package dev.vespian.model

import android.content.Context
import dev.vespian.db.Db
import dev.vespian.db.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

// The behavioural layer.
//
// The particle filter answers a physiological question: when does the body
// become able to sleep. It says nothing about the distance between that moment
// and the moment the phone is finally put down. In a person with a delayed
// sleep phase that distance is often the larger half of the night.
//
// This object models only that distance, called the gap here, and it is kept
// deliberately apart from the filter. Mixing the two is what makes a body clock
// estimate drift every time somebody stays up late by choice.
//
// What the layer can and cannot do was measured before it was written, by
// simulating the effect sizes reported in the literature on bedtime
// procrastination. Two results shaped the design:
//
//   1. It cannot narrow the window. Even generous effect sizes shrink the
//      spread by about a tenth, which is invisible in practice. So the layer
//      never touches the forecast bands.
//   2. It can rank evenings. Flagging the riskiest third of nights catches
//      noticeably more real derailments than chance. So the output is a risk
//      number, never a time.
//
// Under thirty nights the same simulation showed the fit doing actual harm:
// it latches onto coincidences and predicts worse than the personal average.
// Below MIN_NIGHTS this object therefore returns nothing at all.
object Behaviour {

    // Stored fit, and the risk computed for the current evening.
    const val KEY_FIT = "behaviour_fit"
    const val KEY_NOW = "behaviour_now"

    // Below this the layer stays silent. Not a style choice: see above.
    const val MIN_NIGHTS = 30

    // Five now. The fifth is evening screen time, added once it was clear that
    // "how many times the phone went dark" and "how long it stayed lit" are not
    // the same thing: a single unbroken three hour session fragments once and
    // looks like a calm evening.
    const val N_FEATURES = 5

    // How far back an evening is read, in hours before sleep began.
    const val EVENING_H = 3.0

    // Ridge penalty. With four features and a few dozen nights the fit is at
    // constant risk of explaining noise, and a plain least squares solution
    // does exactly that.
    private const val RIDGE = 3.0

    // A gap longer than this is not procrastination any more, it is a missed
    // night or a broken record, and it would dominate the fit.
    const val MAX_GAP_MIN = 360.0

    // How far a flagged evening may widen the onset observation inside the
    // filter. One means no widening.
    const val MAX_WIDEN = 3.0

    // Gap in minutes that counts as a real derailment when describing the risk.
    const val BLOWOUT_MIN = 90.0

    /**
     * A fitted layer.
     *
     * [mean] and [sd] standardise the raw features, so the weights are
     * comparable and the ridge penalty means the same thing for each of them.
     * [spread] is the residual scatter in minutes, which is the honest measure
     * of how much the layer actually knows.
     */
    class Fit(
        val bias: Double,
        val weights: DoubleArray,
        val mean: DoubleArray,
        val sd: DoubleArray,
        val nights: Int,
        val spread: Double,
    ) {
        fun predict(raw: DoubleArray): Double {
            var acc = bias
            for (i in weights.indices) {
                val s = if (sd[i] > 1e-9) sd[i] else 1.0
                acc += weights[i] * ((raw[i] - mean[i]) / s)
            }
            return acc.coerceIn(0.0, MAX_GAP_MIN)
        }

        // How unusual this evening is compared with an ordinary one, in units
        // of the layer's own scatter. Zero means typical.
        fun risk(raw: DoubleArray): Double {
            if (spread < 1e-9) return 0.0
            return (predict(raw) - bias) / spread
        }
    }

    // ---- features --------------------------------------------------------

