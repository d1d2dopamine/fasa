package dev.vespian.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.health.HealthRepo
import dev.vespian.model.Engine
import dev.vespian.model.PredLog
import dev.vespian.tg.Bot

/**
 * The hourly heartbeat of the app.
 *
 * Pulls whatever Health Connect has, refits the model when something new
 * arrived, prunes old light samples and complains if the watch has gone quiet.
 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val db = Db.get(context)
        val now = System.currentTimeMillis()

        // If this worker ran at all, the alarm chain may still be broken after
        // a force stop or an update. Re-arming here costs nothing.
        Watchdog.arm(context)
        runCatching { Watchdog.check(context) }

        when (val outcome = HealthRepo.sync(context)) {
            is HealthRepo.Result.Ok -> {
                if (outcome.added > 0) {
                    db.meta().put(Meta(KEY_LAST_DATA, now.toString()))
                    Engine.refit(context)
                }
            }
            is HealthRepo.Result.Blocked -> {
                // Permissions or Health Connect itself. Retrying in an hour is
                // pointless but harmless, and the user may fix it meanwhile.
            }
            is HealthRepo.Result.Failed -> return Result.retry()
        }

        // A rolling window instead of a purge by age.
        //
        // Deleting everything older than ninety days means a batch of history
        // disappears the moment it crosses the line. Capping by row count means
        // one new reading pushes out exactly one old reading: the oldest row is
        // replaced by the newest, the window keeps its length, and nothing ever
        // vanishes in a lump.
        //
        // Anything unreadable goes first, so a broken row cannot occupy a slot
        // that a real reading could have used.
        runCatching { db.hr().dropBroken() }
        runCatching { db.light().dropBroken() }
        db.hr().cap(KEEP_ROWS)
        db.light().cap(KEEP_ROWS)

        // Drinks are a handful of rows a day, so the same ninety day window
        // costs almost nothing. A drink dated in the future can only be a bug
        // or a clock change, and it would sit in the caffeine total forever.
        runCatching { db.sips().dropBroken(now) }
        runCatching { db.sips().cap(KEEP_SIPS) }

        // Naps are a row or two a day at most, same ninety day window.
        runCatching { db.naps().dropBroken(now) }
        runCatching { db.naps().cap(KEEP_NAPS) }

        checkStale(context, now)

        // The same questions Telegram asks, as notifications, for everyone who
        // never set Telegram up. Silent when it is set up, so nothing is asked
        // twice.
        runCatching { Nudge.tick(context) }

        // Telegram last: it needs the model to be up to date before it can send
        // an evening forecast, and a network failure here must not cost us the
        // health data we already imported.
        runCatching { Bot.tick(context) }

        // Write down tonight's promise so the history chart can grade it in
        // the morning. Only in the evening, and only once per night.
        runCatching { recordPrediction(context) }

        // The home screen must never show a number older than the model.
        runCatching { ForecastWidget.refresh(context) }

        return Result.success()
    }

    // A forecast is only worth grading if it was made before the night it
    // describes. Anything computed after sleep began would be a hindsight
    // score, which is worse than no score at all.
    private suspend fun recordPrediction(context: Context) {
        val now = System.currentTimeMillis()
        val hour = Engine.hourOf(now)
        if (hour < EVENING_FROM_H && hour >= EVENING_TO_H) return
        val forecast = Engine.forecast(context)
        PredLog.record(context, forecast, now)
    }

    // A night arrives roughly once a day. Thirty hours of silence means the
    // Mi Fitness bridge stopped, which is exactly the failure we cannot see.
    private suspend fun checkStale(context: Context, now: Long) {
        val db = Db.get(context)
        val lastData = db.meta().get(KEY_LAST_DATA)?.toLongOrNull()
            ?: db.nights().lastSleepEnd()
            ?: return

        val silentFor = now - lastData
        if (silentFor < STALE_AFTER_MS) return

        // At most one complaint per day. Nagging every hour would train the
        // user to swipe the app away entirely.
        val lastWarned = db.meta().get(KEY_LAST_WARN)?.toLongOrNull() ?: 0L
        if (now - lastWarned < DAY_MS) return

        Notify.stale(context, silentFor / HOUR_MS)
        db.meta().put(Meta(KEY_LAST_WARN, now.toString()))
    }

    companion object {
        const val KEY_LAST_DATA = "last_data_at"
        const val KEY_LAST_WARN = "last_stale_warn"

        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val STALE_AFTER_MS = 30 * HOUR_MS

        // Local hours. The window wraps past midnight on purpose.
        private const val EVENING_FROM_H = 18.0
        private const val EVENING_TO_H = 4.0

        /**
         * How many heart rate and light rows to keep.
         *
         * Both tables hold one row every five minutes, so 288 a day. Ninety
         * days of that is the window below, which is exactly as far back as the
         * model ever looks.
         */
        private const val KEEP_ROWS = 90 * 288

        // Ninety days at a generous twenty drinks a day.
        private const val KEEP_SIPS = 90 * 20

        // Ninety days at a generous four daytime sleeps a day.
        private const val KEEP_NAPS = 90 * 4
    }
}
