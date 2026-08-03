package dev.vespian.tg

import android.content.Context
import dev.vespian.Prefs
import dev.vespian.R
import dev.vespian.db.Answer
import dev.vespian.db.Db
import dev.vespian.db.Forced
import dev.vespian.db.Meta
import dev.vespian.model.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// All bot behaviour lives here: what to ask, when to ask it, and what to do
// with the answers.
//
// Everything is driven by the hourly worker. There is no push, no service and
// no alarm; if the phone was asleep the questions simply go out late, and
// Telegram keeps unread updates for 24 hours so no tap is ever lost.
object Bot {

    const val K_OFFSET = "tg_update_offset"
    const val K_OUTBOX = "tg_outbox"
    const val K_MORNING = "tg_morning_date"
    const val K_EVENING = "tg_evening_date"

    // When the last unanswered question went out. While this is set and fresh,
    // the light service holds a connection open instead of polling on a timer.
    const val K_ASKED_AT = "tg_asked_at"

    // Wake ups older than this are not worth asking about any more.
    private const val MORNING_WINDOW_MS = 8L * 3600 * 1000

    // How long to keep waiting on an open connection after asking. Long enough
    // to cover a normal reply, short enough that ignoring the bot costs
    // nothing.
    private const val WAIT_WINDOW_MS = 30L * 60 * 1000

    // Returns true when at least one update was read, so the caller can tell a
    // useful tick from an empty one and back off when the network is down.
    suspend fun tick(context: Context, longPoll: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!Secrets.configured(context)) return@withContext false
        val token = Secrets.token(context)
        val chat = Secrets.chatId(context)

