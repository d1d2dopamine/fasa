package dev.vespian.model

import android.content.Context
import dev.vespian.Prefs
import dev.vespian.db.Db
import dev.vespian.db.Forced
import dev.vespian.db.Meta
import dev.vespian.db.ModelState
import dev.vespian.work.Screen
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

    // How the last fit was fed: measured nights, censored nights, pulse
    // anchors, stretches of light.
    const val KEY_OBS = "obs_stats"

    // A light log shorter than this carries no useful phase information.
    private const val LIGHT_MIN_SAMPLES = 2

    /**
     * Worst case trust in a fitted clock anchor.
     *
     * A perfect fit leaves the anchor at full strength. A fit that explains
     * nothing widens it to four times the usual uncertainty rather than
     * throwing it away, because even a rough curve says more about the body
     * clock than nothing at all.
     */
    private const val ANCHOR_FLOOR = 0.25

    /**
     * Trust in a bare nightly minimum with no curve behind it.
     *
     * This is the old evidence: one reading, easily moved by a single quiet
     * moment. It still counts, at half the strength of a clean fit.
     */
    private const val ANCHOR_RAW_SCALE = 2.0

    // Bumped whenever the maths behind the saved cloud changes.
    //
    // The particle cloud on disk is the answer to the old equations. Loading it
    // after an update would mix two different models and the result would be
    // neither: predictions built half on the old physics and half on the new.
    // A mismatch here throws the cloud away and refits every stored night under
    // the current rules. Nothing measured is lost, only the fitted state, and
    // that is derived data which is cheap to rebuild.
    //
    // 1 point observations
    // 2 censored observations and pulse anchor
    // 3 light phase response curve and data seeded prior
    // 4 fitted daily pulse curve as the anchor, harmonic circadian shape
    // 5 sleep pressure carried between nights, duration scored from the
    //   measured onset
    // 6 morning wellbeing read as evidence about the pressure left at wake up
    // 7 behavioural layer weighs how much each night says about the body clock
    // 8 caffeine strength and clearance are personal parameters instead of
    //   population constants, and alcohol is scored on its own
    const val MODEL_VERSION = 8
    const val KEY_MODEL_VERSION = "model_version"

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
        val answers = withContext(Dispatchers.IO) { db.answers().last(10_000) }
        // Read once: what a mug and a can are worth is set in Settings.
        val mgPerMugOnce = Prefs.mgPerMug(context).toDouble()
        val mgPerCanOnce = Prefs.mgPerCan(context).toDouble()

        // Every caffeinated drink collapses into one number of milligrams,
        // because that is the only thing the body reacts to. How strongly it
        // reacts is no longer assumed here: it is a parameter each hypothesis
        // carries and the filter fits.
        val caffeineByDate = answers.associate {
            it.dateKey to ((it.mugs ?: 0) * mgPerMugOnce + (it.cans ?: 0) * mgPerCanOnce)
        }

        // Alcohol stays a separate count. It works on a different mechanism
        // and mixing it into milligrams of caffeine would be a lie.
        val alcoholByDate = answers.associate { it.dateKey to (it.alcohol ?: 0).toDouble() }

        // The morning wellbeing answer, kept apart from the mugs because it is
        // optional and often missing. Where it exists it is evidence about how
        // much pressure the night failed to discharge.
        val moodByDate = answers.mapNotNull { a -> a.mood?.let { a.dateKey to it } }.toMap()

        // Mornings that were ended by an alarm or by another person. On those
        // nights the body did not decide when to stop, so the length of the
        // night says "at least this much" rather than "exactly this much".
        val forcedKeys = runCatching { Forced.all(context) }.getOrDefault(emptySet())

        // The moments the screen went dark. Without them a late onset cannot be
        // told apart from a late decision to go to bed.
        val screenEvents = withContext(Dispatchers.IO) {
            runCatching { Screen.all(context) }.getOrDefault(emptyList())
        }

        // Everything the light sensor recorded over the span the nights cover.
        // One query, sliced in memory afterwards.
        val lightRows = withContext(Dispatchers.IO) {
            val first = nights.firstOrNull()?.sleepStart ?: (System.currentTimeMillis() - 7L * 86_400_000L)
            runCatching { db.light().between(first - 86_400_000L, System.currentTimeMillis()) }
                .getOrDefault(emptyList())
        }

        // Every heart rate reading of every day, not only the ones inside a
        // sleep session. The daily curve is fitted through these.
        val hrRows = withContext(Dispatchers.IO) {
            val first = nights.firstOrNull()?.sleepStart ?: (System.currentTimeMillis() - 7L * 86_400_000L)
            runCatching { db.hr().between(first - 86_400_000L, System.currentTimeMillis()) }
                .getOrDefault(emptyList())
        }

        // The behavioural layer as it stood before this refit. It is used to
        // weigh the nights of this pass and refitted from them afterwards.
        // Using the fresh fit on the very nights it was built from would let
        // the layer justify itself, which is how a model ends up confidently
        // wrong.
        val behaviourFit = runCatching { Behaviour.load(context) }.getOrNull()
        val gapRows = ArrayList<DoubleArray>()
        val gapValues = ArrayList<Double>()
        var tonightFeatures: DoubleArray? = null
        val zone = ZoneId.systemDefault()

        var obsMeasured = 0
        var obsCensored = 0
        var obsAnchored = 0
        var obsLight = 0

        val filter = withContext(Dispatchers.Default) {
            val nowHour = hourOf(System.currentTimeMillis(), offset)
            val lightHours = lightRows.map { doubleArrayOf(hourOf(it.at, offset), it.lux.toDouble()) }
            val screenHours = screenEvents.map { hourOf(it, offset) }
            // How long the screen was actually lit, not just how often it went
            // dark. Recorded alongside every light sample, and until now never
            // read by anything except the export file.
            val screenLit = lightRows.map {
                doubleArrayOf(hourOf(it.at, offset), it.screenMs / 60000.0)
            }

            // Place the starting cloud on the first real night instead of on a
            // population average. The circadian low is taken from the nightly
            // heart rate minimum when the band recorded one, otherwise from the
            // rule of thumb that it sits about two hours before waking.
            val seedNight = nights.firstOrNull {
                hourOf(it.sleepEnd, offset) - hourOf(it.sleepStart, offset) >= 2.0
            }
            // Readings grouped into calendar days, each day fitted once. A fit
            // is keyed by the day it belongs to so a night can look up the
            // curve that covers it.
            val hrHours = hrRows.map { doubleArrayOf(hourOf(it.at, offset), it.bpm.toDouble()) }
            val fitsByDay = hrHours
                .groupBy { floor(it[0] / 24.0).toInt() }
                .mapNotNull { (day, points) -> Cosinor.fit(points)?.let { day to it } }
                .toMap()

            val seedNadir = seedNight?.let { n ->
                val day = floor(hourOf(n.sleepEnd, offset) / 24.0).toInt()
                fitsByDay[day]?.nadirHour
                    ?: n.hrMinAt?.let { hourOf(it, offset) }
                    ?: (hourOf(n.sleepEnd, offset) - 2.0)
            }

            val f = Filter.prior(nowHour, seedNadir)

            var previousEnd: Double? = null
            var previousDuration: Double? = null
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

                val caffeine = caffeineByDate[night.dateKey] ?: 0.0
                val alcohol = alcoholByDate[night.dateKey] ?: 0.0

                // A hand typed night has no screen event behind it and its sleep
                // start already contains an assumed latency, so it stays a plain
                // point observation.
                val typed = night.source.startsWith("manual")
                val bedAt = if (typed) null else Screen.bedtimeBefore(screenEvents, night.sleepStart)
                val bedHour = bedAt?.let { hourOf(it, offset) }
                // The clock anchor. A curve fitted through the whole day beats
                // the single lowest beat of the night, because one stray
                // reading cannot move it and because the daytime readings tell
                // the fit how far the rhythm actually swings. The raw minimum
                // stays as the fallback for days with too few readings.
                val fit = fitsByDay[floor(end / 24.0).toInt()]
                val hrHour = fit?.nadirHour ?: night.hrMinAt?.let { hourOf(it, offset) }

                // How hard this anchor is allowed to pull. A clean fit pulls at
                // full strength, a poor one is widened, and a bare minimum with
                // no curve behind it is treated as the weakest evidence.
                val nadirScale = when {
                    fit != null -> 1.0 / (ANCHOR_FLOOR + (1.0 - ANCHOR_FLOOR) * fit.quality)
                    else -> ANCHOR_RAW_SCALE
                }

                if (bedHour != null && bedHour <= start) {
                    if ((start - bedHour) * 60.0 < Filter.CENSOR_BELOW_MIN) obsCensored++ else obsMeasured++
                }
                if (hrHour != null) obsAnchored++

                // The light between the last wake up and this sleep is what
                // pushed the clock into this night, so it is applied before the
                // night is scored against the result.
                val lightFrom = previousEnd
                if (lightFrom != null) {
                    val stretch = lightHours.filter { it[0] >= lightFrom && it[0] <= start }
                    if (stretch.size >= LIGHT_MIN_SAMPLES) {
                        f.applyLight(stretch)
                        obsLight++
                    }
                }

                // The behavioural read of this evening. Taken before the night
                // is scored, and the gap is measured against the gate the cloud
                // believed in at that moment, which is exactly the information
                // the layer will have on a live evening. Measuring it against
                // the gate fitted afterwards would be measuring the layer
                // against its own answer.
                val dayOfWeek = Instant.ofEpochMilli(night.sleepStart).atZone(zone).dayOfWeek.value
                val evening = Behaviour.features(
                    start,
                    screenHours,
                    previousDuration,
                    hrHours,
                    weekend = dayOfWeek == 5 || dayOfWeek == 6,
                    screenMinutes = screenLit,
                )
                if (known != null) {
                    val gap = (start - f.gateMedian(wokeAt, caffeine)) * 60.0
                    if (gap >= 0.0 && gap <= Behaviour.MAX_GAP_MIN) {
                        gapRows.add(evening)
                        gapValues.add(gap)
                    }
                }

                f.observe(
                    start, end, wokeAt, caffeine, sigmaScale, bedHour, hrHour, nadirScale,
                    forcedWake = forcedKeys.contains(night.dateKey),
                    mood = moodByDate[night.dateKey],
                    onsetScale = Behaviour.widenFor(behaviourFit, evening),
                    alcoholDoses = alcohol,
                )
                previousEnd = end
                previousDuration = end - start
            }

            // Today counts too. The light since the last wake up has already
            // moved the clock, and tonight's forecast is built on the result.
            val lastEnd = previousEnd
            if (lastEnd != null) {
                val trailing = lightHours.filter { it[0] >= lastEnd && it[0] <= nowHour }
                if (trailing.size >= LIGHT_MIN_SAMPLES) {
                    f.applyLight(trailing)
                    obsLight++
                }

                // No data for a while means the phase kept drifting unobserved.
                val idleDays = (nowHour - lastEnd) / 24.0
                if (idleDays > 1.0) f.advanceDays(idleDays - 1.0)
            }

            // The evening in progress, read with the same code path as every
            // stored night so a live risk and a historical one mean the same
            // thing.
            val today = LocalDate.now(zone).dayOfWeek.value
            tonightFeatures = Behaviour.features(
                nowHour,
                screenHours,
                previousDuration,
                hrHours,
                weekend = today == 5 || today == 6,
                screenMinutes = screenLit,
            )
            f
        }

        // Refit the behavioural layer over every night of this pass, then read
        // the evening in progress through it. Below its minimum the fit returns
        // null, the stored fit is cleared and the layer says nothing at all.
        val newFit = runCatching { Behaviour.fit(gapRows, gapValues) }.getOrNull()
        val tonightGap = tonightFeatures?.let { row -> newFit?.predict(row) }

        val stamp = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            db.model().put(ModelState(id = 1, particles = filter.toJson(), updatedAt = stamp))
            db.meta().put(Meta(KEY_OFFSET, offset.toString()))
            db.meta().put(Meta(KEY_OBS, "$obsMeasured|$obsCensored|$obsAnchored|$obsLight"))
            db.meta().put(Meta(KEY_MODEL_VERSION, MODEL_VERSION.toString()))
        }
        runCatching {
            Behaviour.save(context, newFit)
            Behaviour.saveNow(
                context,
                gapMin = (tonightGap ?: 0.0).toInt(),
                nights = newFit?.nights ?: gapRows.size,
            )
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

        // A cloud fitted by an older version of the maths is not usable. Refit
        // instead of quietly serving predictions from retired equations.
        val storedVersion = withContext(Dispatchers.IO) {
            db.meta().get(KEY_MODEL_VERSION)
        }?.toIntOrNull() ?: 0
        if (storedVersion != MODEL_VERSION) return refit(context)

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
    // Drinks are assumed spread over the morning, centred at 11:00 local.
    //
    // Coffee and energy drinks are added together here, in milligrams, because
    // that is the only form the body reads. The decay to this moment uses the
    // population half life; each hypothesis then re-decays it with its own
    // clearance while it walks the evening forward, which is where the
    // personal figure actually matters.
    private suspend fun caffeineNow(context: Context, offset: Double): Double {
        val db = Db.get(context)
        val today = LocalDate.now().toString()
        val answer = withContext(Dispatchers.IO) { db.answers().byDate(today) } ?: return 0.0
        val mg = (answer.mugs ?: 0) * Prefs.mgPerMug(context).toDouble() +
            (answer.cans ?: 0) * Prefs.mgPerCan(context).toDouble()
        if (mg <= 0.0) return 0.0

        val centre = ZonedDateTime.of(LocalDate.now(), LocalTime.of(11, 0), ZoneId.systemDefault())
        val drankAt = hourOf(centre.toInstant().toEpochMilli(), offset)
        val nowHour = hourOf(System.currentTimeMillis(), offset)
        return Physics.caffeine(mg, nowHour - drankAt)
    }

    // Standard drinks logged today. Kept separate from caffeine on purpose:
    // it shortens the wait for sleep instead of lengthening it, and it costs
    // the night part of its recovery.
    private suspend fun alcoholToday(context: Context): Double {
        val db = Db.get(context)
        val today = LocalDate.now().toString()
        val answer = withContext(Dispatchers.IO) { db.answers().byDate(today) } ?: return 0.0
        return (answer.alcohol ?: 0).toDouble()
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

    // Measured nights, censored nights, pulse anchors and light stretches from
    // the last fit. Older installs stored three fields; the fourth reads zero
    // until the next refit rather than throwing the whole record away.
    suspend fun obsStats(context: Context): IntArray {
        val raw = withContext(Dispatchers.IO) { Db.get(context).meta().get(KEY_OBS) }
            ?: return IntArray(4)
        val parts = raw.split("|")
        if (parts.size < 3) return IntArray(4)
        return IntArray(4) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
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
        val alcohol = alcoholToday(context)
        val alarm = alarmRaw(context) ?: "-"

        // Quarter hour buckets. Nothing in the answer moves faster than that,
        // and it caps a recompute at four per hour instead of one per redraw.
        val bucket = floor(hourOf(System.currentTimeMillis(), offset) * 4.0).toLong()
        val key = "$filterStamp|$nights|$alarm|$bucket|${offset}|" +
            "${(caffeine / 5.0).toInt()}|${alcohol.toInt()}"

        val hit = forecastCache
        if (hit != null && key == forecastKey) return hit

        val result = withContext(Dispatchers.Default) {
            val base = filter.forecast(
                wokeAtHour = wokeAt,
                caffeineMg = caffeine,
                nights = nights,
                targetWake = null,
                alcoholDoses = alcohol,
            )
            val target = targetWakeHour(context, offset, base.onset.median)
            if (target == null) base else base.copy(reverseAlarm = filter.reverseBand(target))
        }

        forecastCache = result
        forecastKey = key
        return result
    }
}
