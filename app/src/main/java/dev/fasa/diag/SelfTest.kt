package dev.fasa.diag

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.fasa.R
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.health.HealthRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume

/**
 * End to end diagnostics. Answers one question: does data from the watch
 * actually reach this app, and is it good enough for the model.
 */
object SelfTest {

    enum class Level { OK, WARN, FAIL }

    data class Line(val level: Level, val text: String)

    data class Report(val lines: List<Line>, val verdict: Line)

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        val out = mutableListOf<Line>()

        // 1. Health Connect availability
        when (HealthRepo.status(context)) {
            HealthRepo.Status.OK ->
                out += Line(Level.OK, context.getString(R.string.st_hc_ok))
            HealthRepo.Status.NEEDS_UPDATE -> {
                out += Line(Level.FAIL, context.getString(R.string.st_hc_update))
                return@withContext finish(context, out)
            }
            HealthRepo.Status.NOT_INSTALLED -> {
                out += Line(Level.FAIL, context.getString(R.string.st_hc_missing))
                return@withContext finish(context, out)
            }
        }

        // 2. Permissions
        val granted = HealthRepo.grantedSet(context)
        fun perm(labelRes: Int, permission: String, required: Boolean) {
            val has = granted.contains(permission)
            val label = context.getString(labelRes)
            out += when {
                has -> Line(Level.OK, "$label \u2014 " + context.getString(R.string.state_granted))
                required -> Line(Level.FAIL, "$label \u2014 " + context.getString(R.string.state_denied))
                else -> Line(
                    Level.WARN,
                    "$label \u2014 " + context.getString(R.string.state_denied) +
                        ", " + context.getString(R.string.st_perm_optional)
                )
            }
        }

        perm(
            R.string.st_perm_sleep,
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            true,
        )
        perm(
            R.string.st_perm_hr,
            HealthPermission.getReadPermission(HeartRateRecord::class),
            true,
        )
        perm(
            R.string.st_perm_spo2,
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            false,
        )
        perm(
            R.string.st_perm_bg,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
            false,
        )
        perm(
            R.string.st_perm_history,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
            false,
        )

        val client = HealthConnectClient.getOrCreate(context)
        val now = Instant.now()
        val week = TimeRangeFilter.between(now.minus(7, ChronoUnit.DAYS), now)
        val day = TimeRangeFilter.between(now.minus(24, ChronoUnit.HOURS), now)
        var newest: Instant? = null