    /**
     * The four numbers that describe one evening.
     *
     * Chosen because every one of them is already recorded and needs no new
     * permission. Fragmentation comes first because in the literature it is the
     * pattern of use, not the amount, that separates people who postpone sleep.
     *
     * @param onsetHour absolute local hour sleep began, or now for a forecast
     * @param screenHours absolute local hours of every screen off event
     * @param prevDurationH length of the previous night, hours, null if unknown
     * @param hrHours pairs of absolute local hour and beats per minute
     * @param weekend true when the evening leads into a day off
     * @param screenMinutes pairs of absolute local hour and minutes the screen
     *        was on during the five minute window ending at that hour
     */
    fun features(
        onsetHour: Double,
        screenHours: List<Double>,
        prevDurationH: Double?,
        hrHours: List<DoubleArray>,
        weekend: Boolean,
        screenMinutes: List<DoubleArray> = emptyList(),
    ): DoubleArray {
        val from = onsetHour - EVENING_H

        // 1. Fragmentation: how many times the screen went dark in the evening.
        var fragments = 0
        for (h in screenHours) if (h in from..onsetHour) fragments++

        // 2. How short the previous night was. Positive means shorter than a
        // long night, which is when self control is thinnest the next evening.
        val shortfall = if (prevDurationH == null) 0.0 else (8.0 - prevDurationH).coerceIn(-4.0, 6.0)

        // 3. Spread of the evening pulse. A stand in for the vagal measure used
        // in the literature, which this band does not report.
        val beats = ArrayList<Double>()
        for (row in hrHours) if (row[0] >= from && row[0] <= onsetHour) beats.add(row[1])
        val spread = if (beats.size < 4) 0.0 else sd(beats)

        // 4. Minutes of screen on in the same three hours. Capped at the length
        //    of the window itself, because a longer number can only be a
        //    bookkeeping error and would drag the whole fit with it.
        var lit = 0.0
        for (row in screenMinutes) if (row[0] >= from && row[0] <= onsetHour) lit += row[1]
        lit = lit.coerceIn(0.0, EVENING_H * 60.0)

        return doubleArrayOf(
            fragments.toDouble(),
            shortfall,
            spread,
            if (weekend) 1.0 else 0.0,
            lit,
        )
    }

    private fun sd(values: List<Double>): Double {
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var acc = 0.0
        for (v in values) acc += (v - mean) * (v - mean)
        return sqrt(acc / values.size)
    }

    // ---- fitting ---------------------------------------------------------

    /**
     * Ridge regression of the gap on the evening features.
     *
     * Solved by Gaussian elimination on a five by five system, which is small
     * enough that a matrix library would be more code than the solver.
     */
    fun fit(rows: List<DoubleArray>, gaps: List<Double>): Fit? {
        if (rows.size < MIN_NIGHTS || rows.size != gaps.size) return null

        val n = rows.size
        val mean = DoubleArray(N_FEATURES)
        val sd = DoubleArray(N_FEATURES)
        for (i in 0 until N_FEATURES) {
            var sum = 0.0
            for (r in rows) sum += r[i]
            mean[i] = sum / n
            var acc = 0.0
            for (r in rows) acc += (r[i] - mean[i]) * (r[i] - mean[i])
            sd[i] = sqrt(acc / n)
        }

        // Standardised design matrix with an intercept column in front.
        val cols = N_FEATURES + 1
        val x = Array(n) { row ->
            DoubleArray(cols) { c ->
                if (c == 0) {
                    1.0
                } else {
                    val i = c - 1
                    val s = if (sd[i] > 1e-9) sd[i] else 1.0
                    (rows[row][i] - mean[i]) / s
                }
            }
        }

        val a = Array(cols) { DoubleArray(cols + 1) }
        for (r in 0 until cols) {
            for (c in 0 until cols) {
                var acc = 0.0
                for (k in 0 until n) acc += x[k][r] * x[k][c]
                if (r == c && r > 0) acc += RIDGE
                a[r][c] = acc
            }
            var acc = 0.0
            for (k in 0 until n) acc += x[k][r] * gaps[k]
            a[r][cols] = acc
        }

        val beta = solve(a, cols) ?: return null

        var resid = 0.0
        for (k in 0 until n) {
            var pred = 0.0
            for (c in 0 until cols) pred += beta[c] * x[k][c]
            val e = gaps[k] - pred
            resid += e * e
        }
        val spread = sqrt(resid / n)

        val weights = DoubleArray(N_FEATURES) { beta[it + 1] }
        return Fit(beta[0], weights, mean, sd, n, spread)
    }

