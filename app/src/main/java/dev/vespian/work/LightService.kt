package dev.vespian.work

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
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.vespian.db.Db
import dev.vespian.db.LightSample
import dev.vespian.db.Meta
import dev.vespian.tg.Bot
import dev.vespian.tg.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Records what the eyes could have seen, and what the phone was doing.
 *
 * One row every five minutes. How that row is gathered depends on the screen:
 *
 *  - screen on: the sensor stays registered. The phone is in a hand, turned
 *    towards a face, so this is the only time the reading resembles what the
 *    person is actually looking at.
 *  - screen off: the sensor is registered for a few seconds per window and
 *    released. Keeping it on all night would wake the CPU on every flicker.
 *
 * Every row carries a kind. A dark reading with the screen off is a pocket, not
 * a dark room, and is stored as untrusted so the clock model never scores it. A
 * window where the sensor said nothing at all is written as an explicit gap,
 * because a missing row and a dead app look identical afterwards.
 */
class LightService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var sensors: SensorManager? = null
    private var light: Sensor? = null

    private var windowMax: Float? = null
    private var windowStarted = 0L
    private var listening = false

    // True while the screen is on and the sensor is kept registered.
    private var continuous = false

    // When the screen came on, and how long it has been on inside the window
    // that has not been written yet.
    private var screenOnAt = 0L
    private var screenMs = 0L

    private var lastLux: Float? = null
    private var samples = 0
    private var samplesDay: LocalDate? = null
    private var botStarted = false

    // Screen state. Off is the moment the phone was put down, which is the only
    // signal separating "decided to sleep" from "fell asleep". On starts the
    // continuous sampling and the phone use clock.
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            flushWindow()
            beginWindow()
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    private val closeShortWindow = Runnable {
        if (!continuous) stopSensor()
    }

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannels(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // A wake-up sensor keeps reporting while the phone is dozing. Without
        // it a short window can close with nothing in it.
        light = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT, true)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
        // Screen state is not delivered to manifest receivers, only to a live
        // one, which is exactly what a running service is for.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenState,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == true) screenOnAt = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 kills the process if this does not happen within ten
        // seconds of the start request, so it is the very first thing we do.
        startForeground(Notify.ID_SERVICE, Notify.service(this, lastLux, samples))

        startBotLoop()

        if (light == null) {
            // No sensor in this phone. Nothing to sample, so do not pretend.
            handler.removeCallbacks(tick)
            return START_STICKY
        }

        handler.removeCallbacks(tick)
        beginWindow()
        handler.postDelayed(tick, PERIOD_MS)
        return START_STICKY
    }

    // The bot lives here rather than in the hourly worker.
    //
    // WorkManager cannot run more often than every fifteen minutes, so a button
    // tap used to sit unread for up to an hour. This service is already awake
    // for the light sensor, so the bot rides along at no extra cost.
    //
    // The hourly worker still calls the same code as a safety net for the case
    // where the system has killed this service.
    private fun startBotLoop() {
        if (botStarted) return
        botStarted = true
        val app = applicationContext
        scope.launch {
            runCatching {
                Db.get(app).meta()
                    .put(Meta(K_SINCE, System.currentTimeMillis().toString()))
            }
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
                val began = System.currentTimeMillis()
                val got = runCatching { Bot.tick(app, longPoll = true) }.getOrDefault(false)
                val took = System.currentTimeMillis() - began
                // A healthy held connection either returns something or sits
                // there for tens of seconds. Returning instantly with nothing
                // means the network refused it, so back off instead of
                // spinning on a dead link.
                val dead = !got && took < FAST_TICK_MS
                delay(if (dead) BACKOFF_MS else HOLD_GAP_MS)
            }
        }
    }

    // ---- screen ----------------------------------------------------------

    private fun onScreenOn() {
        screenOnAt = System.currentTimeMillis()
        if (light == null) return
        continuous = true
        handler.removeCallbacks(closeShortWindow)
        // A slower rate than the short window: this one runs for minutes, and
        // the peak over the window is what we keep either way.
        startSensor(SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun onScreenOff() {
        val now = System.currentTimeMillis()
        if (screenOnAt > 0L) screenMs += now - screenOnAt
        screenOnAt = 0L
        continuous = false
        stopSensor()
        val app = applicationContext
        scope.launch { Screen.record(app, now) }
    }

    // ---- sampling --------------------------------------------------------

    private fun startSensor(rate: Int) {
        val manager = sensors ?: return
        val sensor = light ?: return
        if (listening) return
        listening = manager.registerListener(this, sensor, rate)
    }

    private fun stopSensor() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    private fun beginWindow() {
        windowMax = null
        windowStarted = System.currentTimeMillis()
        if (continuous) {
            // Already registered and staying registered.
            startSensor(SensorManager.SENSOR_DELAY_NORMAL)
            return
        }
        // The window is short, so ask for everything the sensor has. The cost
        // is a few seconds of samples, and the gain is a peak that is not an
        // accident of timing.
        startSensor(SensorManager.SENSOR_DELAY_FASTEST)
        if (listening) handler.postDelayed(closeShortWindow, WINDOW_MS)
    }

    private fun flushWindow() {
        if (windowStarted == 0L) return
        val now = System.currentTimeMillis()

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive ?: false

        // Screen time that is still running when the window closes belongs to
        // this window; the clock restarts for the next one.
        var on = screenMs
        if (screenOn && screenOnAt > 0L) {
            on += now - maxOf(screenOnAt, windowStarted)
        }
        screenMs = 0L
        if (screenOn) screenOnAt = now

        val lux = windowMax
        val kind = when {
            lux == null -> LightSample.KIND_GAP
            !screenOn && lux < OCCLUDED_LUX -> LightSample.KIND_OCCLUDED
            else -> LightSample.KIND_OK
        }

        if (lux != null) {
            lastLux = lux
            bumpCounter(now)
            refreshNotification()
        }

        val row = LightSample(
            at = now,
            lux = lux ?: 0f,
            screenOn = screenOn,
            kind = kind,
            screenMs = on.coerceAtLeast(0L),
            brightness = brightness(),
        )
        scope.launch { Db.get(applicationContext).light().put(row) }
    }

    // The user's brightness setting, 0..255. Not the light the screen actually
    // emits, but the only number available without a special permission, and
    // enough to tell a dim night screen from a full blast one.
    private fun brightness(): Int = runCatching {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(-1)

    // The notification says "today", so the counter has to mean today.
    private fun bumpCounter(now: Long) {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        if (samplesDay != day) {
            samplesDay = day
            samples = 0
        }
        samples += 1
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
        handler.removeCallbacks(tick)
        handler.removeCallbacks(closeShortWindow)
        stopSensor()
        runCatching { unregisterReceiver(screenState) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        // Written on every loop pass so the chat can report whether the
        // background half of the app is still running.
        const val K_BEAT = "svc_beat"

        // When the current run of the service began. Together with the beat it
        // answers the only question that matters after a silent night: was the
        // background half alive, and if not, for how long.
        const val K_SINCE = "svc_since"

        // Two idle periods plus slack. Anything older means trouble.
        const val BEAT_STALE_MS = 20 * 60 * 1000L

        // Below this, with the screen off, the sensor is covered rather than
        // the room being dark. Moonlight through a window is about one lux, a
        // dim night lamp is five, so the line sits between them.
        const val OCCLUDED_LUX = 3f

        private const val PERIOD_MS = 5 * 60 * 1000L
        private const val WINDOW_MS = 8 * 1000L

        // Nothing pending: check in on the same rhythm as the sensor.
        private const val IDLE_MS = 5 * 60 * 1000L

        // Between two held connections. Just enough not to spin on an error.
        private const val HOLD_GAP_MS = 1000L

        // A poll that comes back empty faster than this never reached Telegram.
        private const val FAST_TICK_MS = 5_000L

        // How long to wait after a failed poll before trying again.
        private const val BACKOFF_MS = 60_000L

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
