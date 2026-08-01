package dev.vespian.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android forgets foreground services across a reboot. WorkManager survives on
 * its own, but starting it here is cheap and covers the case where the app was
 * force stopped and then the phone restarted.
 *
 * BOOT_COMPLETED is one of the few exemptions that may still start a foreground
 * service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Scheduler.start(context.applicationContext)
    }
}
