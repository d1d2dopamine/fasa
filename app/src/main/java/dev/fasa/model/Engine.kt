package dev.fasa.model

import android.content.Context
import dev.fasa.db.Db
import dev.fasa.db.Meta
import dev.fasa.db.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.floor

// Glue between the database and the particle filter.
//
// Everything the user sees goes through here. The filter itself knows nothing
// about Room, Health Connect or Android, which keeps it testable and portable.
object Engine {

    const val KEY_ALARM = "alarm_hhmm"

    // Time zone offset the saved particle cloud was built in.
    const val KEY_OFFSET = "model_offset"

    // ---- caches ----------------------------------------------------------
    // The cloud is 2000 particles of JSON and a forecast simulates every one of
    // them minute by minute. Recomputing that on every tab switch burns battery
    // for an answer that cannot have changed. Both caches key off the model
    // timestamp, so a refit invalidates them for free.

    @Volatile private var filterCache: Filter? = null
    @Volatile private var filterStamp: Long = -1L
    @Volatile private var forecastCache: Forecast? = null
    @Volatile private var forecastKey: String = ""

    fun invalidate() {
        filterCache = null
        filterStamp = -1L
        forecastCache = null
        forecastKey = ""
    }

    // ---- time ------------------------------------------------------------

    // Absolute local hours. Continuous across midnight, which the sine needs.
    fun offsetHours(zone: ZoneId = ZoneId.systemDefault()): Double =
        zone.rules.getOffset(Instant.now()).totalSeconds / 3600.0

    fun hourOf(epochMillis: Long, offset: Double = offsetHours()): Double =
        epochMillis / 3_600_000.0 + offset

    fun millisOf(hour: Double, offset: Double = offsetHours()): Long =
        ((hour - offset) * 3_600_000.0).toLong()

    // ---- fitting ---------------------------------------------------------

    // Rebuild the model from scratch over every stored night, then persist it.
    // Cheap enough to run on demand: a few thousand particles times a few
    // hundred nights is milliseconds, and it removes any chance of the saved
    // state drifting out of sync with the data.
    suspend fun refit(context: Context): Filter {
        val db = Db.get(context)
        val offset = offsetHours()

        val nights = withContext(Dispatchers.IO) { db.nights().all() }
        // One query instead of one per night.
        val mugsByDate = withContext(Dispatchers.IO) {
            db.answers().last(10_000).associate { it.dateKey to (it.mugs ?: 0) }
        }

        val filter = withContext(Dispatchers.Default) {
            val nowHour = hourOf(System.currentTimeMillis(), offset)
            val f = Filter.prior(nowHour)

            var previousEnd: Double? = null
            for (night in nights) {
                val start = hourOf(night.sleepStart, offset)
                val end = hourOf(night.sleepEnd, offset)

                // Skip naps. Anything shorter than two hours is not a night, and in
                // DSPS a long daytime sleep still counts, so length decides, not clock.
                if (end - start < 2.0) continue

                // The very first night has no measured previous wake up. Inventing
                // one and then trusting it is how a model talks itself into a wrong
                // phase on day one, so that night gets a much wider likelihood.
                val known = previousEnd
                val wokeAt = known ?: (start - 16.0)
                val sigmaScale = if (known == null) 3.0 else 1.0

                val caffeine = (mugsByDate[night.dateKey] ?: 0) * Physics.MG_PER_MUG
                f.observe(start, end, wokeAt, caffeine, sigmaScale)
                previousEnd = end
            }

            // No data for a while means the phase kept drifting unobserved.
            val lastEnd = previousEnd
            if (lastEnd != null) {
                val idleDays = (nowHour - lastEnd) / 24.0
                if (idleDays > 1.0) f.advanceDays(idleDays - 1.0)
            }
            f
        }

        val stamp = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            db.model().put(ModelState(id = 1, particles = filter.toJson(), updatedAt = stamp))
            db.meta().put(Meta(KEY_OFFSET, offset.toString()))
        }

