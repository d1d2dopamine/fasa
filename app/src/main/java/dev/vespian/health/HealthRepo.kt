package dev.vespian.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.db.Night
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

object HealthRepo {

    /** Without these there is nothing to model. */
    val CORE: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    /** Nice to have. Without them the app is limited to 30 days and to foreground reads. */
    val EXTRA: Set<String> = setOf(
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    val ALL: Set<String> = CORE + EXTRA

    /** A night must be at least this long. Shorter sessions are naps. */
    private const val MIN_NIGHT_MINUTES = 120L

    /**
     * How far back to re-read on every sync.
     *
     * Mi Fitness writes nights retroactively, sometimes a day or more late.
     * If the cursor only moved forward, those nights would be lost for good.
     * Re-reading is free: dateKey is the primary key and inserts replace.
     */
    private const val REWIND_DAYS = 5L

    private const val CURSOR_KEY = "hc_cursor"
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    enum class Status { OK, NEEDS_UPDATE, NOT_INSTALLED }

    sealed interface Result {
        data class Ok(val sessions: Int, val added: Int) : Result
        data class Blocked(val reason: Reason) : Result
        data class Failed(val error: String) : Result
    }

    enum class Reason { NO_HEALTH_CONNECT, NEEDS_UPDATE, NO_PERMISSION }

    fun status(context: Context): Status =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Status.OK
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_UPDATE
            else -> Status.NOT_INSTALLED
        }

    fun client(context: Context): HealthConnectClient? =
        if (status(context) == Status.OK) HealthConnectClient.getOrCreate(context) else null

    suspend fun grantedSet(context: Context): Set<String> {
        val c = client(context) ?: return emptySet()
        return runCatching { c.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
    }

    /**
     * Reads sleep sessions plus the heart rate and SpO2 inside each session,
     * and stores one row per night.
     */
    suspend fun sync(context: Context): Result {
        when (status(context)) {
            Status.NOT_INSTALLED -> return Result.Blocked(Reason.NO_HEALTH_CONNECT)
            Status.NEEDS_UPDATE -> return Result.Blocked(Reason.NEEDS_UPDATE)
            Status.OK -> Unit
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = grantedSet(context)
        if (!granted.containsAll(CORE)) return Result.Blocked(Reason.NO_PERMISSION)

        val db = Db.get(context)
        val now = Instant.now()

        val hasHistory = granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        val floor = now.minus(if (hasHistory) 365 else 29, ChronoUnit.DAYS)

        val stored = db.meta().get(CURSOR_KEY)?.toLongOrNull()
        val from = when (stored) {
            null -> floor
            else -> maxOf(Instant.ofEpochMilli(stored).minus(REWIND_DAYS, ChronoUnit.DAYS), floor)
        }

        return try {
            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(from, now),
                )
            ).records

            var added = 0
            for (s in sessions) {
                val minutes = ChronoUnit.MINUTES.between(s.startTime, s.endTime)
                if (minutes < MIN_NIGHT_MINUTES) continue

                var deep = 0L
                var rem = 0L
                var awake = 0L
                var asleep = 0L

                if (s.stages.isEmpty()) {
                    // No stage breakdown: count the whole session as sleep.
                    asleep = minutes
                } else {
                    for (st in s.stages) {
                        val m = ChronoUnit.MINUTES.between(st.startTime, st.endTime)
                        when (st.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> { deep += m; asleep += m }
                            SleepSessionRecord.STAGE_TYPE_REM -> { rem += m; asleep += m }
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> asleep += m
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += m
                        }
                    }
                }

                val window = TimeRangeFilter.between(s.startTime, s.endTime)

                val hrSamples = runCatching {
                    client.readRecords(
                        ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = window)
                    ).records.flatMap { it.samples }
                }.getOrDefault(emptyList())

                // A single stray beat is not a resting heart rate. The low
                // point is the fifth percentile: it survives one bad sample and
                // still equals the true minimum on sparsely measured nights.
                val sorted = hrSamples.sortedBy { it.beatsPerMinute }
                val minSample = sorted.getOrNull(sorted.size / 20)
                val hrMean = hrSamples
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.beatsPerMinute }
                    ?.average()
                    ?.toInt()

                val spo2 = runCatching {
                    client.readRecords(
                        ReadRecordsRequest(OxygenSaturationRecord::class, timeRangeFilter = window)
                    ).records.map { it.percentage.value }
                }.getOrDefault(emptyList())

                db.nights().put(
                    Night(
                        dateKey = dateFmt.format(Date(s.endTime.toEpochMilli())),
                        sleepStart = s.startTime.toEpochMilli(),
                        sleepEnd = s.endTime.toEpochMilli(),
                        minutesAsleep = asleep.toInt(),
                        minutesDeep = deep.toInt(),
                        minutesRem = rem.toInt(),
                        minutesAwake = awake.toInt(),
                        hrMin = minSample?.beatsPerMinute?.toInt(),
                        hrMinAt = minSample?.time?.toEpochMilli(),
                        hrMean = hrMean,
                        spo2Mean = spo2.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                        importedAt = System.currentTimeMillis(),
                    )
                )
                added++
            }

            db.meta().put(Meta(CURSOR_KEY, now.toEpochMilli().toString()))
            Result.Ok(sessions.size, added)
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
