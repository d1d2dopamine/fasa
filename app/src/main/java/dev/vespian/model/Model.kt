package dev.vespian.model

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

// Two-process model of sleep regulation (Borbely).
//
// Process S: homeostatic sleep pressure. Rises while awake, falls while asleep.
// Process C: circadian rhythm, a sine wave with an individual period tau.
// In delayed sleep phase syndrome tau is longer than 24 h, which is exactly
// why the whole schedule slides later every day.
//
// Sleep becomes possible when S crosses the upper threshold H, which is itself
// modulated by C. Waking happens when S falls below the lower threshold L.
//
// All times are absolute local hours: epoch millis converted to hours plus the
// local UTC offset. That keeps the sine continuous across midnight and days.
object Physics {

    const val H0 = 0.60
    const val L0 = 0.17
    const val AMP = 0.12

    // ---- caffeine --------------------------------------------------------
    //
    // These two numbers used to be the whole story of caffeine in this model,
    // applied identically to every person. That is wrong in a way that matters.
    // Clearance of caffeine is governed largely by one liver enzyme whose
    // activity varies several fold between people, so the same mug keeps one
    // person awake for three hours and another for ten. Sensitivity at the
    // receptor varies on top of that.
    //
    // So they are no longer constants of the world. They are the population
    // means of two personal parameters carried by every hypothesis, and the
    // filter moves them the same way it moves the period of the body clock.
    // What stays fixed is the physics: a dose decays exponentially, and the
    // drug raises the threshold in proportion to how much of it is left.
    //
    // There is exactly one gain, shared by every caffeinated drink, because
    // caffeine from a mug and caffeine from a can is the same molecule at the
    // same receptor. Drinks differ in the dose they deliver, and the dose is
    // already counted in milligrams. Fitting a separate gain per drink would
    // split the same evidence across parameters that must be equal.
    const val K_CAF = 0.0008
    const val CAF_HALF_LIFE = 5.0
    const val MG_PER_MUG = 130.0

    // Bounds of the personal caffeine parameters. The gain spans roughly a
    // threefold range either side of the mean; the half life covers the
    // measured human range from a fast metaboliser to a slow one.
    const val CAF_GAIN_MIN = 0.00025
    const val CAF_GAIN_MAX = 0.0025
    const val CAF_GAIN_SD = 0.0004
    const val CAF_HL_MIN = 2.0
    const val CAF_HL_MAX = 10.0
    const val CAF_HL_SD = 1.5

    // ---- alcohol ---------------------------------------------------------
    //
    // Alcohol is not caffeine with a minus sign, so it does not share the
    // gain. It is sedative on the way in and disruptive on the way out: it
    // shortens the time taken to fall asleep, then suppresses REM and
    // fragments the second half of the night, so the same hours in bed
    // discharge less pressure than a sober night would.
    //
    // One personal parameter covers both, because both come from the same
    // dose and there is no way to tell them apart from a wrist band. It is the
    // fraction of the night's recovery lost per standard drink.
    const val K_ALC = 0.10
    const val ALC_MIN = 0.0
    const val ALC_MAX = 0.40
    const val ALC_SD = 0.07

    // A dose cannot cut the time to fall asleep by more than this, whatever
    // the count says. Ten drinks do not produce a negative latency.
    const val ALC_LATENCY_FLOOR = 0.35

    // Doses beyond this are not scored any harder. The curve flattens and the
    // night stops being a measurement of anything.
    const val ALC_MAX_DOSES = 6.0

    // ---- shape of the circadian process ----------------------------------
    //
    // A plain sine is symmetric: it falls as slowly as it rises and its trough
    // is as wide as its peak. The real rhythm is not shaped like that. The
    // classical formulation of this model does not use one sine but a sum of
    // harmonics, which produces a broad flat evening and a narrow deep trough
    // in the early morning.
    //
    // The difference matters in exactly the place this app makes its living.
    // With a plain sine the window where sleep becomes possible opens as a
    // gentle slope, so the predicted onset drifts by a lot whenever the sleep
    // pressure estimate moves a little. With the real shape that slope is
    // steeper, the crossing is sharper, and the same uncertainty in pressure
    // produces a narrower band of predicted times.
    //
    // It also fixes a quieter error. The flat evening means the clock resists
    // sleep for hours rather than easing into it, which is the shape a delayed
    // phase actually has. A sine understates that plateau, so it kept expecting
    // sleep earlier than a delayed body allows.
    //
    // Coefficients are the standard harmonic weights for this process. They are
    // fixed physiology, not personal parameters, so they are not fitted.
    private val HARMONICS = doubleArrayOf(0.97, 0.22, 0.07, 0.03, 0.001)

    // Scale so the peak of the summed wave is one and AMP keeps its meaning.
    private const val HARMONIC_NORM = 1.06