        // Order matters. Drain first so a queued question is not duplicated,
        // then read replies, then decide whether anything new is due.
        drain(context, token, chat)
        val got = poll(context, token, chat, longPoll)
        // A refit that was postponed as too soon happens here.
        runCatching { Commands.flushRefit(context) }
        maybeMorning(context)
        maybeEvening(context)
        drain(context, token, chat)
        got
    }

    // True while a question is on screen and recent. The caller uses this to
    // decide between a held connection and a cheap timer.
    suspend fun waiting(context: Context): Boolean {
        if (!Secrets.configured(context)) return false
        val at = Db.get(context).meta().get(K_ASKED_AT)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at in 0..WAIT_WINDOW_MS
    }

    suspend fun markAsked(context: Context) {
        Db.get(context).meta().put(Meta(K_ASKED_AT, System.currentTimeMillis().toString()))
    }

    private suspend fun clearAsked(context: Context) {
        Db.get(context).meta().put(Meta(K_ASKED_AT, "0"))
    }

    // ---- outbox ----------------------------------------------------------

    // Messages are queued in the database, not sent inline. No connection, a
    // rate limit or a flat battery then costs nothing: the message waits.
    suspend fun enqueue(
        context: Context,
        text: String,
        keyboard: JSONArray?,
        replyKeyboard: JSONArray? = null,
    ) {
        val db = Db.get(context)
        val arr = readOutbox(context)
        val item = JSONObject().put("text", text)
        if (keyboard != null) item.put("kb", keyboard)
        if (replyKeyboard != null) item.put("rkb", replyKeyboard)
        arr.put(item)
        db.meta().put(Meta(K_OUTBOX, arr.toString()))

        // Try to send right away. The queue stays as the safety net for a dead
        // network; without this push a reply could sit for the whole idle
        // period before anyone looked at the queue.
        if (Secrets.configured(context)) {
            runCatching { drain(context, Secrets.token(context), Secrets.chatId(context)) }
        }
    }

    private suspend fun readOutbox(context: Context): JSONArray {
        val raw = Db.get(context).meta().get(K_OUTBOX) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    // Only one sender at a time. The service loop and an immediate push after
    // enqueue can otherwise both read the same queue and send it twice.
    private val sendLock = Mutex()

    private suspend fun drain(context: Context, token: String, chat: String) =
        sendLock.withLock { drainLocked(context, token, chat) }

    private suspend fun drainLocked(context: Context, token: String, chat: String) {
        val db = Db.get(context)
        var queue = readOutbox(context)
        if (queue.length() == 0) return

        while (queue.length() > 0) {
            val item = queue.optJSONObject(0) ?: run {
                queue = removeFirst(queue); null
            } ?: continue

            val reply = Telegram.sendMessage(
                token,
                chat,
                item.optString("text"),
                item.optJSONArray("kb"),
                item.optJSONArray("rkb"),
            )
            when (reply) {
                is Telegram.Reply.Ok -> queue = removeFirst(queue)
                is Telegram.Reply.Fail -> {
                    // 4xx means the message itself is wrong: bad chat id, bot
                    // blocked, malformed keyboard. Retrying forever would jam the
                    // queue behind it, so it is dropped. Anything else is
                    // temporary and keeps its place in the queue.
                    if (reply.code in 400..499) queue = removeFirst(queue) else break
                }
            }
        }
        db.meta().put(Meta(K_OUTBOX, queue.toString()))
    }

    private fun removeFirst(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 1 until arr.length()) out.put(arr.get(i))
        return out
    }

    // ---- incoming --------------------------------------------------------

    private suspend fun poll(
        context: Context,
        token: String,
        chat: String,
        longPoll: Boolean = false,
    ): Boolean {
        val db = Db.get(context)
        val offset = db.meta().get(K_OFFSET)?.toLongOrNull() ?: 0L
        val (updates, fail) = Telegram.getUpdates(
            token,
            offset,
            if (longPoll) Telegram.LONG_POLL_SEC else 0,
        )
        if (fail != null || updates.isEmpty()) return false

        var maxId = offset
        for (u in updates) {
            maxId = maxOf(maxId, u.optLong("update_id") + 1)
            val cb = u.optJSONObject("callback_query")
            if (cb != null) {
                // One bad tap must never wedge the stream. Without this guard a
                // throw here skips the offset write below, so the same update is
                // replayed for ever and nothing newer is ever seen.
                runCatching { handleCallback(context, token, chat, cb) }
                continue
            }
            // Typed commands and taps on the persistent keyboard both arrive as
            // ordinary messages.
            val msg = u.optJSONObject("message") ?: continue
            runCatching { Commands.handleMessage(context, msg) }
        }
        db.meta().put(Meta(K_OFFSET, maxId.toString()))
        return true
    }

    // callback_data is "m:<date>:<value>" for mood, "c:<date>:<value>" for
    // mugs of coffee, "e:<date>:<value>" for cans and "a:<date>:<value>" for
    // standard drinks. Every one of them is a count reached by tapping a
    // number. Nothing here ever asks for millilitres or milligrams: what a mug
    // and a can are worth is set once in the app settings, and a question that
    // has to be worked out is a question that stops being answered.
    private suspend fun handleCallback(
        context: Context,
        token: String,
        chat: String,
        cb: JSONObject,
    ) {
        val id = cb.optString("id")
        val data = cb.optString("data")

        // The spinner on the tapped button stops only when Telegram receives
        // this call, and Telegram refuses it a few seconds after the tap. So it
        // goes first, before any database write, model refit or second request.
        runCatching { Telegram.answerCallback(token, id, "") }

        // Language and mode taps are two part codes and are settled here. The
        // answer buttons below are three part codes.
        if (data.startsWith("l:")) {
            Commands.setLang(context, if (data.endsWith("ru")) "ru" else "en")
            return
        }
        if (data.startsWith("k:")) {
            Commands.setMode(context, data.endsWith("m"))
            return
        }
        // How long falling asleep took, asked right after a manual wake up.
        if (data.startsWith("d:")) {
            val p = data.split(":")
            val minutes = p.getOrNull(2)?.toIntOrNull()
            if (p.size == 3 && minutes != null) Commands.setLatency(context, p[1], minutes)
            clearAsked(context)
            return
        }

        // The night was ended by an alarm or by another person rather than by
        // the body. Applied immediately: this is tapped by someone who has just
        // woken up, and a confirmation dialog every morning costs more than the
        // rare mis-tap it would prevent. The undo button below is the safety
        // net, and it is one tap as well.
        if (data.startsWith("w:")) {
            val p = data.split(":")
            if (p.size == 3) {
                val on = p[2] == "1"
                runCatching { Forced.set(context, p[1], on) }
                if (on) {
                    enqueue(
                        context,
                        Lang.string(context, R.string.tg_forced_on),
                        Telegram.keyboard(
                            Telegram.row(
                                Lang.string(context, R.string.tg_forced_undo) to "w:${p[1]}:0"
                            )
                        ),
                    )
                } else {
                    val mid = cb.optJSONObject("message")?.optInt("message_id")
                    if (mid != null && mid != 0) {
                        Telegram.editText(
                            token, chat, mid,
                            Lang.string(context, R.string.tg_forced_off),
                        )
                    }
                }
                runCatching { Commands.refitSoon(context) }
            }
            return
        }

        // "Do not ask again" on a drink question. Silences the morning drink
        // question for good; the counters in the app and the commands in the
        // chat keep working, and the switch in Settings brings it back.
        if (data.startsWith("q:")) {
            Prefs.setAskDrinks(context, false)
            val mid = cb.optJSONObject("message")?.optInt("message_id")
            if (mid != null && mid != 0) {
                Telegram.editText(
                    token, chat, mid,
                    Lang.string(context, R.string.tg_ask_off),
                )
            }
            clearAsked(context)
            return
        }

        val parts = data.split(":")
        if (parts.size != 3) return
        val kind = parts[0]
        val date = parts[1]
        val value = parts[2].toIntOrNull()
        if (value == null) return

        val db = Db.get(context)
        val existing = db.answers().byDate(date)
        val now = System.currentTimeMillis()
        val base = existing
            ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
        val updated = when (kind) {
            "m" -> base.copy(mood = value, at = now)
            "c" -> base.copy(mugs = value, at = now)
            "e" -> base.copy(cans = value, at = now)
            "a" -> base.copy(alcohol = value, at = now)
            else -> null
        }
        if (updated == null) return
        db.answers().put(updated)

        // Collapse the answered message so the chat stays a clean log.
        val messageId = cb.optJSONObject("message")?.optInt("message_id")
        if (messageId != null && messageId != 0) {
            val label = when (kind) {
                "m" -> Lang.string(context, R.string.tg_mood_done, moodLabel(context, value))
                "e" -> Lang.string(context, R.string.tg_cans_done, value)
                "a" -> Lang.string(context, R.string.tg_alcohol_done, value)
                else -> Lang.string(context, R.string.tg_mugs_done, value)
            }
            Telegram.editText(token, chat, messageId, label)
        }

        // One question on screen at a time, in a fixed order: wellbeing,
        // coffee, then whichever extra drinks are switched on. Everything past
        // coffee is off by default, so by default this is still two taps.
        // Wellbeing is about the morning that just happened, drinks are about
        // the day the night started, so after the mood answer the chain has to
        // change date rather than carry on with the same one.
        val next = if (kind == "m") {
            drinkDayAnswer(context)?.let { nextDrinkQuestion(context, it) }
        } else {
            nextDrinkQuestion(context, updated)
        }
        if (next != null) {
            enqueue(context, next.first, next.second)
            markAsked(context)
        } else {
            clearAsked(context)
        }

        // A new answer changes the fit, but a burst of answers should still
        // cost one pass.
        runCatching { Commands.refitSoon(context) }
    }

    // ---- outgoing decisions ---------------------------------------------

    // Asked once per recorded wake up, roughly twenty minutes after the band
    // says the night ended. Tied to real data, not to a clock alarm, because a
    // clock alarm is exactly the thing that does not work for this user.
    private suspend fun maybeMorning(context: Context) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val night = db.nights().lastEnded(now) ?: return
        val age = now - night.sleepEnd
        if (age < 20 * 60 * 1000L || age > MORNING_WINDOW_MS) return

        val wakeDate = Instant.ofEpochMilli(night.sleepEnd)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (db.meta().get(K_MORNING) == wakeDate) return

        // Wellbeing belongs to the morning it is asked about. Drinks belong to
        // the day the night began: coffee drunk before falling asleep is not
        // coffee drunk today, and writing it to today is what produced a mug
        // nobody had.
        val morning = db.answers().byDate(wakeDate)
        val question = if (morning?.mood == null) {
            Lang.string(context, R.string.tg_q_mood) to moodKeyboard(context, wakeDate)
        } else {
            drinkDayAnswer(context)?.let { nextDrinkQuestion(context, it) }
        } ?: return

        enqueue(context, question.first, question.second)
        db.meta().put(Meta(K_MORNING, wakeDate))
        markAsked(context)
    }

    // The answer row for the day the last finished night started, created empty
    // when nothing was logged that day.
    private suspend fun drinkDayAnswer(context: Context): Answer? {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val night = db.nights().lastEnded(now) ?: return null
        val date = Commands.dayKey(night.sleepStart)
        return db.answers().byDate(date)
            ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
    }

    // Two hours before the predicted window, once a day. Not a question.
    private suspend fun maybeEvening(context: Context) {
        val db = Db.get(context)
        val today = LocalDate.now().toString()
        if (db.meta().get(K_EVENING) == today) return

        val f = runCatching { Engine.forecast(context) }.getOrNull() ?: return
        val offset = Engine.offsetHours()
        val nowHour = Engine.hourOf(System.currentTimeMillis(), offset)
        val lead = f.gate.median - nowHour
        if (lead > 2.0 || lead < 0.0) return

        // Same wording as the forecast command, so the chat cannot contradict
        // itself an hour apart.
        val text = Commands.forecastText(context) ?: return
        enqueue(context, text, null, Commands.menu(context))
        db.meta().put(Meta(K_EVENING, today))
    }

    // ---- manual test -----------------------------------------------------

    // Used by the settings button. Returns a human readable outcome instead of
    // a silent success, because "nothing happened" is the worst possible answer
    // when a token is wrong.
    suspend fun test(context: Context): String {
        val token = Secrets.token(context)
        val chat = Secrets.chatId(context)
        if (token.isEmpty() || chat.isEmpty()) return context.getString(R.string.tg_test_empty)

        when (val me = Telegram.getMe(token)) {
            is Telegram.Reply.Fail -> return context.getString(R.string.tg_test_token, me.message)
            is Telegram.Reply.Ok -> Unit
        }
        return when (val r = Telegram.sendMessage(token, chat, context.getString(R.string.tg_test_text), null)) {
            is Telegram.Reply.Ok -> {
                // Good moment to register the slash command hints: the token is
                // known to work and this runs on every save.
                runCatching { Commands.publishCommands(context) }
                context.getString(R.string.tg_test_ok)
            }
            is Telegram.Reply.Fail -> context.getString(R.string.tg_test_chat, r.message)
        }
    }

    // ---- keyboards -------------------------------------------------------

    private fun moodLabel(context: Context, value: Int): String = Lang.string(
        context,
        when (value) {
            1 -> R.string.tg_mood_1
            2 -> R.string.tg_mood_2
            3 -> R.string.tg_mood_3
            4 -> R.string.tg_mood_4
            else -> R.string.tg_mood_5
        }
    )

    // The morning question carries the "woken up" button with it, on both
    // variants. Remembering to send a separate command while half awake is not
    // something to design around; the message that already arrives is.
    private fun forcedRow(context: Context, date: String): JSONArray = Telegram.row(
        Lang.string(context, R.string.tg_forced_btn) to "w:$date:1"
    )

    private fun moodKeyboard(context: Context, date: String): JSONArray = Telegram.keyboard(
        Telegram.row("1" to "m:$date:1", "2" to "m:$date:2", "3" to "m:$date:3"),
        Telegram.row("4" to "m:$date:4", "5" to "m:$date:5"),
        forcedRow(context, date),
    )

    // Only the drink questions carry it: wellbeing is one tap and is what the
    // filter leans on hardest, so it stays.
    private fun askOffRow(context: Context): JSONArray = Telegram.row(
        Lang.string(context, R.string.tg_ask_off_btn) to "q:0"
    )

    private fun mugsKeyboard(context: Context, date: String): JSONArray = Telegram.keyboard(
        Telegram.row("0" to "c:$date:0", "1" to "c:$date:1", "2" to "c:$date:2"),
        Telegram.row("3" to "c:$date:3", "4" to "c:$date:4", "5+" to "c:$date:5"),
        forcedRow(context, date),
        askOffRow(context),
    )

    private fun cansKeyboard(context: Context, date: String): JSONArray = Telegram.keyboard(
        Telegram.row("0" to "e:$date:0", "1" to "e:$date:1", "2" to "e:$date:2"),
        Telegram.row("3" to "e:$date:3", "4" to "e:$date:4", "5+" to "e:$date:5"),
        forcedRow(context, date),
        askOffRow(context),
    )

    private fun alcoholKeyboard(context: Context, date: String): JSONArray = Telegram.keyboard(
        Telegram.row("0" to "a:$date:0", "1" to "a:$date:1", "2" to "a:$date:2"),
        Telegram.row("3" to "a:$date:3", "4" to "a:$date:4", "5+" to "a:$date:5"),
        forcedRow(context, date),
        askOffRow(context),
    )

    /**
     * The next thing worth asking about this day, or null when the day is
     * fully answered.
     *
     * A day counts as answered when every question that is switched on has a
     * value. Zero is a value: a day with no coffee at all is exactly the kind
     * of day the model needs in order to tell a strong reaction apart from a
     * slow one, so "none" is recorded rather than left blank.
     */
    private fun nextDrinkQuestion(context: Context, answer: Answer): Pair<String, JSONArray>? = when {
        !Prefs.askDrinks(context) -> null
        answer.mugs == null ->
            Lang.string(context, R.string.tg_q_mugs) to mugsKeyboard(context, answer.dateKey)
        Prefs.energyOn(context) && answer.cans == null ->
            Lang.string(context, R.string.tg_q_cans) to cansKeyboard(context, answer.dateKey)
        Prefs.alcoholOn(context) && answer.alcohol == null ->
            Lang.string(context, R.string.tg_q_alcohol) to alcoholKeyboard(context, answer.dateKey)
        else -> null
    }
}
