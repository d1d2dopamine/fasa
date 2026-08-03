package dev.vespian.model

import android.content.Context
import dev.vespian.Prefs
import dev.vespian.db.Db
import dev.vespian.work.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

// Behavioural delay: the gap between "the body is ready to sleep" and "the
// phone was actually put down".
//
// The two process model predicts physiology. It has no opinion about a person
// lying in bed scrolling for three hours, and for this user that gap is the
// whole story. Revenge bedtime procrastination is not noise; it is repeatable,
// so it can be measured and predicted separately.
//
// Keeping it separate matters. Folding the delay into the biology would push
// the estimated circadian phase later than it really is, and then the advice
// about morning light would be wrong too.
object Delay {

    // Enough nights to say anything at all.
    const val MIN_NIGHTS = 3

    // Only the recent past. Habits from two months ago are not this week's.
    private const val WINDOW = 30

    // How many particles to simulate per night. The full cloud is overkill for
    // a summary statistic and this runs on every forecast.
    private const val SAMPLE = 120

    data class Info(
        val nights: Int,
        val median: Double,
        val low: Double,
        val high: Double,
        val weekday: Double?,
        // How many of those nights had a real "phone went dark" moment behind
        // them rather than the band's sleep start.
        val measured: Int = 0,
    ) {
        val known: Boolean get() = nights >= MIN_NIGHTS
    }

    suspend fun estimate(context: Context): Info? = withContext(Dispatchers.Default) {
        val db = Db.get(context)
        val filter = runCatching { Engine.load(context) }.getOrNull() ?: return@withContext null
        val nights = withContext(Dispatchers.IO) { db.nights().all() }
            .filter { it.sleepEnd - it.sleepStart >= 2 * 3600_000L }
            .takeLast(WINDOW + 1)
        if (nights.size < MIN_NIGHTS + 1) return@withContext null

        val mgPerMug = Prefs.mgPerMug(context).toDouble()
        val mgPerCan = Prefs.mgPerCan(context).toDouble()
        // Coffee and energy drinks are one caffeine total, in milligrams.
        val caffeineByDate = withContext(Dispatchers.IO) {
            db.answers().last(10_000).associate {
                it.dateKey to ((it.mugs ?: 0) * mgPerMug + (it.cans ?: 0) * mgPerCan)
            }
        }
        val offset = Engine.offsetHours()
        val zone = ZoneId.systemDefault()
        val step = (filter.particles.size / SAMPLE).coerceAtLeast(1)

        // Screen off events turn this from a guess into a measurement. Without
        // them the only observable is when sleep began, which also contains
        // however long it took to fall asleep.
        val screenEvents = runCatching { Screen.all(context) }.getOrDefault(emptyList())
        var measured = 0

        val deltas = ArrayList<Double>()
        val todayDow = java.time.LocalDate.now().dayOfWeek
        val sameDow = ArrayList<Double>()

        var previousEnd: Double? = null
        for (night in nights) {
            val start = Engine.hourOf(night.sleepStart, offset)
            val end = Engine.hourOf(night.sleepEnd, offset)
            val woke = previousEnd
            previousEnd = end
            if (woke == null) continue

            val caffeine = caffeineByDate[night.dateKey] ?: 0.0

            // Preferred: the last time the screen went dark before this night.
            // That is the decision to stop, and it is compared with the bare
            // gate. Fallback: sleep start compared with gate plus latency, so
            // both branches measure the same thing.
            // A hand typed night never has a screen event behind it, and its
            // sleep start is a guess with an assumed latency inside. Treating
            // it as measured would make the habit look perfectly steady.
            val typed = night.source.startsWith("manual")
            val bed = if (typed) null else Screen.bedtimeBefore(screenEvents, night.sleepStart)
            val real = bed != null
            if (real) measured += 1
            val observed = if (real) Engine.hourOf(bed!!, offset) else start

            val gates = ArrayList<Double>(SAMPLE)
            var i = 0
            while (i < filter.particles.size) {
                val p = filter.particles[i]
                val g = filter.gateFrom(p, woke, Physics.L0, caffeine)
                gates.add(if (real) g else g + p.latency / 60.0)
                i += step
            }
            gates.sort()
            val gate = gates[gates.size / 2]

            // Anything beyond half a day apart is a broken record, not a habit.
            val d = observed - gate
            if (d < -6.0 || d > 12.0) continue
            deltas.add(d)

            val dow = Instant.ofEpochMilli(night.sleepStart).atZone(zone).toLocalDate().dayOfWeek
            if (dow == todayDow) sameDow.add(d)
        }

        if (deltas.size < MIN_NIGHTS) return@withContext null
        val sorted = deltas.sorted()
        fun q(p: Double): Double = sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

        // Day of week only once there is more than one example of it, otherwise
        // a single bad Friday becomes a law.
        val weekday = if (sameDow.size >= 2) sameDow.sorted()[sameDow.size / 2] else null

        Info(
            nights = deltas.size,
            median = q(0.5),
            low = q(0.2),
            high = q(0.8),
            weekday = weekday,
            measured = measured,
        )
    }

    // The predicted real bedtime: physiology plus habit.
    fun applied(info: Info?, gate: Double): Double? {
        if (info == null || !info.known) return null
        val d = info.weekday ?: info.median
        return gate + d
    }
}
