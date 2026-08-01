package dev.fasa

import android.app.Application
import dev.fasa.work.Notify
import dev.fasa.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        // Language and mode are kept in the database too, so a reinstall does
        // not silently reset the chat back to English and hands free.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Store.restore(this@FasaApp) }
        }
        if (Prefs.onboarded(this)) Scheduler.start(this)
    }
}
