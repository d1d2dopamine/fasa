package dev.fasa.tg

import android.content.Context
import dev.fasa.R
import dev.fasa.db.Answer
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.model.Engine
import kotlinx.coroutines.Dispatchers
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

    suspend fun tick(context: Context, longPoll: Boolean = false) = withContext(Dispatchers.IO) {
        if (!Secrets.configured(context)) return@withContext
        val token = Secrets.token(context)
        val chat = Secrets.chatId(context)

        // Order matters. Drain first so a queued question is not duplicated,
        // then read replies, then decide whether anything new is due.
        drain(context, token, chat)
        poll(context, token, chat, longPoll)
        maybeMorning(context)
        maybeEvening(context)
        drain(context, token, chat)
    }

    // True while a question is on screen and recent. The caller uses this to
    // decide between a held connection and a cheap timer.
    suspend fun waiting(context: Context): Boolean {
        if (!Secrets.configured(context)) return false
        val at = Db.get(context).meta().get(K_ASKED_AT)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at in 0..WAIT_WINDOW_MS
    }

    private suspend fun markAsked(context: Context) {
        Db.get(context).meta().put(Meta(K_ASKED_AT, System.currentTimeMillis().toString()))
    }

    private suspend fun clearAsked(context: Context) {
        Db.get(context).meta().put(Meta(K_ASKED_AT, "0"))
    }

    // ---- outbox ----------------------------------------------------------

    // Messages are queued in the database, not sent inline. No connection, a
    // rate limit or a flat battery then costs nothing: the message waits.
    suspend fun enqueue(context: Context, text: String, keyboard: JSONArray?) {
        val db = Db.get(context)
        val arr = readOutbox(context)
        val item = JSONObject().put("text", text)
        if (keyboard != null) item.put("kb", keyboard)
        arr.put(item)
        db.meta().put(Meta(K_OUTBOX, arr.toString()))
    }

    private suspend fun readOutbox(context: Context): JSONArray {
        val raw = Db.get(context).meta().get(K_OUTBOX) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private suspend fun drain(context: Context, token: String, chat: String) {
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
    ) {
        val db = Db.get(context)
        val offset = db.meta().get(K_OFFSET)?.toLongOrNull() ?: 0L
        val (updates, fail) = Telegram.getUpdates(
            token,
            offset,
            if (longPoll) Telegram.LONG_POLL_SEC else 0,
        )
        if (fail != null || updates.isEmpty()) return

        var maxId = offset
        for (u in updates) {
            maxId = maxOf(maxId, u.optLong("update_id") + 1)
            val cb = u.optJSONObject("callback_query") ?: continue
            handleCallback(context, token, chat, cb)
        }
        db.meta().put(Meta(K_OFFSET, maxId.toString()))
    }

    // callback_data is "m:<date>:<value>" for mood and "c:<date>:<value>" for mugs.
    private suspend fun handleCallback(
        context: Context,
        token: String,
        chat: String,
        cb: JSONObject,
    ) {
        val id = cb.optString("id")
        val data = cb.optString("data")
        val parts = data.split(":")
        if (parts.size != 3) {
            Telegram.answerCallback(token, id, "")
            return
        }
        val kind = parts[0]
        val date = parts[1]
        val value = parts[2].toIntOrNull()
        if (value == null) {
            Telegram.answerCallback(token, id, "")
            return
        }

        val db = Db.get(context)
        val existing = db.answers().byDate(date)
        val updated = when (kind) {
            "m" -> Answer(date, value, existing?.mugs, System.currentTimeMillis())
            "c" -> Answer(date, existing?.mood, value, System.currentTimeMillis())
            else -> null
        }
        if (updated == null) {
            Telegram.answerCallback(token, id, "")
            return
        }
        db.answers().put(updated)

        Telegram.answerCallback(token, id, context.getString(R.string.tg_saved))

        // Collapse the answered message so the chat stays a clean log.
        val messageId = cb.optJSONObject("message")?.optInt("message_id")
        if (messageId != null && messageId != 0) {
            val label = if (kind == "m")
                context.getString(R.string.tg_mood_done, moodLabel(context, value))
            else
                context.getString(R.string.tg_mugs_done, value)
            Telegram.editText(token, chat, messageId, label)
        }

        // Wellbeing first, coffee second, one question on screen at a time.
        if (kind == "m" && updated.mugs == null) {
            enqueue(context, context.getString(R.string.tg_q_mugs), mugsKeyboard(date))
            markAsked(context)
        } else if (updated.mood != null && updated.mugs != null) {
            clearAsked(context)
        }

        // A new answer changes the fit.
        runCatching { Engine.refit(context) }
    }

    // ---- outgoing decisions ---------------------------------------------

    // Asked once per recorded wake up, roughly twenty minutes after the band
    // says the night ended. Tied to real data, not to a clock alarm, because a
    // clock alarm is exactly the thing that does not work for this user.
    private suspend fun maybeMorning(context: Context) {
        val db = Db.get(context)
        val wake = db.nights().lastSleepEnd() ?: return
        val now = System.currentTimeMillis()
        val age = now - wake
        if (age < 20 * 60 * 1000L || age > MORNING_WINDOW_MS) return

        val date = Instant.ofEpochMilli(wake).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (db.meta().get(K_MORNING) == date) return

        val existing = db.answers().byDate(date)
        if (existing?.mood != null && existing.mugs != null) return

        if (existing?.mood == null) {
            enqueue(context, context.getString(R.string.tg_q_mood), moodKeyboard(date))
        } else {
            enqueue(context, context.getString(R.string.tg_q_mugs), mugsKeyboard(date))
        }
        db.meta().put(Meta(K_MORNING, date))
        markAsked(context)
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

        val text = StringBuilder()
        text.append(context.getString(R.string.tg_evening_gate, hhmm(f.gate.median)))
        text.append("\n")
        text.append(
            context.getString(
                R.string.tg_evening_range,
                hhmm(f.onset.low),
                hhmm(f.onset.high),
            )
        )
        f.reverseAlarm?.let {
            text.append("\n")
            text.append(context.getString(R.string.tg_evening_reverse, hhmm(it.median)))
        }
        enqueue(context, text.toString(), null)
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
            is Telegram.Reply.Ok -> context.getString(R.string.tg_test_ok)
            is Telegram.Reply.Fail -> context.getString(R.string.tg_test_chat, r.message)
        }
    }

    // ---- keyboards -------------------------------------------------------

    private fun moodLabel(context: Context, value: Int): String = context.getString(
        when (value) {
            1 -> R.string.tg_mood_1
            2 -> R.string.tg_mood_2
            3 -> R.string.tg_mood_3
            4 -> R.string.tg_mood_4
            else -> R.string.tg_mood_5
        }
    )

    private fun moodKeyboard(date: String): JSONArray = Telegram.keyboard(
        Telegram.row("1" to "m:$date:1", "2" to "m:$date:2", "3" to "m:$date:3"),
        Telegram.row("4" to "m:$date:4", "5" to "m:$date:5"),
    )

    private fun mugsKeyboard(date: String): JSONArray = Telegram.keyboard(
        Telegram.row("0" to "c:$date:0", "1" to "c:$date:1", "2" to "c:$date:2"),
        Telegram.row("3" to "c:$date:3", "4" to "c:$date:4", "5+" to "c:$date:5"),
    )

    private fun hhmm(hour: Double): String {
        var h = hour % 24.0
        if (h < 0) h += 24.0
        val total = Math.round(h * 60.0).toInt()
        val hh = (total / 60) % 24
        val mm = total % 60
        return String.format("%02d:%02d", hh, mm)
    }
}