    // Gaussian elimination with partial pivoting on an augmented matrix.
    private fun solve(a: Array<DoubleArray>, size: Int): DoubleArray? {
        for (col in 0 until size) {
            var pivot = col
            for (r in col until size) {
                if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            }
            if (kotlin.math.abs(a[pivot][col]) < 1e-9) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            for (r in 0 until size) {
                if (r == col) continue
                val factor = a[r][col] / a[col][col]
                if (factor == 0.0) continue
                for (c in col..size) a[r][c] -= factor * a[col][c]
            }
        }
        // Full elimination leaves the matrix diagonal, so each unknown is one
        // division away.
        return DoubleArray(size) { i -> a[i][size] / a[i][i] }
    }

    // ---- storage ---------------------------------------------------------

    fun toJson(fit: Fit): String {
        val o = JSONObject()
        o.put("bias", fit.bias)
        o.put("nights", fit.nights)
        o.put("spread", fit.spread)
        o.put("w", JSONArray().also { arr -> fit.weights.forEach { arr.put(it) } })
        o.put("m", JSONArray().also { arr -> fit.mean.forEach { arr.put(it) } })
        o.put("s", JSONArray().also { arr -> fit.sd.forEach { arr.put(it) } })
        return o.toString()
    }

    fun fromJson(raw: String?): Fit? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val w = o.getJSONArray("w")
            val m = o.getJSONArray("m")
            val s = o.getJSONArray("s")
            if (w.length() != N_FEATURES) return null
            Fit(
                bias = o.getDouble("bias"),
                weights = DoubleArray(N_FEATURES) { w.getDouble(it) },
                mean = DoubleArray(N_FEATURES) { m.getDouble(it) },
                sd = DoubleArray(N_FEATURES) { s.getDouble(it) },
                nights = o.getInt("nights"),
                spread = o.getDouble("spread"),
            )
        }.getOrNull()
    }

    suspend fun load(context: Context): Fit? = withContext(Dispatchers.IO) {
        fromJson(Db.get(context).meta().get(KEY_FIT))
    }

    suspend fun save(context: Context, fit: Fit?) = withContext(Dispatchers.IO) {
        Db.get(context).meta().put(Meta(KEY_FIT, if (fit == null) "" else toJson(fit)))
    }

    /**
     * The risk read for the evening in progress: predicted gap in minutes, and
     * the number of nights behind it. Null while the layer is still silent.
     */
    suspend fun now(context: Context): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val raw = Db.get(context).meta().get(KEY_NOW) ?: return@withContext null
        val parts = raw.split("|")
        if (parts.size < 2) return@withContext null
        val gap = parts[0].toIntOrNull() ?: return@withContext null
        val nights = parts[1].toIntOrNull() ?: return@withContext null
        if (nights < MIN_NIGHTS) return@withContext null
        gap to nights
    }

    suspend fun saveNow(context: Context, gapMin: Int, nights: Int) = withContext(Dispatchers.IO) {
        Db.get(context).meta().put(Meta(KEY_NOW, "$gapMin|$nights"))
    }

    /**
     * How much wider an onset observation should be on an evening the layer
     * flags. A predicted gap of an hour beyond the usual doubles the sigma, so
     * a night spent scrolling moves the body clock estimate far less than a
     * night the body itself delayed.
     */
    fun widenFor(fit: Fit?, raw: DoubleArray?): Double {
        if (fit == null || raw == null) return 1.0
        val excess = fit.predict(raw) - fit.bias
        if (excess <= 0.0) return 1.0
        return (1.0 + excess / 60.0).coerceAtMost(MAX_WIDEN)
    }
}
