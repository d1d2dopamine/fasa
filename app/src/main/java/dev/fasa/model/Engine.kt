package dev.fasa.model

import android.content.Context
import dev.fasa.db.Db
import dev.fasa.db.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Glue between the database and the particle filter.
//
// Everything the user sees goes through here. The filter itself knows nothing
// about Room, Health Connect or Android, which keeps it testable and portable.
object Engine {

    // Absolute local hours. Continuous across midnight, which the sine needs.
    fun offsetHours(zone: ZoneId = ZoneId.systemDefault()): Double =
        zone.rules.getOffset(Instant.now()).totalSeconds / 3600.0

    fun hourOf(epochMillis: Long, offset: Double = offsetHours()): Double =
        epochMillis / 3_600_000.0 + offset

    fun millisOf(hour: Double, offset: Double = offsetHours()): Long =
        ((hour - offset) * 3_600_000.0).toLong()

    // Rebuild the model from scratch over every stored night, then persist it.
    // Cheap enough to run on demand: a few thousand particles times a few
    // hundred nights is milliseconds, and it removes any chance of the saved
    // state drifting out of sync with the data.
    suspend fun refit(context: Context): Filter = withContext(Dispatchers.Default) {
        val db = Db.get(context)
        val offset = offsetHours()
        val nowHour = hourOf(System.currentTimeMillis(), offset)

        val filter = Filter.prior(nowHour)
        val nights = db.nights().all()

        var previousEnd: Double? = null
        for (night in nights) {
            val start = hourOf(night.sleepStart, offset)
            val end = hourOf(night.sleepEnd, offset)

            // Skip naps. Anything shorter than two hours is not a night, and in
            // DSPS a long daytime sleep still counts, so length decides, not clock.
            if (end - start < 2.0) continue

            val wokeAt = previousEnd ?: (start - 16.0)
            val mugs = db.answers().byDate(night.dateKey)?.mugs ?: 0
            val caffeine = mugs * Physics.MG_PER_MUG

            filter.observe(start, end, wokeAt, caffeine)
            previousEnd = end
        }

        // No data for a while means the phase kept drifting unobserved.
        val lastEnd = previousEnd
        if (lastEnd != null) {
            val idleDays = (nowHour - lastEnd) / 24.0
            if (idleDays > 1.0) filter.advanceDays(idleDays - 1.0)
        }

        db.model().put(
            ModelState(
                id = 1,
                particles = filter.toJson(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        filter
    }

    // Load the saved cloud, or build a prior if there is nothing yet.
    suspend fun load(context: Context): Filter = withContext(Dispatchers.Default) {
        val saved = Db.get(context).model().get()?.particles
        val restored = saved?.let { Filter.fromJson(it) }
        restored ?: refit(context)
    }

    // Caffeine still circulating, based on today's answer.
    // Mugs are assumed spread over the morning, centred at 11:00 local.
    private suspend fun caffeineNow(context: Context, offset: Double): Double {
        val db = Db.get(context)
        val today = LocalDate.now().toString()
        val mugs = db.answers().byDate(today)?.mugs ?: return 0.0
        if (mugs <= 0) return 0.0

        val centre = ZonedDateTime.of(LocalDate.now(), LocalTime.of(11, 0), ZoneId.systemDefault())
        val drankAt = hourOf(centre.toInstant().toEpochMilli(), offset)
        val nowHour = hourOf(System.currentTimeMillis(), offset)
        return Physics.caffeine(mugs * Physics.MG_PER_MUG, nowHour - drankAt)
    }

    // When did the body last wake up. Falls back to a plausible morning so the
    // very first forecast is still a real forecast and not an error message.
    private suspend fun lastWakeHour(context: Context, offset: Double): Double {
        val stored = Db.get(context).nights().lastSleepEnd()
        if (stored != null) return hourOf(stored, offset)

        val nowHour = hourOf(System.currentTimeMillis(), offset)
        val morning = ZonedDateTime.of(LocalDate.now(), LocalTime.of(10, 0), ZoneId.systemDefault())
        val assumed = hourOf(morning.toInstant().toEpochMilli(), offset)
        return if (assumed <= nowHour) assumed else nowHour - 4.0
    }

    // Target wake time for the reverse alarm, stored as "HH:mm".
    suspend fun targetWakeHour(context: Context, offset: Double): Double? {
        val raw = Db.get(context).meta().get(KEY_ALARM) ?: return null
        val parts = raw.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null

        val nowHour = hourOf(System.currentTimeMillis(), offset)
        val today = ZonedDateTime.of(LocalDate.now(), LocalTime.of(h, m), ZoneId.systemDefault())
        var target = hourOf(today.toInstant().toEpochMilli(), offset)
        // Always the next occurrence.
        while (target < nowHour) target += 24.0
        return target
    }

    const val KEY_ALARM = "alarm_hhmm"

    suspend fun forecast(context: Context): Forecast = withContext(Dispatchers.Default) {
        val offset = offsetHours()
        val filter = load(context)
        val nights = Db.get(context).nights().count()

        filter.forecast(
            wokeAtHour = lastWakeHour(context, offset),
            caffeineMg = caffeineNow(context, offset),
            nights = nights,
            targetWake = targetWakeHour(context, offset),
        )
    }
}
