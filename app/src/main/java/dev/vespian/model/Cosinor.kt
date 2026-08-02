package dev.vespian.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fits a daily wave through heart rate readings and reports where its low point
 * falls.
 *
 * Why this exists.
 *
 * The model used to take the body clock anchor from the single lowest beat of
 * the night. That is one reading out of hundreds. If the band happened to catch
 * one unusually quiet moment at four in the morning, the whole anchor moved to
 * four in the morning, and the model believed it. Sparse nights made it worse:
 * with readings every ten minutes the true low point can sit anywhere in a ten
 * minute gap, and on a night the band measured badly the minimum can be an hour
 * off.
 *
 * Fitting a curve uses every reading of the day instead. Each one pulls the
 * curve a little, so no single bad beat can move the answer, and readings taken
 * while awake count too. The low point of the fitted curve is a far steadier
 * estimate of the same thing the nightly minimum was standing in for.
 *
 * The fit also reports how well the curve actually describes the data. A day
 * spent moving around, or a day where the band barely measured, produces a poor
 * fit, and the model is told to trust that day less instead of treating every
 * anchor as equally solid.
 *
 * The method is ordinary least squares against a fixed twenty four hour wave.
 * Because the shape is fixed, the fit is linear in its three unknowns and has a
 * closed form, so there is no iteration and nothing that can fail to converge
 * on a phone.
 */
object Cosinor {

    /** A day needs at least this many readings before a fit means anything. */
    const val MIN_SAMPLES = 12

    /** Readings must span at least this many hours to pin a daily wave down. */
    const val MIN_SPAN_H = 8.0

    /**
     * A heart rate swing smaller than this is noise, not a rhythm. Fitting a
     * phase to a flat line would produce a confident answer built on nothing.
     */
    const val MIN_AMPLITUDE_BPM = 2.0

    private const val PERIOD_H = 24.0

    /**
     * @param nadirHour absolute local hour of the low point of the fitted wave.
     * @param amplitude half the peak to trough swing, in beats per minute.
     * @param quality share of the variation in the readings explained by the
     *   wave, from zero to one. Used to decide how far to trust the anchor.
     * @param samples how many readings went into the fit.
     */
    data class Fit(
        val nadirHour: Double,
        val amplitude: Double,
        val quality: Double,
        val samples: Int,
    )

    /**
     * @param points pairs of absolute local hour and heart rate.
     * @return the fit, or null when the day cannot support one.
     */
    fun fit(points: List<DoubleArray>): Fit? {
        if (points.size < MIN_SAMPLES) return null

        val hours = points.map { it[0] }
        val span = (hours.maxOrNull() ?: return null) - (hours.minOrNull() ?: return null)
        if (span < MIN_SPAN_H) return null

        val w = 2.0 * PI / PERIOD_H

        // Least squares for y = mesor + b * cos(w t) + c * sin(w t).
        // The three normal equations are built directly from the sums below.
        var n = 0.0
        var sy = 0.0
        var sc = 0.0
        var ss = 0.0
        var scc = 0.0
        var sss = 0.0
        var scs = 0.0
        var syc = 0.0
        var sys = 0.0

        for (p in points) {
            val t = p[0]
            val y = p[1]
            val c = cos(w * t)
            val s = sin(w * t)
            n += 1.0
            sy += y
            sc += c
            ss += s
            scc += c * c
            sss += s * s
            scs += c * s
            syc += y * c
            sys += y * s
        }

        // Solve the three by three system by elimination of the mesor.
        val a11 = scc - sc * sc / n
        val a12 = scs - sc * ss / n
        val a22 = sss - ss * ss / n
        val r1 = syc - sc * sy / n
        val r2 = sys - ss * sy / n

        val det = a11 * a22 - a12 * a12
        if (det == 0.0 || !det.isFinite()) return null

        val b = (r1 * a22 - r2 * a12) / det
        val c = (r2 * a11 - r1 * a12) / det
        val mesor = (sy - b * sc - c * ss) / n

        val amplitude = sqrt(b * b + c * c)
        if (!amplitude.isFinite() || amplitude < MIN_AMPLITUDE_BPM) return null

        // Goodness of fit, so a flat or noisy day can be discounted.
        var ssTot = 0.0
        var ssRes = 0.0
        val mean = sy / n
        for (p in points) {
            val t = p[0]
            val y = p[1]
            val pred = mesor + b * cos(w * t) + c * sin(w * t)
            ssTot += (y - mean) * (y - mean)
            ssRes += (y - pred) * (y - pred)
        }
        val quality = if (ssTot <= 0.0) 0.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)

        // Peak of the wave, then half a period later is the trough.
        val peakHour = atan2(c, b) / w
        var nadir = peakHour + PERIOD_H / 2.0

        // Lift onto the same absolute timeline as the readings, choosing the
        // occurrence nearest the middle of the measured stretch.
        val centre = (hours.first() + hours.last()) / 2.0
        nadir += PERIOD_H * Math.round((centre - nadir) / PERIOD_H)

        return Fit(
            nadirHour = nadir,
            amplitude = amplitude,
            quality = quality,
            samples = points.size,
        )
    }
}