        // 3. Sleep parsing
        val sleep = runCatching {
            client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, timeRangeFilter = week)
            ).records
        }.getOrDefault(emptyList())

        if (sleep.isEmpty()) {
            out += Line(Level.WARN, context.getString(R.string.st_sleep_none))
        } else {
            out += Line(Level.OK, context.getString(R.string.st_sleep_found, sleep.size))
            newest = maxOfNullable(newest, sleep.maxOf { it.endTime })

            val withStages = sleep.count { it.stages.isNotEmpty() }
            out += if (withStages > 0) {
                Line(Level.OK, context.getString(R.string.st_sleep_stages, withStages, sleep.size))
            } else {
                Line(Level.WARN, context.getString(R.string.st_sleep_no_stages))
            }
        }

        // 4. Heart rate parsing and coverage
        val hr = runCatching {
            client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = day)
            ).records
        }.getOrDefault(emptyList())

        val samples = hr.flatMap { it.samples }
        if (samples.isEmpty()) {
            out += Line(Level.WARN, context.getString(R.string.st_hr_none))
        } else {
            out += Line(Level.OK, context.getString(R.string.st_hr_found, samples.size))
            newest = maxOfNullable(newest, samples.maxOf { it.time })

            // Distinct hours touched. Workout-only data clusters into a few hours,
            // continuous background measurement spreads across most of the day.
            val zone = ZoneId.systemDefault()
            val hours = samples
                .map { it.time.atZone(zone).truncatedTo(ChronoUnit.HOURS) }
                .toHashSet()
                .size

            out += if (hours >= 12) {
                Line(Level.OK, context.getString(R.string.st_hr_coverage_ok, hours))
            } else {
                Line(Level.WARN, context.getString(R.string.st_hr_coverage_low, hours))
            }
        }

        // 5. SpO2
        val spo2 = runCatching {
            client.readRecords(
                ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = week)
            ).records
        }.getOrDefault(emptyList())

        if (spo2.isEmpty()) {
            out += Line(Level.WARN, context.getString(R.string.st_spo2_none))
        } else {
            out += Line(Level.OK, context.getString(R.string.st_spo2_found, spo2.size))
            newest = maxOfNullable(newest, spo2.maxOf { it.time })
        }

        // 6. Freshness. Catches a stalled Mi Fitness sync.
        newest?.let {
            val hoursOld = ChronoUnit.HOURS.between(it, now)
            val human = if (hoursOld < 48) "$hoursOld h" else "${hoursOld / 24} d"
            // Same threshold as the stale-data warning in the worker, so the
            // two never disagree about what counts as fresh.
            out += if (hoursOld <= 30) {
                Line(Level.OK, context.getString(R.string.st_freshness, human))
            } else {
                Line(Level.WARN, context.getString(R.string.st_freshness_stale, human))
            }
        }

        // 7. Local database round trip
        runCatching {
            val db = Db.get(context)
            db.meta().put(Meta("selftest", now.toEpochMilli().toString()))
            check(db.meta().get("selftest") != null)
            db.nights().count()
        }.fold(
            onSuccess = { out += Line(Level.OK, context.getString(R.string.st_db_ok, it)) },
            onFailure = {
                out += Line(
                    Level.FAIL,
                    context.getString(R.string.st_db_fail, it.message ?: it.javaClass.simpleName)
                )
            },
        )

        // 8. Light sensor
        val lux = readLuxOnce(context)
        // The ceiling matters as much as the reading. A sensor that cannot
        // report more than a few dozen lux explains a suspiciously low peak
        // better than any amount of guessing about where the phone was lying.
        val range = luxRange(context) ?: 0f
        out += if (lux != null) {
            Line(Level.OK, context.getString(R.string.st_light_ok, lux, range))
        } else {
            Line(Level.WARN, context.getString(R.string.st_light_none))
        }

        // 9. Telegram
        out += if (!dev.fasa.tg.Secrets.configured(context)) {
            Line(Level.WARN, context.getString(R.string.st_tg_none))
        } else {
            when (val me = dev.fasa.tg.Telegram.getMe(dev.fasa.tg.Secrets.token(context))) {
                is dev.fasa.tg.Telegram.Reply.Ok ->
                    Line(Level.OK, context.getString(R.string.st_tg_ok))
                is dev.fasa.tg.Telegram.Reply.Fail ->
                    Line(Level.WARN, context.getString(R.string.st_tg_fail, me.message))
            }
        }

        // 10. Battery optimisation
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        out += if (exempt) {
            Line(Level.OK, context.getString(R.string.st_battery_ok))
        } else {
            Line(Level.WARN, context.getString(R.string.st_battery_bad))
        }

        // 10. Notifications
        val notif = NotificationManagerCompat.from(context).areNotificationsEnabled()
        out += if (notif) {
            Line(Level.OK, context.getString(R.string.st_notif_ok))
        } else {
            Line(Level.WARN, context.getString(R.string.st_notif_bad))
        }

        finish(context, out)
    }

    private fun maxOfNullable(a: Instant?, b: Instant): Instant =
        if (a == null || b.isAfter(a)) b else a

    private fun finish(context: Context, lines: List<Line>): Report {
        val verdict = when {
            lines.any { it.level == Level.FAIL } ->
                Line(Level.FAIL, context.getString(R.string.st_verdict_fail))
            lines.any { it.level == Level.WARN } ->
                Line(Level.WARN, context.getString(R.string.st_verdict_warn))
            else -> Line(Level.OK, context.getString(R.string.st_verdict_ok))
        }
        return Report(lines, verdict)
    }

    /** The largest value this sensor is able to report. */
    private fun luxRange(context: Context): Float? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        return sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.maximumRange
    }

    /** One reading from the ambient light sensor, or null if there is none. */
    private suspend fun readLuxOnce(context: Context): Float? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return null

        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values.firstOrNull())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    @Suppress("unused")
    private val sdk = Build.VERSION.SDK_INT
}
