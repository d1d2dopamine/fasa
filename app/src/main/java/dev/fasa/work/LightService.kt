package dev.fasa.work

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.fasa.db.Db
import dev.fasa.db.LightSample
import dev.fasa.db.Meta
import dev.fasa.tg.Bot
import dev.fasa.tg.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples the ambient light sensor every five minutes.
 *
 * The sensor is only registered for a few seconds per window, then released.
 * Keeping it registered all the time would wake the CPU on every lux change,
 * which on a bright day is hundreds of times a minute.
 */
class LightService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var sensors: SensorManager? = null
    private var light: Sensor? = null

    private var windowMax: Float? = null
    private var listening = false
    private var lastLux: Float? = null
    private var samples = 0
    private var botStarted = false

    // The moment the phone was put down. Cheap to observe, and the only signal
    // that separates "decided to sleep" from "fell asleep".
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            val app = applicationContext
            scope.launch { Screen.record(app) }
        }
    }

    private val openWindow = object : Runnable {
        override fun run() {
            startWindow()
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    private val closeWindow = Runnable { finishWindow() }

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        light = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
        // Screen state is not delivered to manifest receivers, only to a live
        // one, which is exactly what a running service is for.
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 kills the process if this does not happen within ten
        // seconds of the start request, so it is the very first thing we do.
        startForeground(Notify.ID_SERVICE, Notify.service(this, lastLux, samples))

        startBotLoop()

        if (light == null) {
            // No sensor in this phone. Nothing to sample, so do not pretend.
            handler.removeCallbacks(openWindow)
            return START_STICKY
        }

        handler.removeCallbacks(openWindow)
        handler.post(openWindow)
        return START_STICKY
    }

    // The bot lives here rather than in the hourly worker.
    //
    // WorkManager cannot run more often than every fifteen minutes, so a button
    // tap used to sit unread for up to an hour. This service is already awake
    // for the light sensor, so the bot rides along at no extra cost:
    //
    //  - idle: one tiny request every five minutes, same cadence as the sensor
    //  - question on screen: a held connection, answered within a second
    //
    // The hourly worker still calls the same code as a safety net for the case
    // where the system has killed this service.
    private fun startBotLoop() {
        if (botStarted) return
        botStarted = true
        val app = applicationContext
        scope.launch {
            while (isActive) {
                // Proof of life. Without it nothing can tell a healthy phone
                // from one where the system quietly killed this service.
                runCatching {
                    Db.get(app).meta()
                        .put(Meta(K_BEAT, System.currentTimeMillis().toString()))
                }
                if (!Secrets.configured(app)) {
                    delay(IDLE_MS)
                    continue
                }
                val waiting = runCatching { Bot.waiting(app) }.getOrDefault(false)
                // A held connection returns the instant a button is tapped, so
                // this call itself is the wait.
                runCatching { Bot.tick(app, longPoll = waiting) }
                delay(if (waiting) HOLD_GAP_MS else IDLE_MS)
            }
        }
    }

    private fun startWindow() {
        val manager = sensors ?: return
        val sensor = light ?: return
        if (listening) return

        windowMax = null
        listening = manager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
        )
        if (listening) handler.postDelayed(closeWindow, WINDOW_MS)
    }

    private fun finishWindow() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false

        val lux = windowMax ?: return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive ?: false

        lastLux = lux
        samples += 1
        refreshNotification()

        scope.launch {
            Db.get(applicationContext).light().put(
                LightSample(at = System.currentTimeMillis(), lux = lux, screenOn = screenOn)
            )
        }
    }

    private fun refreshNotification() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(this)
                .notify(Notify.ID_SERVICE, Notify.service(this, lastLux, samples))
        } catch (_: SecurityException) {
        }
    }

    // The maximum over the window, not the average. A phone face down on a
    // table reads zero even at noon; the peak is the honest estimate of how
    // much light the eyes could have seen.
    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        val current = windowMax
        if (current == null || value > current) windowMax = value
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(openWindow)
        handler.removeCallbacks(closeWindow)
        if (listening) sensors?.unregisterListener(this)
        runCatching { unregisterReceiver(screenOff) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Written on every loop pass so the chat can report whether the
        // background half of the app is still running.
        const val K_BEAT = "svc_beat"

        // Two idle periods plus slack. Anything older means trouble.
        const val BEAT_STALE_MS = 20 * 60 * 1000L

        private const val PERIOD_MS = 5 * 60 * 1000L
        private const val WINDOW_MS = 8 * 1000L

        // Nothing pending: check in on the same rhythm as the sensor.
        private const val IDLE_MS = 5 * 60 * 1000L

        // Between two held connections. Just enough not to spin on an error.
        private const val HOLD_GAP_MS = 1000L

        fun available(context: Context): Boolean {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            return manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
        }

        fun start(context: Context) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val intent = Intent(context, LightService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // Background start restrictions. The next app launch retries.
            }
        }
    }
}
