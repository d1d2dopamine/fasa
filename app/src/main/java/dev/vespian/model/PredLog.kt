package dev.vespian.model

import android.content.Context
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.db.Night
import dev.vespian.tg.Commands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * A written record of what the model promised, so the promise can be checked.
 *
 * Nothing here changes the model. It only stores the onset band that was on
 * screen in the evening and, later, lets the history chart put the measured
 * night next to it. Without this file a forecast can never be wrong out loud,
 * which is the same as never being right.
 *
 * The log lives in `meta` as one JSON array. No schema migration, no new
 * table, nothing that can fail on a device that is still catching up.
 */
object PredLog {

    const val KEY = "pred_log"

    /** Beyond this the chart cannot show them anyway. */
    private const val KEEP = 60

    /** Prediction of one evening for the night that follows it. */
    data class Entry(
        val dateKey: String,
        val at: Long,
        val low: Double,
        val median: Double,
        val high: Double,
    )

    /** One row of the history chart: what was promised, what happened. */
    data class Row(
        val dateKey: String,
        val night: Night,
        val pred: Entry?,
    ) {
        /** Measured onset in local hours, on the same axis as the bands. */
        val actual: Double get() = Engine.hourOf(night.sleepStart)

        /** True when the measured onset fell inside the promised band. */
        val hit: Boolean?
            get() {
                val p = pred ?: return null
                return actual >= p.low && actual <= p.high
            }

        /** Signed miss in minutes against the middle of the band. */
        val errorMinutes: Int?
            get() {
                val p = pred ?: return null
                return Math.round((actual - p.median) * 60.0).toInt()
            }
    }

    private fun parse(raw: String?): MutableList<Entry> {
        val out = mutableListOf<Entry>()
        if (raw.isNullOrBlank()) return out
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = o.optString("d", "")
            if (d.isEmpty()) continue
            out.add(
                Entry(
                    dateKey = d,
                    at = o.optLong("t", 0L),
                    low = o.optDouble("lo", 0.0),
                    median = o.optDouble("me", 0.0),
                    high = o.optDouble("hi", 0.0),
                )
            )
        }
        return out
    }

    private fun encode(list: List<Entry>): String {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("d", e.dateKey)
                    .put("t", e.at)
                    .put("lo", e.low)
                    .put("me", e.median)
                    .put("hi", e.high)
            )
        }
        return arr.toString()
    }

    suspend fun all(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        parse(Db.get(context).meta().get(KEY))
    }

    /**
     * Stores tonight's onset band, once per night.
     *
     * The first write of a night wins. A later one would be made with more
     * evidence than the user ever saw, and grading a forecast against a
     * version of itself that was never shown is cheating.
     */
    suspend fun record(context: Context, forecast: Forecast, now: Long) {
        val dateKey = Commands.dayKey(now)
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val list = parse(db.meta().get(KEY))
            if (list.any { it.dateKey == dateKey }) return@withContext
            list.add(
                Entry(
                    dateKey = dateKey,
                    at = now,
                    low = forecast.onset.low,
                    median = forecast.onset.median,
                    high = forecast.onset.high,
                )
            )
            list.sortBy { it.dateKey }
            while (list.size > KEEP) list.removeAt(0)
            db.meta().put(Meta(KEY, encode(list)))
        }
    }

    /**
     * The last [n] measured nights, newest last, each paired with the
     * prediction that was written before it if there was one.
     */
    suspend fun history(context: Context, n: Int): List<Row> = withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val nights = db.nights().last(n).sortedBy { it.sleepStart }
        val preds = parse(db.meta().get(KEY)).associateBy { it.dateKey }
        nights.map { night ->
            val key = Commands.dayKey(night.sleepStart)
            Row(dateKey = key, night = night, pred = preds[key])
        }
    }

    /** Share of graded nights that landed inside the band, or null if none. */
    fun accuracy(rows: List<Row>): Double? {
        val graded = rows.mapNotNull { it.hit }
        if (graded.isEmpty()) return null
        return graded.count { it }.toDouble() / graded.size
    }

    /** Typical size of the miss in minutes, or null if nothing is graded. */
    fun typicalMiss(rows: List<Row>): Int? {
        val errs = rows.mapNotNull { it.errorMinutes }.map { abs(it) }.sorted()
        if (errs.isEmpty()) return null
        return errs[errs.size / 2]
    }
}
