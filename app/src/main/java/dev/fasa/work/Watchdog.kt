package dev.fasa.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fasa.db.Db
import dev.fasa.db.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings the light service back after the system has killed it.
 *
 * ColorOS freezes background processes, foreground services included, and it
 * freezes WorkManager along with them. So the recovery cannot live in either.
 * It lives in an alarm, which the system delivers even in doze.
 *
 * setAndAllowWhileIdle is deliberate. An exact alarm would need a special
 * permission meant for alarm clocks; an inexact one is accurate enough for a
 * fifteen minute check, and it briefly lifts background restrictions when it
 * fires, which is what makes restarting a foreground service legal here.
 */
object Watchdog {

    const val ACTION = "dev.fasa.action.WATCHDOG"

    // When the service was last found dead, and when the user was last told.
    const val K_DOWN_AT = "svc_down_at"
    const val K_DOWN_WARN = "svc_down_warn"

    private const val REQUEST = 4201
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    // Cheap enough to call from anywhere. Re-arming an existing alarm just
    // moves it, it does not stack.
    fun arm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + PERIOD_MS
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
        }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Reads the heartbeat the service writes on every loop pass. A stale one
     * means the process is gone even though nothing reported an error.
     */
    suspend fun check(context: Context) {
        val db = Db.get(context)
        val now = System.currentTimeMillis()
        val beat = db.meta().get(LightService.K_BEAT)?.toLongOrNull()
        val alive = beat != null && now - beat <= LightService.BEAT_STALE_MS
        if (alive) return

        db.meta().put(Meta(K_DOWN_AT, now.toString()))
        LightService.start(context)

        // Telling the user once a day is help. Telling them every fifteen
        // minutes is how an app gets uninstalled.
        val warned = db.meta().get(K_DOWN_WARN)?.toLongOrNull() ?: 0L
        if (now - warned < DAY_MS) return
        val minutes = if (beat == null) 0L else (now - beat) / (60 * 1000L)
        Notify.downtime(context, minutes)
        db.meta().put(Meta(K_DOWN_WARN, now.toString()))
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Re-arm first. If the check below throws, the chain must not break.
        Watchdog.arm(app)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Watchdog.check(app)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