        filterCache = filter
        filterStamp = stamp
        forecastCache = null
        forecastKey = ""
        return filter
    }

    // Load the saved cloud, or build a prior if there is nothing yet.
    //
    // Phase is stored in absolute local hours, so flying to another time zone or
    // a daylight saving jump would silently move every prediction. The offset
    // the cloud was fitted in is stored next to it; on a mismatch the whole
    // cloud is rebased instead of quietly lying.
    suspend fun load(context: Context): Filter {
        val db = Db.get(context)
        val state = withContext(Dispatchers.IO) { db.model().get() }
        val saved = state?.particles

        val cached = filterCache
        if (cached != null && state != null && state.updatedAt == filterStamp) return cached

        val restored = saved?.let { Filter.fromJson(it) } ?: return refit(context)

        val offset = offsetHours()
        val savedOffset = withContext(Dispatchers.IO) { db.meta().get(KEY_OFFSET) }?.toDoubleOrNull()
        var stamp = state?.updatedAt ?: System.currentTimeMillis()

        if (savedOffset != null && abs(savedOffset - offset) > 0.01) {
            restored.shiftPhase(offset - savedOffset)
            stamp = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                db.model().put(ModelState(id = 1, particles = restored.toJson(), updatedAt = stamp))
                db.meta().put(Meta(KEY_OFFSET, offset.toString()))
            }
            forecastCache = null
            forecastKey = ""
        } else if (savedOffset == null) {
            withContext(Dispatchers.IO) { db.meta().put(Meta(KEY_OFFSET, offset.toString())) }
        }

        filterCache = restored
        filterStamp = stamp
        return restored
    }

    // ---- inputs ----------------------------------------------------------

    // Caffeine still circulating, based on today's answer.
    // Mugs are assumed spread over the morning, centred at 11:00 local.
    private suspend fun caffeineNow(context: Context, offset: Double): Double {
        val db = Db.get(context)
        val today = LocalDate.now().toString()
        val mugs = withContext(Dispatchers.IO) { db.answers().byDate(today)?.mugs } ?: return 0.0
        if (mugs <= 0) return 0.0

        val centre = ZonedDateTime.of(LocalDate.now(), LocalTime.of(11, 0), ZoneId.systemDefault())
        val drankAt = hourOf(centre.toInstant().toEpochMilli(), offset)
        val nowHour = hourOf(System.currentTimeMillis(), offset)
        return Physics.caffeine(mugs * Physics.MG_PER_MUG, nowHour - drankAt)
    }

    // When did the body last wake up. Falls back to a plausible morning so the
    // very first forecast is still a real forecast and not an error message.
    private suspend fun lastWakeHour(context: Context, offset: Double): Double {
        val stored = withContext(Dispatchers.IO) { Db.get(context).nights().lastSleepEnd() }
        if (stored != null) return hourOf(stored, offset)

        val nowHour = hourOf(System.currentTimeMillis(), offset)
        val morning = ZonedDateTime.of(LocalDate.now(), LocalTime.of(10, 0), ZoneId.systemDefault())
        val assumed = hourOf(morning.toInstant().toEpochMilli(), offset)
        return if (assumed <= nowHour) assumed else nowHour - 4.0
    }

    private suspend fun alarmRaw(context: Context): String? =
        withContext(Dispatchers.IO) { Db.get(context).meta().get(KEY_ALARM) }

    // Target wake time for the reverse alarm, stored as "HH:mm".
    //
    // [after] is the moment sleep is actually expected to begin. Anchoring on
    // "the next occurrence from now" is wrong: at 06:50 with a 07:00 alarm it
    // picks 07:00 today and reports a bedtime three hours in the past. The
    // alarm we care about is the one that ends the sleep we are about to start.
    suspend fun targetWakeHour(context: Context, offset: Double, after: Double): Double? {
        val raw = alarmRaw(context) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null

        val today = ZonedDateTime.of(LocalDate.now(), LocalTime.of(h, m), ZoneId.systemDefault())
        var target = hourOf(today.toInstant().toEpochMilli(), offset)
        // At least two hours of sleep after the expected onset, otherwise it is
        // tomorrow's alarm we are planning for.
        while (target < after + 2.0) target += 24.0
        return target
    }

    // ---- forecasting -----------------------------------------------------

    suspend fun forecast(context: Context): Forecast {
        val offset = offsetHours()
        val filter = load(context)
        val db = Db.get(context)
        val nights = withContext(Dispatchers.IO) { db.nights().count() }
        val wokeAt = lastWakeHour(context, offset)
        val caffeine = caffeineNow(context, offset)
        val alarm = alarmRaw(context) ?: "-"

        // Quarter hour buckets. Nothing in the answer moves faster than that,
        // and it caps a recompute at four per hour instead of one per redraw.
        val bucket = floor(hourOf(System.currentTimeMillis(), offset) * 4.0).toLong()
        val key = "$filterStamp|$nights|$alarm|$bucket|${offset}|${(caffeine / 5.0).toInt()}"

        val hit = forecastCache
        if (hit != null && key == forecastKey) return hit

        val result = withContext(Dispatchers.Default) {
            val base = filter.forecast(
                wokeAtHour = wokeAt,
                caffeineMg = caffeine,
                nights = nights,
                targetWake = null,
            )
            val target = targetWakeHour(context, offset, base.onset.median)
            if (target == null) base else base.copy(reverseAlarm = filter.reverseBand(target))
        }

        forecastCache = result
        forecastKey = key
        return result
    }
}
