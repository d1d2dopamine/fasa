package dev.vespian.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// Single entry point for everything that must keep running without the user.
object Scheduler {

    private const val SYNC_WORK = "vespian_sync"

    fun start(context: Context) {
        Notify.ensureChannels(context)
        scheduleSync(context)
        LightService.start(context)
        Watchdog.arm(context)
    }

    private fun scheduleSync(context: Context) {
        // No network constraint on purpose: Health Connect is a local database,
        // the phone does not need to be online to read last night.
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        // KEEP would ignore changes to the request after an app update, so the
        // policy is UPDATE. The existing schedule is reused, not restarted.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
