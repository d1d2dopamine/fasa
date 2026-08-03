package dev.vespian.tg

import android.content.Context
import dev.vespian.Prefs
import dev.vespian.R
import dev.vespian.Store
import dev.vespian.work.LightService
import dev.vespian.db.Answer
import dev.vespian.db.Db
import dev.vespian.db.Forced
import dev.vespian.db.Meta
import dev.vespian.db.Night
import dev.vespian.export.Export
import dev.vespian.model.Behaviour
import dev.vespian.model.Engine
import dev.vespian.work.Screen
import dev.vespian.work.SyncWorker
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

        // A tap can fail: no network at that second, or Telegram refusing a
        // late answer. Typing the language must always work, otherwise one dead
        // button locks the whole bot out with no way back.
        langFromText(raw)?.let {
            setLang(context, it)
            return
        }

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
            "why" -> why(context)
            "export" -> export(context)
            "wakeup" -> wakeup(context)
            "corr" -> corr(context)
            "status" -> status(context)
            "last" -> last(context)
            "bed" -> bed(context)
            "up" -> up(context)
            "coffee" -> coffee(context)
            "energy" -> energy(context)
            "alcohol" -> alcohol(context)
            else -> {
                // A bare digit is the wellbeing answer typed by hand, for when
                // the buttons on the morning question no longer respond.
                val mood = raw.toIntOrNull()
                if (mood != null && mood in 1..5) moodFromText(context, mood)
                else say(context, Lang.string(context, R.string.tgb_unknown))
            }
        }
    }

    // Accepts the language typed instead of tapped, in either language and with
    // or without a leading slash.
    private fun langFromText(raw: String): String? = when (norm(raw)) {
        "ru", "rus", "russian", "\u0440\u0443\u0441", "\u0440\u0443\u0441\u0441\u043a\u0438\u0439" -> "ru"
        "en", "eng", "english", "\u0430\u043d\u0433\u043b\u0438\u0439\u0441\u043a\u0438\u0439" -> "en"
        else -> null
    }

    // Same effect as tapping a wellbeing button. The date is the one the
    // morning question was about: the day the last recorded night ended.
    private suspend fun moodFromText(context: Context, value: Int) {
        val db = Db.get(context)
        val wake = db.nights().lastSleepEnd() ?: 0L
        val date = if (wake > 0L)
            Instant.ofEpochMilli(wake).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        else dayKey()
        val existing = db.answers().byDate(date)
        val now = System.currentTimeMillis()
        val base = existing ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
        db.answers().put(base.copy(mood = value, at = now))
        say(context, Lang.string(context, R.string.tg_mood_done, moodName(context, value)))
        runCatching { refitSoon(context) }
    }

    // The night was ended by an alarm or by another person. The backup path for
    // the button on the morning question, for a morning where that message was
    // never sent or was lost in the chat.
    //
    // Without this the model would take an interrupted night as proof that
    // sleep pressure had fully discharged, which is the one inference the whole
    // forecast rests on.
    private suspend fun wakeup(context: Context) {
        val db = Db.get(context)
        val wake = db.nights().lastSleepEnd() ?: 0L
        val date = if (wake > 0L)
            Instant.ofEpochMilli(wake).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        else dayKey()
        runCatching { Forced.set(context, date, true) }
        Bot.enqueue(
            context,
            Lang.string(context, R.string.tg_forced_on),
            Telegram.keyboard(
                Telegram.row(Lang.string(context, R.string.tg_forced_undo) to "w:$date:0")
            ),
        )
        runCatching { refitSoon(context) }
    }

    // Below this the leftover pressure is noise, not a debt worth naming.
    private const val DEBT_FLOOR_MIN = 20

    // Repayment is capped per day. Recovery sleep is bounded by physiology,
    // and a plan that promises more than this is a plan that fails on day one.
    private const val REPAY_PER_DAY_MIN = 60

    // The clock itself can only be pulled earlier by so much in a day.
    private const val SHIFT_PER_DAY_MIN = 40

    // Never a plan longer than this. Beyond three days the forecast has moved
    // on and the plan describes a person who no longer exists.
    private const val MAX_PLAN_DAYS = 3

    // Sleep debt and a short way back. Pull only, never sent on its own: a
    // schedule that nags is a schedule that gets muted.
    //
    // Debt here is not a missing number of hours against some norm. It is the
    // pressure that had not reached the floor when the night ended. A night
    // that ended by itself leaves none of it, so most of the time this command
    // answers that there is nothing to repay, and that is the correct answer.
    //
    // Windows only, never a time to be in bed. The body decides the minute.
    private suspend fun corr(context: Context) {
        val f = runCatching { Engine.forecast(context) }.getOrNull()
        if (f == null) {
            say(context, Lang.string(context, R.string.tgb_no_forecast))
            return
        }
        val debt = runCatching { Engine.load(context).debtBandMinutes() }.getOrNull()
        if (debt == null || f.nights < 1) {
            say(context, Lang.string(context, R.string.tgb_corr_unknown))
            return
        }

        val sb = StringBuilder()
        val minutes = Math.round(debt.median).toInt()
        if (minutes < DEBT_FLOOR_MIN) {
            sb.append(Lang.string(context, R.string.tgb_corr_none))
            sb.append("\n")
            sb.append(
                Lang.string(context, R.string.tgb_corr_window, hhmm(f.gate.low), hhmm(f.gate.high))
            )
            sb.append("\n")
            sb.append(Lang.string(context, R.string.tgb_corr_free))
        } else {
            sb.append(Lang.string(context, R.string.tgb_corr_debt, minutes))
            // A spread wider than the estimate itself means the discharge speed
            // is still unknown, so the number is a direction, not a quantity.
            if (debt.high - debt.low > debt.median) {
                sb.append("\n")
                sb.append(Lang.string(context, R.string.tgb_corr_rough))
            }
            var left = minutes
            var days = 0
            while (left > 0 && days < MAX_PLAN_DAYS) {
                days += 1
                left -= REPAY_PER_DAY_MIN
            }
            sb.append("\n\n")
            sb.append(Lang.string(context, R.string.tgb_corr_plan, days))
            var remaining = minutes
            for (day in 1..days) {
                val add = if (remaining > REPAY_PER_DAY_MIN) REPAY_PER_DAY_MIN else remaining
                remaining -= add
                val shift = (SHIFT_PER_DAY_MIN * day).coerceAtMost(minutes) / 60.0
                sb.append("\n")
                sb.append(
                    Lang.string(
                        context,
                        R.string.tgb_corr_day,
                        day,
                        hhmm(f.gate.low - shift),
                        hhmm(f.gate.high - shift),
                        add,
                    )
                )
            }
        }
        sb.append("\n\n")
        sb.append(Lang.string(context, R.string.tgb_corr_light))
        say(context, sb.toString())
    }

    private fun moodName(context: Context, value: Int): String = Lang.string(
        context,
        when (value) {
            1 -> R.string.tg_mood_1
            2 -> R.string.tg_mood_2
            3 -> R.string.tg_mood_3
            4 -> R.string.tg_mood_4
            else -> R.string.tg_mood_5
        }
    )

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
                "why" -> "why"
                "export", "backup" -> "export"
                "wakeup", "woken" -> "wakeup"
                "corr", "debt" -> "corr"
                "status" -> "status"
                "last" -> "last"
                "bed" -> "bed"
                "up" -> "up"
                "coffee" -> "coffee"
                "energy", "can" -> "energy"
                "alcohol", "drink" -> "alcohol"
                else -> null
            }
        }
        val n = norm(raw)

        // Hard coded first, on purpose. Resolving a string resource through a
        // locale wrapped context can quietly fall back to the phone language,
        // and when that happens the keyboard is drawn in one language and
        // matched in another, so every button silently stops working.
        // These labels are the wire protocol; they must not depend on that.
        FIXED[n]?.let { return it }

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

    // Normalised button labels in both languages. Kept next to the resources,
    // never instead of them: a new language still only needs a translation.
    private val FIXED: Map<String, String> = mapOf(
        "forecast" to "forecast",
        "\u043f\u0440\u043e\u0433\u043d\u043e\u0437" to "forecast",
        "status" to "status",
        "\u0441\u0442\u0430\u0442\u0443\u0441" to "status",
        "goingtobed" to "bed",
        "\u043b\u043e\u0436\u0443\u0441\u044c" to "bed",
        "wokeup" to "up",
        "\u0432\u0441\u0442\u0430\u043b" to "up",
        "coffee1" to "coffee",
        "\u043a\u043e\u0444\u04351" to "coffee",
    )

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
        list.add("why" to c.getString(R.string.tgb_cmd_why))
        list.add("status" to c.getString(R.string.tgb_cmd_status))
        list.add("export" to c.getString(R.string.tgb_cmd_export))
        list.add("wakeup" to c.getString(R.string.tgb_cmd_wakeup))
        list.add("corr" to c.getString(R.string.tgb_cmd_corr))
        list.add("last" to c.getString(R.string.tgb_cmd_last))
        list.add("mode" to c.getString(R.string.tgb_cmd_mode))
        list.add("lang" to c.getString(R.string.tgb_cmd_lang))
        if (Prefs.manualMode(context)) {
            list.add("bed" to c.getString(R.string.tgb_cmd_bed))
            list.add("up" to c.getString(R.string.tgb_cmd_up))
            list.add("coffee" to c.getString(R.string.tgb_cmd_coffee))
        }
        // Extra drinks appear in the command list only when they are switched
        // on, in both modes: a counter is useful the moment it is drunk, not
        // only for people using the manual buttons.
        if (Prefs.energyOn(context)) {
            list.add("energy" to c.getString(R.string.tgb_cmd_energy))
        }
        if (Prefs.alcoholOn(context)) {
            list.add("alcohol" to c.getString(R.string.tgb_cmd_alcohol))
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
        text.append("\n")
        text.append(Lang.string(context, R.string.tgb_help_extra))
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

    // A forecast nobody understands is a forecast nobody acts on. This says
    // what the number is built from, in the same order the model builds it.
    private suspend fun why(context: Context) {
        val f = runCatching { Engine.forecast(context) }.getOrNull()
        if (f == null) {
            say(context, Lang.string(context, R.string.tgb_no_forecast))
            return
        }
        // Median over the surviving hypotheses, the same statistic the bands use.
        val latency = runCatching {
            val values = Engine.load(context).particles.map { it.latency }.sorted()
            values[values.size / 2]
        }.getOrDefault(25.0)

        val sb = StringBuilder()
        sb.append(
            Lang.string(context, R.string.tgb_why_head, hhmm(f.gate.median), hhmm(f.onset.median))
        )
        sb.append("\n\n")
        sb.append(Lang.string(context, R.string.tgb_why_latency, Math.round(latency).toInt()))
        sb.append("\n")
        sb.append(Lang.string(context, R.string.tgb_why_wake, hhmm(f.wake.median)))
        sb.append("\n")
        sb.append(
            Lang.string(context, R.string.tgb_why_drift, Math.round(f.driftPerDay * 60.0).toInt())
        )
        sb.append("\n")
        val mg = Math.round(f.caffeineNow).toInt()
        if (mg > 0) sb.append(Lang.string(context, R.string.tgb_why_caffeine, mg))
        else sb.append(Lang.string(context, R.string.tgb_why_caffeine_none))
        sb.append("\n\n")
        sb.append(
            Lang.string(
                context,
                R.string.tgb_why_spread,
                hhmm(f.onset.low),
                hhmm(f.onset.high),
                Math.round(f.onset.width * 60.0).toInt(),
            )
        )
        sb.append("\n")
        val trust = when {
            f.onset.confidence >= 0.66 -> R.string.tgb_why_trust_high
            f.onset.confidence >= 0.33 -> R.string.tgb_why_trust_mid
            else -> R.string.tgb_why_trust_low
        }
        sb.append(Lang.string(context, trust, f.nights))

        // Where the evidence came from. A night spent on the phone past an open
        // gate only bounds the answer from above, and saying so out loud is the
        // difference between a model and a fortune teller.
        val obs = runCatching { Engine.obsStats(context) }.getOrNull()
        if (obs != null && (obs[0] + obs[1] + obs[2]) > 0) {
            sb.append("\n\n")
            sb.append(Lang.string(context, R.string.tgb_why_basis, obs[0], obs[1]))
            if (obs[2] > 0) {
                sb.append("\n")
                sb.append(Lang.string(context, R.string.tgb_why_anchor, obs[2]))
            }
            if (obs.size > 3 && obs[3] > 0) {
                sb.append("\n")
                sb.append(Lang.string(context, R.string.tgb_why_light, obs[3]))
            }
        }
        val beh = runCatching { Behaviour.now(context) }.getOrNull()
        if (beh != null) {
            val gapMin = beh.first
            val nights = beh.second
            sb.append("\n")
            if (nights >= Behaviour.MIN_NIGHTS) {
                sb.append(Lang.string(context, R.string.tgb_why_behaviour, gapMin, nights))
            } else {
                sb.append(Lang.string(context, R.string.tgb_why_behaviour_wait, nights, Behaviour.MIN_NIGHTS))
            }
        }
        say(context, sb.toString())
    }

    // The whole database as one file in the chat. No cable and no computer, and
    // Telegram stores it, which makes this the only off device backup there is.
    private suspend fun export(context: Context) {
        val token = Secrets.token(context)
        val chat = Secrets.chatId(context)
        if (token.isEmpty() || chat.isEmpty()) return

        say(context, Lang.string(context, R.string.tgb_export_wait))
        val text = runCatching { Export.build(context) }.getOrNull()
        if (text == null) {
            say(context, Lang.string(context, R.string.tgb_export_fail))
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reply = Telegram.sendDocument(
            token,
            chat,
            Export.fileName(),
            Lang.string(context, R.string.tgb_export_caption, (bytes.size + 1023) / 1024),
            bytes,
        )
        // Silence on failure would look like the command was ignored.
        if (reply is Telegram.Reply.Fail) {
            say(context, Lang.string(context, R.string.tgb_export_fail))
        }
    }

    // One glance at whether the pipeline is still alive. If the phone kills the
    // app, this is where it shows up first.
    private suspend fun status(context: Context) {
        val db = Db.get(context)
        val nights = runCatching { db.nights().count() }.getOrDefault(0)
        val last = db.meta().get(SyncWorker.KEY_LAST_DATA)?.toLongOrNull()
        val today = db.answers().byDate(dayKey())
        val mugs = today?.mugs ?: 0
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
        // Only what is switched on, and only what was actually answered.
        if (Prefs.energyOn(context)) {
            sb.append("\n")
            sb.append(Lang.string(context, R.string.tgb_st_energy, today?.cans ?: 0))
        }
        if (Prefs.alcoholOn(context)) {
            sb.append("\n")
            sb.append(Lang.string(context, R.string.tgb_st_alcohol, today?.alcohol ?: 0))
        }
        // The same number /corr works from, shown here without the plan, so
        // the debt is visible without having to remember another command.
        val debt = runCatching {
            Math.round(Engine.load(context).debtBandMinutes().median).toInt()
        }.getOrNull()
        if (debt != null && nights > 0) {
            sb.append("\n")
            if (debt < DEBT_FLOOR_MIN) {
                sb.append(Lang.string(context, R.string.tgb_st_debt_none))
            } else {
                sb.append(Lang.string(context, R.string.tgb_st_debt, debt))
            }
        }
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
    //
    // One tap adds one. There is no field for millilitres and none for
    // milligrams: what a mug is worth is set once in the app, and a counter
    // that asks a question every time is a counter that gets abandoned inside
    // a week.
    private suspend fun coffee(context: Context) {
        val db = Db.get(context)
        val date = dayKey()
        val now = System.currentTimeMillis()
        val base = db.answers().byDate(date)
            ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
        val mugs = (base.mugs ?: 0) + 1
        db.answers().put(base.copy(mugs = mugs, at = now))
        refitSoon(context)
        say(context, Lang.string(context, R.string.tgb_coffee_ok, mugs))
    }

    // The same counter for energy drinks. Only reachable when it is switched
    // on in Settings, so nobody who does not drink them ever sees it.
    //
    // It ends up in the same caffeine total as coffee, because caffeine from a
    // can and caffeine from a mug is the same molecule. Only the size of the
    // dose differs, and that is a number set once.
    private suspend fun energy(context: Context) {
        if (!Prefs.energyOn(context)) {
            say(context, Lang.string(context, R.string.tgb_drink_off))
            return
        }
        val db = Db.get(context)
        val date = dayKey()
        val now = System.currentTimeMillis()
        val base = db.answers().byDate(date)
            ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
        val cans = (base.cans ?: 0) + 1
        db.answers().put(base.copy(cans = cans, at = now))
        refitSoon(context)
        say(context, Lang.string(context, R.string.tgb_energy_ok, cans))
    }

    // Standard drinks. One tap is one drink, whatever it was: half a beer, a
    // glass of wine, a shot. The model does not need the strength, it needs to
    // know the difference between a sober night and a night with three.
    private suspend fun alcohol(context: Context) {
        if (!Prefs.alcoholOn(context)) {
            say(context, Lang.string(context, R.string.tgb_drink_off))
            return
        }
        val db = Db.get(context)
        val date = dayKey()
        val now = System.currentTimeMillis()
        val base = db.answers().byDate(date)
            ?: Answer(dateKey = date, mood = null, mugs = null, at = now)
        val doses = (base.alcohol ?: 0) + 1
        db.answers().put(base.copy(alcohol = doses, at = now))
        refitSoon(context)
        say(context, Lang.string(context, R.string.tgb_alcohol_ok, doses))
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
