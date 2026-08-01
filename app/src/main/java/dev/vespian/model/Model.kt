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

    fun circadian(hour: Double, phi: Double, tau: Double): Double =
        sin(2.0 * PI * (hour - phi) / tau)

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
