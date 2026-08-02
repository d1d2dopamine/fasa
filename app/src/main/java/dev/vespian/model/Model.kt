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

    const val K_CAF = 0.0008
    const val CAF_HALF_LIFE = 5.0
    const val MG_PER_MUG = 130.0

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
        H0 + AMP * circadian(hour, p.phi, p.tau) + K_CAF * caffeineMg

    fun lowerThreshold(hour: Double, p: Particle): Double =
        L0 + AMP * circadian(hour, p.phi, p.tau)

    fun rise(s: Double, dt: Double, tauRise: Double): Double =
        1.0 - (1.0 - s) * exp(-dt / tauRise)

    fun fall(s: Double, dt: Double, tauFall: Double): Double =
        s * exp(-dt / tauFall)

    fun caffeine(mg: Double, dt: Double): Double =
        if (dt < 0.0) 0.0 else mg * 2.0.pow(-dt / CAF_HALF_LIFE)

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
    // Sleep pressure left over at the last wake up.
    //
    // Resetting this to the floor every morning would assert that every night
    // ended fully rested, which is exactly what does not happen when a night is
    // cut short. Carrying it forward is what makes a short night push the next
    // sleep gate earlier instead of vanishing without trace.
    var sWake: Double = Physics.L0,
)

// A prediction with an honest uncertainty band.
data class Band(
    val median: Double,
    val low: Double,
    val high: Double,
) {
    val width: Double get() = high - low

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
)
