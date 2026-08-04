package dev.vespian.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.vespian.MainActivity
import dev.vespian.R

// All notification plumbing in one place.
object Notify {

    // The light sampler lives here. Minimum importance: it sits in the shade
    // silently and never makes a sound or a heads-up popup.
    const val CH_SERVICE = "vespian_service"

    // Things that actually need the user: stale data, morning questions.
    const val CH_ALERT = "vespian_alert"

    const val ID_SERVICE = 1
    const val ID_STALE = 2
    const val ID_DOWN = 3

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val service = NotificationChannel(
            CH_SERVICE,
            context.getString(R.string.ch_service),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.ch_service_desc)
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            CH_ALERT,
            context.getString(R.string.ch_alert),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.ch_alert_desc)
        }

        manager.createNotificationChannel(service)
        manager.createNotificationChannel(alert)
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // The permanent shade entry. Text carries the last reading so the user can
    // see at a glance that the sensor is alive.
    fun service(context: Context, lux: Float?, samples: Int, total: Int = samples): Notification {
        // [samples] are the trusted readings of today, [total] every row written.
        // Saying both makes a stalled sampler impossible to confuse with a phone
        // that spent the day in a pocket.
        val text = when {
            total <= 0 && lux == null -> context.getString(R.string.nt_light_waiting)
            lux == null -> context.getString(R.string.nt_light_quiet, samples, total)
            else -> context.getString(R.string.nt_light_value, Math.round(lux), samples, total)
        }
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vespian)
            .setContentTitle(context.getString(R.string.nt_light_title))
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // The system killed the background half of the app. Saying so plainly is
    // better than letting the user conclude the bot is broken.
    fun downtime(context: Context, minutes: Long) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val text = context.getString(R.string.nt_down_text, minutes)
        val notification = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_vespian)
            .setContentTitle(context.getString(R.string.nt_down_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_DOWN, notification)
        } catch (_: SecurityException) {
        }
    }

    fun stale(context: Context, hours: Long) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_vespian)
            .setContentTitle(context.getString(R.string.nt_stale_title))
            .setContentText(context.getString(R.string.nt_stale_text, hours))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.nt_stale_text, hours))
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_STALE, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call. Nothing to do.
        }
    }
}