    fun circadian(hour: Double, phi: Double, tau: Double): Double {
        val base = 2.0 * PI * (hour - phi) / tau
        var sum = 0.0
        for (i in HARMONICS.indices) sum += HARMONICS[i] * sin((i + 1) * base)
        return sum / HARMONIC_NORM
    }

    fun upperThreshold(hour: Double, p: Particle, caffeineMg: Double): Double =
        H0 + AMP * circadian(hour, p.phi, p.tau) + p.cafGain * caffeineMg

    fun lowerThreshold(hour: Double, p: Particle): Double =
        L0 + AMP * circadian(hour, p.phi, p.tau)

    fun rise(s: Double, dt: Double, tauRise: Double): Double =
        1.0 - (1.0 - s) * exp(-dt / tauRise)

    fun fall(s: Double, dt: Double, tauFall: Double): Double =
        s * exp(-dt / tauFall)

    fun caffeine(mg: Double, dt: Double, halfLife: Double = CAF_HALF_LIFE): Double =
        if (dt < 0.0) 0.0 else mg * 2.0.pow(-dt / halfLife)

    /**
     * One caffeinated drink, at the hour it happened.
     *
     * @property hour absolute hours on the same scale as everything else in the
     *   model, not an hour of the clock.
     * @property mg how much caffeine it carried.
     * @property slackMinutes how vaguely the hour is known. A drink logged by
     *   tapping the button carries zero; one backdated from memory or guessed
     *   from a habit carries the width of that guess.
     */
    class Dose(
        val hour: Double,
        val mg: Double,
        val slackMinutes: Int = 0,
    )

    /**
     * Caffeine still circulating at [atHour] from a whole day of drinks.
     *
     * The old way of doing this was to add up the day's milligrams and decay
     * the total from one assumed moment. That is wrong in the direction that
     * matters: a cup at eight in the morning and a cup at eight in the evening
     * average out to an afternoon that never happened, and the evening cup,
     * the only one still doing anything at bedtime, gets its effect halved.
     * Doses are decayed one by one instead. Anything dated later than [atHour]
     * contributes nothing, so a partly logged day needs no special case.
     */
    fun caffeineFrom(
        doses: List<Dose>,
        atHour: Double,
        halfLife: Double = CAF_HALF_LIFE,
    ): Double {
        var total = 0.0
        for (d in doses) {
            total += caffeineSpread(d.mg, atHour - d.hour, d.slackMinutes / 60.0, halfLife)
        }
        return total
    }

    /**
     * Caffeine still circulating from a dose whose time is only roughly known.
     *
     * A drink remembered two hours after the fact has no single hour attached
     * to it, and picking one anyway would hand the model a false certainty it
     * cannot see through. Instead the dose is spread evenly across the window
     * it might have happened in and the remaining amount is averaged over that
     * window.
     *
     * The averaging is not cosmetic. Decay is a curve, so the average of the
     * curve is not the curve of the average: a dose that might have been taken
     * anywhere in a three hour window leaves more caffeine on average than one
     * pinned to the middle of that window. Spreading it therefore errs on the
     * side of admitting caffeine might still be present, which is the safe
     * direction for a forecast about falling asleep.
     *
     * @param slack how wrong the timestamp could be, in hours either way. Zero
     *   gives exactly [caffeine].
     */
    fun caffeineSpread(
        mg: Double,
        dt: Double,
        slack: Double,
        halfLife: Double = CAF_HALF_LIFE,
    ): Double {
        if (mg <= 0.0) return 0.0
        if (slack <= 0.0) return caffeine(mg, dt, halfLife)

        // Nine slices across the window. More slices do not change the answer
        // by anything a person could notice, and the cost is paid on every
        // forecast.
        val slices = 9
        var total = 0.0
        for (i in 0 until slices) {
            // Slice centres, evenly spaced from one edge of the window to the
            // other. A later assumed drink means less time to decay, so this
            // walks dt from dt + slack down to dt - slack.
            val offset = slack * (1.0 - 2.0 * (i + 0.5) / slices)
            total += caffeine(mg, dt + offset, halfLife)
        }
        return total / slices
    }

    // How much of one night's recovery this hypothesis thinks was lost to
    // drink. Zero doses cost nothing; the effect saturates rather than growing
    // without limit.
    fun alcoholLoss(p: Particle, doses: Double): Double {
        if (doses <= 0.0) return 0.0
        return (p.alcGain * doses.coerceAtMost(ALC_MAX_DOSES)).coerceIn(0.0, 0.8)
    }

