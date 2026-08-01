package dev.fasa

import android.app.Application
import dev.fasa.work.Notify
import dev.fasa.work.Scheduler

/**
 * Starts the background machinery on every cold start.
 *
 * Scheduling is idempotent: the periodic work is unique by name and the
 * foreground service ignores a start request when it is already running.
 */
class FasaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        if (Prefs.onboarded(this)) Scheduler.start(this)
    }
}
