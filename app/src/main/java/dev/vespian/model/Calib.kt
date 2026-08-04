package dev.vespian.model

import android.content.Context

/**
 * Turns the app's own track record into the confidence it prints.
 *
 * A band used to report confidence as a restatement of its own width: narrow
 * meant certain. That is not a measurement of anything. A model can publish a
 * twenty minute window every evening, be wrong half the time, and still call
 * itself ninety percent sure, because nothing in that arithmetic has ever
 * looked at an actual night.
 *
 * This object closes that loop. The app already writes down the window it
 * showed each evening and later compares it with the measured onset, so the
 * honest number is already on disk: how often sleep landed inside the window.
 * Confidence becomes that share, and the width of the window is adjusted until
 * it stops lying.
 *
 * Two rules keep it honest early on:
 *
 * - With few graded nights the measured share is noise. Three nights out of
 *   three is not a hundred percent, it is three nights. So the measurement is
 *   blended towards the model's own estimate, and the blend shifts to the
 *   measurement as nights accumulate.
 * - Below [MIN_GRADED] nights the app says out loud that the number is
 *   provisional instead of quietly presenting a guess as a fact.
 */
object Calib {

    /** Nights looked back at. Older than a month says little about now. */
    const val WINDOW = 30

    /** Below this the percentage is shown as provisional. */
    const val MIN_GRADED = 10

    /**
     * Strength of the model's own estimate, in nights.
     *
     * The blend weight is graded / (graded + PRIOR), so at eight graded nights
     * the measurement and the model count equally, and by thirty the
     * measurement dominates. Nothing about this number is sacred; it only sets
     * how fast the app stops trusting its own theory over the record.
     */
    private const val PRIOR = 8.0

    /**
     * How often the window is supposed to contain the night.
     *
     * A published range is a promise, and this is the promise: roughly four
     * nights in five. Not higher, because a window wide enough to always be
     * right is a window that says nothing useful.
     */
    const val TARGET = 0.80

    /** The width may be stretched or squeezed only inside these bounds. */
    private const val MAX_SCALE = 1.8
    private const val MIN_SCALE = 0.7

    /** Difference from [TARGET] below which the width is left alone. */
    private const val DEAD_ZONE = 0.05

    /** The record, as of now. */
    data class Score(
        /** Nights that had a saved forecast and can therefore be judged. */
        val graded: Int,
        /** Of those, how many contained the measured onset. */
        val hits: Int,
        /** Median distance from the middle of the window, in minutes. */
        val typicalMiss: Int?,
    ) {
        /** Measured share of nights inside the window, or null if none yet. */
        val rate: Double? get() = if (graded <= 0) null else hits.toDouble() / graded

        /** True once there are enough nights to state the share plainly. */
        val measured: Boolean get() = graded >= MIN_GRADED

        /**
         * Confidence for [band]: the record, pulled towards the band's own
         * estimate by however little evidence there is.
         */
        fun confidence(band: Band): Double {
            val r = rate ?: return band.confidence
            val w = graded / (graded + PRIOR)
            return (w * r + (1.0 - w) * band.confidence).coerceIn(0.0, 1.0)
        }

        /**
         * Factor the published width is multiplied by.
         *
         * Missing more often than promised means the windows were too narrow,
         * so they widen. Missing less often means they were needlessly vague,
         * so they tighten. Both are capped, and nothing moves at all until
         * there are enough nights to tell a trend from a coin flip.
         */
        val scale: Double
            get() {
                val r = rate ?: return 1.0
                if (graded < MIN_GRADED) return 1.0
                val gap = TARGET - r
                if (gap > -DEAD_ZONE && gap < DEAD_ZONE) return 1.0
                return if (gap > 0.0) {
                    (1.0 + gap * 1.5).coerceAtMost(MAX_SCALE)
                } else {
                    (1.0 + gap * 0.8).coerceAtLeast(MIN_SCALE)
                }
            }

        /** -1 narrowed, 0 unchanged, 1 widened. For explaining it on screen. */
        val direction: Int
            get() = when {
                scale > 1.0 -> 1
                scale < 1.0 -> -1
                else -> 0
            }
    }

    /** Reads the graded history. Cheap: one meta row and the nights table. */
    suspend fun score(context: Context): Score {
        val rows = runCatching { PredLog.history(context, WINDOW) }.getOrDefault(emptyList())
        val graded = rows.mapNotNull { it.hit }
        return Score(
            graded = graded.size,
            hits = graded.count { it },
            typicalMiss = PredLog.typicalMiss(rows),
        )
    }

    /**
     * Stretches a band around its middle.
     *
     * The middle is the model's single best guess and the record says nothing
     * about whether it is misplaced, only about whether the range around it was
     * the right size. So the middle never moves here.
     */
    private fun stretch(band: Band, scale: Double): Band {
        if (scale == 1.0) return band
        val half = band.width / 2.0 * scale
        return Band(
            median = band.median,
            low = band.median - half,
            high = band.median + half,
        )
    }

    /** The same forecast, with every window sized by the app's track record. */
    fun apply(forecast: Forecast, score: Score): Forecast {
        val s = score.scale
        return forecast.copy(
            gate = stretch(forecast.gate, s),
            onset = stretch(forecast.onset, s),
            wake = stretch(forecast.wake, s),
            reverseAlarm = forecast.reverseAlarm?.let { stretch(it, s) },
            calib = score,
        )
    }
}