    // ---- light -----------------------------------------------------------
    //
    // Light does not simply "shift the clock" by an amount proportional to how
    // bright it was. The same hour of light advances the clock in the morning
    // and delays it in the evening, and the two directions are nowhere near
    // equal: measured phase response curves give roughly two hours of delay for
    // light near bedtime against about a quarter hour of advance for light on
    // waking. That eight to one asymmetry is the single most important fact for
    // a delayed phase, because it means a bright screen after midnight moves
    // the schedule much further out than a bright window in the morning can
    // pull it back.
    //
    // The curve is expressed against the circadian minimum, which every
    // hypothesis in the filter places at its own hour, so the same light log
    // scores differently for each of them. That is what makes the light data
    // informative instead of decorative.
    //
    // Argument is hours since that hypothesis' circadian minimum.
    // A positive result means a delay, a later clock.
    const val PRC_DELAY_PEAK = 1.0
    const val PRC_ADVANCE_PEAK = 0.125
    const val PRC_DELAY_CENTRE = -3.0
    const val PRC_ADVANCE_CENTRE = 1.5
    const val PRC_DELAY_WIDTH = 2.5
    const val PRC_ADVANCE_WIDTH = 2.0

    // Half saturation of the intensity response, in lux. Ordinary room light
    // already carries most of the effect and daylight is not proportionally
    // stronger, so the response saturates rather than growing without bound.
    const val LUX_HALF = 300.0

    // Hours of phase shift produced by one hour of saturating light at the peak
    // of the curve, before the personal gain of a hypothesis is applied.
    const val K_LIGHT = 2.0

    // Fold a difference of hours into the half open range from minus twelve to
    // plus twelve, so that a light sample is always scored against the nearest
    // circadian minimum rather than one a day away.
    fun wrapHalf(x: Double, period: Double): Double {
        var v = x
        while (v <= -period / 2.0) v += period
        while (v > period / 2.0) v -= period
        return v
    }

    fun prc(hoursFromNadir: Double): Double {
        val x = wrapHalf(hoursFromNadir, 24.0)
        val delay = PRC_DELAY_PEAK *
            exp(-((x - PRC_DELAY_CENTRE).pow(2)) / (2.0 * PRC_DELAY_WIDTH * PRC_DELAY_WIDTH))
        val advance = PRC_ADVANCE_PEAK *
            exp(-((x - PRC_ADVANCE_CENTRE).pow(2)) / (2.0 * PRC_ADVANCE_WIDTH * PRC_ADVANCE_WIDTH))
        return delay - advance
    }

    fun dose(lux: Double): Double = if (lux <= 0.0) 0.0 else lux / (lux + LUX_HALF)
}

// One hypothesis about how this particular body works.
// The filter keeps a few thousand of these. Nights of real data reweight them.
// The spread between survivors is what the confidence percentage measures.
data class Particle(
    var tau: Double,
    var phi: Double,
    var tauRise: Double,
    var tauFall: Double,
    var latency: Double,
    var lightGain: Double,
    var weight: Double,
    /**
     * How far one milligram of circulating caffeine lifts the threshold for
     * this body. [Physics.K_CAF] is the population mean it starts from.
     */
    var cafGain: Double = Physics.K_CAF,
    /**
     * Hours for half of a caffeine dose to clear this body. The human range is
     * wide enough that assuming the average is the single largest avoidable
     * error in an evening forecast for a fast or a slow metaboliser.
     */
    var cafHalfLife: Double = Physics.CAF_HALF_LIFE,
    /**
     * Fraction of a night's recovery this body loses per standard drink.
     */
    var alcGain: Double = Physics.K_ALC,
    /**
     * Sleep pressure left over at the last wake up.
     *
     * Every night used to start from the floor, which quietly assumed that the
     * person woke up fully rested no matter how the night had gone. After a
     * short night that is false: pressure is still high, the gate opens sooner,
     * and a model that resets to the floor reads that early sleep as a shifted
     * body clock instead of as a debt. Carrying the remainder forward keeps the
     * two apart.
     *
     * [Physics.L0] means fully discharged. Anything above it is debt, in the
     * same units the rest of the model already speaks.
     */
    var sWake: Double = Physics.L0,
)

// A prediction with an honest uncertainty band.
data class Band(
    val median: Double,
    val low: Double,
    val high: Double,
) {
    val width: Double get() = high - low

    /**
     * The model's own estimate of how sure it is, from the spread of the
     * surviving hypotheses alone.
     *
     * This is a statement about the model, not about reality: it says the
     * hypotheses agree, not that they are right. Everything shown to the user
     * goes through [Calib], which corrects this against how often the published
     * window actually contained the night.
     */
    val confidence: Double get() = (1.0 - width / MAX_WIDTH).coerceIn(0.0, 1.0)

    companion object {
        const val MAX_WIDTH = 6.0
    }
}

data class Forecast(
    val gate: Band,
    val onset: Band,
    val wake: Band,
    val reverseAlarm: Band?,
    val driftPerDay: Double,
    val nights: Int,
    val caffeineNow: Double,
    /**
     * The app's measured track record, attached once the forecast has been
     * calibrated against it. Null means it has not been through [Calib] yet.
     */
    val calib: Calib.Score? = null,
)
