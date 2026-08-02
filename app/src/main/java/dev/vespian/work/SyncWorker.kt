package dev.vespian.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.health.HealthRepo
import dev.vespian.model.Engine
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

        // Light samples older than ninety days cannot influence today's phase.
        db.light().prune(now - PRUNE_AFTER_MS)
        db.hr().prune(now - PRUNE_AFTER_MS)

        checkStale(context, now)

        // Telegram last: it needs the model to be up to date before it can send
        // an evening forecast, and a network failure here must not cost us the
        // health data we already imported.
        runCatching { Bot.tick(context) }

        return Result.success()
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
        private const val PRUNE_AFTER_MS = 90 * DAY_MS
    }
}
