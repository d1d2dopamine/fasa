package dev.fasa.tg

import android.content.Context
import dev.fasa.Prefs
import dev.fasa.R
import dev.fasa.Store
import dev.fasa.work.LightService
import dev.fasa.db.Answer
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.db.Night
import dev.fasa.model.Engine
import dev.fasa.work.Screen
import dev.fasa.work.SyncWorker
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Everything the chat can be asked to do.
//
// Two modes, because one size does not fit here. Hands free is the default and
// the reason this project exists: sleep comes from the band, bedtime from the
// screen going dark, coffee from one question in the morning. Manual mode adds
// buttons for people with no band, or for a night where the automatic guess is
// obviously wrong.
object Commands {

    // A bedtime tapped by hand, waiting for the matching wake up.
    const val K_MANUAL_BED = "manual_bed_at"

    private const val MIN_SLEEP_MS = 30L * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000

    // A bedtime further back than this is not the night that just ended.
    private const val MAX_SLEEP_MS = 16L * 60 * 60 * 1000

    // The day for coffee starts at four in the morning, not at midnight. A mug
    // at one at night belongs to the evening before, which is also how the body
    // treats it.
    private const val DAY_START_H = 4L

    // Refits are expensive and answers arrive in bursts. One pass covers them.
    private const val REFIT_GAP_MS = 2L * 60 * 1000
    private const val K_REFIT_AT = "refit_at"
    private const val K_REFIT_PENDING = "refit_pending"

    // A night built from an unanswered guess, and the same night once the
    // latency question has been answered.
    private const val SOURCE_GUESS = "manual"
    private const val SOURCE_MEASURED = "manual_ok"

    // Used only for manual nights, where nobody measured how long falling
    // asleep took. The filter treats it as an ordinary observation, so a wrong
    // guess here is diluted by every real night.
    private const val ASSUMED_LATENCY_MIN = 25

    // ---- routing ---------------------------------------------------------

    suspend fun handleMessage(context: Context, msg: JSONObject) {
        val raw = msg.optString("text").trim()
        if (raw.isEmpty()) return

        // Until a language is picked, anything at all gets the start message.
        if (!Lang.chosen(context)) {
            start(context)
            return
        }

        when (action(context, raw)) {
            "start" -> start(context)
            "help" -> help(context)
            "lang" -> askLang(context)
            "mode" -> askMode(context)
            "forecast" -> forecast(context)
            "status" -> status(context)
            "last" -> last(context)
            "bed" -> bed(context)
            "up" -> up(context)
            "coffee" -> coffee(context)
            else -> say(context, Lang.string(context, R.string.tgb_unknown))
        }
    }

    // Commands are matched by name, keyboard buttons by their label. Labels are
    // checked in both languages so switching language cannot leave a stale
    // keyboard that no longer works.
    private fun action(context: Context, raw: String): String? {
        val t = raw.substringBefore('@').trim().lowercase()
        if (t.startsWith("/")) {
            return when (t.removePrefix("/")) {
                "start" -> "start"
                "help" -> "help"
                "lang", "language" -> "lang"
                "mode" -> "mode"
                "forecast" -> "forecast"
                "status" -> "status"
                "last" -> "last"
                "bed" -> "bed"
                "up" -> "up"
                "coffee" -> "coffee"
                else -> null
            }
        }
        val n = norm(raw)
        for (tag in listOf("en", "ru")) {
            val c = Lang.ctxFor(context, tag)
            when (n) {
                norm(c.getString(R.string.tgb_b_forecast)) -> return "forecast"
                norm(c.getString(R.string.tgb_b_status)) -> return "status"
                norm(c.getString(R.string.tgb_b_bed)) -> return "bed"
                norm(c.getString(R.string.tgb_b_up)) -> return "up"
                norm(c.getString(R.string.tgb_b_coffee)) -> return "coffee"
            }
        }
        return null
    }

    // Button labels are the protocol, so matching must survive a stray space, a
    // capital letter, punctuation, or the Russian letter that half of all
    // keyboards type differently.
    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (raw in s.lowercase()) {
            val ch = if (raw == '\u0451') '\u0435' else raw
            if (ch.isLetterOrDigit()) sb.append(ch)
        }
        return sb.toString()
    }

    // ---- language and mode ----------------------------------------------

    // Always English: at this point nobody has told us anything else.
    private suspend fun start(context: Context) {
        val en = Lang.ctxFor(context, "en")
        val ru = Lang.ctxFor(context, "ru")
        Bot.enqueue(
            context,
            en.getString(R.string.tgb_start),
            Telegram.keyboard(
                Telegram.row(
                    en.getString(R.string.lang_en) to "l:en",
                    ru.getString(R.string.lang_ru) to "l:ru",
                )
            ),
        )
    }

    suspend fun setLang(context: Context, tag: String) {
        Store.saveLang(context, tag)
        publishCommands(context)
        say(context, Lang.string(context, R.string.tgb_lang_set))
        help(context)
    }

    // Fills the hint list Telegram shows while typing a slash. Re-sent whenever
    // the language or the mode changes, so it always matches the keyboard.
    internal suspend fun publishCommands(context: Context) {
        val token = Secrets.token(context)
        if (token.isEmpty()) return
        val c = Lang.ctx(context)
        val list = ArrayList<Pair<String, String>>()
        list.add("help" to c.getString(R.string.tgb_cmd_help))
        list.add("forecast" to c.getString(R.string.tgb_cmd_forecast))
        list.add("status" to c.getString(R.string.tgb_cmd_status))
        list.add("last" to c.getString(R.string.tgb_cmd_last))
        list.add("mode" to c.getString(R.string.tgb_cmd_mode))
        list.add("lang" to c.getString(R.string.tgb_cmd_lang))
        if (Prefs.manualMode(context)) {
            list.add("bed" to c.getString(R.string.tgb_cmd_bed))
            list.add("up" to c.getString(R.string.tgb_cmd_up))
            list.add("coffee" to c.getString(R.string.tgb_cmd_coffee))
        }
        runCatching { Telegram.setMyCommands(token, list) }
    }

    private suspend fun askLang(context: Context) {
        Bot.enqueue(
            context,
            Lang.string(context, R.string.tgb_lang_ask),
            Telegram.keyboard(
                Telegram.row(
                    Lang.ctxFor(context, "en").getString(R.string.lang_en) to "l:en",
                    Lang.ctxFor(context, "ru").getString(R.string.lang_ru) to "l:ru",
                )
            ),
        )
    }

    private suspend fun askMode(context: Context) {
        val c = Lang.ctx(context)
        Bot.enqueue(
            context,
            c.getString(R.string.tgb_mode_ask),
            Telegram.keyboard(
                Telegram.row(c.getString(R.string.tgb_mode_auto) to "k:a"),
                Telegram.row(c.getString(R.string.tgb_mode_manual) to "k:m"),
            ),
        )
    }

    suspend fun setMode(context: Context, manual: Boolean) {
        Store.saveMode(context, manual)
        publishCommands(context)
        // The keyboard is part of the mode, so it is redrawn immediately.
        say(context, Lang.string(context, R.string.tgb_mode_now, modeName(context)))
    }

    private fun modeName(context: Context): String = Lang.string(
        context,
        if (Prefs.manualMode(context)) R.string.tgb_mode_manual else R.string.tgb_mode_auto,
    )

    suspend fun help(context: Context) {
        val text = StringBuilder()
        text.append(Lang.string(context, R.string.tgb_help))
        text.append("\n\n")
        text.append(Lang.string(context, R.string.tgb_mode_now, modeName(context)))
        say(context, text.toString())
    }

    // ---- answers ---------------------------------------------------------

    private suspend fun forecast(context: Context) {
        say(context, forecastText(context) ?: Lang.string(context, R.string.tgb_no_forecast))
    }

    // Shared with the evening message so the chat never contradicts itself.
    internal suspend fun forecastText(context: Context): String? {
        val f = runCatching { Engine.forecast(context) }.getOrNull() ?: return null
        val sb = StringBuilder()
        sb.append(Lang.string(context, R.string.tg_evening_gate, hhmm(f.gate.median)))
        sb.append("\n")
        sb.append(
            Lang.string(context, R.string.tg_evening_range, hhmm(f.onset.low), hhmm(f.onset.high))
        )
        f.reverseAlarm?.let {
            sb.append("\n")
            sb.append(Lang.string(context, R.string.tg_evening_reverse, hhmm(it.median)))
        }
        sb.append("\n")
        sb.append(Lang.string(context, R.string.f_nights, f.nights))
        return sb.toString()
    }

    // One glance at whether the pipeline is still alive. If the phone kills the
    // app, this is where it shows up first.
    private suspend fun status(context: Context) {
        val db = Db.get(context)
        val nights = runCatching { db.nights().count() }.getOrDefault(0)
        val last = db.meta().get(SyncWorker.KEY_LAST_DATA)?.toLongOrNull()
        val mugs = db.answers().byDate(dayKey())?.mugs ?: 0
        val beat = db.meta().get(LightService.K_BEAT)?.toLongOrNull() ?: 0L

        val sb = StringBuilder()
        sb.append(Lang.string(context, R.string.tgb_st_mode, modeName(context)))
        sb.append("\n")
        // The background service is the part the phone likes to kill. Reporting
        // it separately from the data age says whether the app is broken or
        // just waiting for the band to sync.
        val silent = System.currentTimeMillis() - beat
        if (beat > 0L && silent < LightService.BEAT_STALE_MS) {
            sb.append(
                Lang.string(context, R.string.tgb_st_service_ok, (silent / 60_000L).toInt())
            )
        } else {
            sb.append(Lang.string(context, R.string.tgb_st_service_off))
        }
        sb.append("\n")
        sb.append(Lang.string(context, R.string.tgb_st_nights, nights))
        sb.append("\n")
        if (last == null || last <= 0L) {
            sb.append(Lang.string(context, R.string.tgb_st_data_never))
        } else {
            val hours = ((System.currentTimeMillis() - last) / HOUR_MS).toInt().coerceAtLeast(0)
            sb.append(Lang.string(context, R.string.tgb_st_data, hours))
        }
        sb.append("\n")
        sb.append(Lang.string(context, R.string.tgb_st_coffee, mugs))
        say(context, sb.toString())
    }

    // Kept apart from /status on purpose: one command answers "is the app
    // alive", the other answers "what did the band actually measure". Merged,
    // neither gets read.
    private suspend fun last(context: Context) {
        val night = runCatching {
            Db.get(context).nights().lastEnded(System.currentTimeMillis())
        }.getOrNull()
        if (night == null) {
            say(context, Lang.string(context, R.string.tgb_last_none))
            return
        }
        val sb = StringBuilder()
        sb.append(
            Lang.string(
                context,
                R.string.tgb_st_last,
                Lang.string(
                    context,
                    R.string.ln_range,
                    clock(night.sleepStart),
                    clock(night.sleepEnd),
                ),
                Lang.string(
                    context,
                    R.string.ln_dur,
                    night.minutesAsleep / 60,
                    night.minutesAsleep % 60,
                ),
            )
        )
        sb.append("\n")
        if (night.minutesDeep > 0 || night.minutesRem > 0) {
            sb.append(
                Lang.string(
                    context,
                    R.string.tgb_st_phases,
                    night.minutesDeep,
                    night.minutesRem,
                    night.minutesAwake,
                )
            )
        } else {
            sb.append(Lang.string(context, R.string.tgb_phases_none))
        }
        sb.append("\n")
        val hrMin = night.hrMin
        val hrMean = night.hrMean
        if (hrMin == null || hrMean == null) {
            sb.append(Lang.string(context, R.string.tgb_st_hr_none))
        } else {
            sb.append(
                Lang.string(
                    context,
                    R.string.tgb_st_hr,
                    hrMin,
                    clock(night.hrMinAt ?: night.sleepStart),
                    hrMean,
                )
            )
        }
        say(context, sb.toString())
    }

    // ---- manual events ---------------------------------------------------

    private suspend fun bed(context: Context) {
        val now = System.currentTimeMillis()
        // Deliberately kept out of the screen off log. That log is the raw
        // record of when the phone went dark, and the delay model measures
        // habit against it; feeding hand typed times into it would make the
        // habit look artificially steady.
        Db.get(context).meta().put(Meta(K_MANUAL_BED, now.toString()))
        say(context, Lang.string(context, R.string.tgb_bed_ok, clock(now)))
    }

    private suspend fun up(context: Context) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        var bed = db.meta().get(K_MANUAL_BED)?.toLongOrNull() ?: 0L
        var fromScreen = false

        // Forgetting to tap "going to bed" is the normal case, not an error.
        // The screen going dark says the same thing and is always recorded.
        if (bed <= 0L || now - bed < MIN_SLEEP_MS) {
            val guess = runCatching { Screen.all(context) }
                .getOrDefault(emptyList())
                .lastOrNull { now - it in MIN_SLEEP_MS..MAX_SLEEP_MS }
            if (guess == null) {
                say(context, Lang.string(context, R.string.tgb_up_none))
                return
            }
            bed = guess
            fromScreen = true
        }

        // A band that already logged this night beats anything typed by hand.
        val lastEnd = db.nights().lastSleepEnd() ?: 0L
        if (lastEnd > bed) {
            db.meta().put(Meta(K_MANUAL_BED, "0"))
            say(context, Lang.string(context, R.string.tgb_up_band))
            return
        }

        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val start = bed + ASSUMED_LATENCY_MIN * 60_000L
        val asleep = ((now - start) / 60_000L).toInt().coerceAtLeast(0)
        db.nights().put(
            Night(
                dateKey = date,
                sleepStart = start,
                sleepEnd = now,
                minutesAsleep = asleep,
                minutesDeep = 0,
                minutesRem = 0,
                minutesAwake = ASSUMED_LATENCY_MIN,
                hrMin = null,
                hrMinAt = null,
                hrMean = null,
                spo2Mean = null,
                source = SOURCE_GUESS,
                importedAt = now,
            )
        )
        db.meta().put(Meta(K_MANUAL_BED, "0"))
        refitSoon(context)
        if (fromScreen) say(context, Lang.string(context, R.string.tgb_up_screen, clock(bed)))
        say(context, Lang.string(context, R.string.tgb_up_ok, asleep / 60, asleep % 60))
        askLatency(context, date)
    }

    // Twenty five minutes was an assumption. Asking turns it into data, and one
    // tap is cheap enough to be worth it.
    private suspend fun askLatency(context: Context, date: String) {
        Bot.enqueue(
            context,
            Lang.string(context, R.string.tgb_q_latency),
            Telegram.keyboard(
                Telegram.row(
                    "5" to "d:$date:5",
                    "15" to "d:$date:15",
                    "30" to "d:$date:30",
                    "60+" to "d:$date:60",
                )
            ),
        )
        Bot.markAsked(context)
    }

    // Rewrites the night with the real latency. Sleep start moves, so the model
    // sees the same night with a better onset.
    suspend fun setLatency(context: Context, date: String, minutes: Int) {
        val db = Db.get(context)
        val night = db.nights().all().lastOrNull { it.dateKey == date } ?: return
        val bed = night.sleepStart - night.minutesAwake * 60_000L
        val start = bed + minutes * 60_000L
        val asleep = ((night.sleepEnd - start) / 60_000L).toInt().coerceAtLeast(0)
        db.nights().put(
            night.copy(
                sleepStart = start,
                minutesAsleep = asleep,
                minutesAwake = minutes,
                source = SOURCE_MEASURED,
            )
        )
        refitSoon(context, force = true)
        say(context, Lang.string(context, R.string.tgb_lat_ok, minutes))
    }

    // Counted the moment it is drunk, which is far better than remembering it
    // the next morning.
    private suspend fun coffee(context: Context) {
        val db = Db.get(context)
        val date = dayKey()
        val existing = db.answers().byDate(date)
        val mugs = (existing?.mugs ?: 0) + 1
        db.answers().put(Answer(date, existing?.mood, mugs, System.currentTimeMillis()))
        refitSoon(context)
        say(context, Lang.string(context, R.string.tgb_coffee_ok, mugs))
    }

    // Which day a moment belongs to, counted from four in the morning.
    internal fun dayKey(at: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(at)
            .atZone(ZoneId.systemDefault())
            .minusHours(DAY_START_H)
            .toLocalDate()
            .toString()

    // ---- refit throttle ---------------------------------------------------

    // Three mugs in a row used to mean three full refits. Now they mean one.
    internal suspend fun refitSoon(context: Context, force: Boolean = false) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val last = db.meta().get(K_REFIT_AT)?.toLongOrNull() ?: 0L
        if (!force && now - last < REFIT_GAP_MS) {
            db.meta().put(Meta(K_REFIT_PENDING, "1"))
            return
        }
        db.meta().put(Meta(K_REFIT_AT, now.toString()))
        db.meta().put(Meta(K_REFIT_PENDING, "0"))
        runCatching { Engine.refit(context) }
    }

    // A refit that was skipped as too soon still has to happen. The poll loop
    // picks it up on its next pass.
    internal suspend fun flushRefit(context: Context) {
        val db = Db.get(context)
        if (db.meta().get(K_REFIT_PENDING) != "1") return
        val last = db.meta().get(K_REFIT_AT)?.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < REFIT_GAP_MS) return
        refitSoon(context, force = true)
    }

    // ---- plumbing --------------------------------------------------------

    private suspend fun say(context: Context, text: String) {
        Bot.enqueue(context, text, null, menu(context))
    }

    // The persistent keyboard. Manual mode adds a row, hands free mode keeps it
    // to the two things worth asking for.
    fun menu(context: Context): JSONArray {
        val c = Lang.ctx(context)
        val rows = ArrayList<JSONArray>()
        rows.add(
            Telegram.textRow(
                c.getString(R.string.tgb_b_forecast),
                c.getString(R.string.tgb_b_status),
            )
        )
        if (Prefs.manualMode(context)) {
            rows.add(
                Telegram.textRow(
                    c.getString(R.string.tgb_b_bed),
                    c.getString(R.string.tgb_b_up),
                    c.getString(R.string.tgb_b_coffee),
                )
            )
        }
        return Telegram.keyboard(*rows.toTypedArray())
    }

    internal fun hhmm(hour: Double): String {
        var h = hour % 24.0
        if (h < 0) h += 24.0
        val total = Math.round(h * 60.0).toInt()
        return String.format("%02d:%02d", (total / 60) % 24, total % 60)
    }

    private fun clock(at: Long): String {
        val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
        return String.format("%02d:%02d", t.hour, t.minute)
    }
}
