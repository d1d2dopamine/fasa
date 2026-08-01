package dev.fasa.work

import android.content.Context
import dev.fasa.db.Db
import dev.fasa.db.Meta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// A log of "the screen went dark" moments.
//
// The band knows when sleep started. It cannot know when the phone was put
// down, and the gap between those two is sleep latency, which is a property of
// the body, not of the habit. Mixing them makes the behavioural delay look
// bigger than it is.
//
// The last screen off before a night is the closest thing to "decided to stop"
// that a phone can observe without any extra permission.
//
// Stored in the key value table rather than a new table on purpose: one row a
// night, no schema change, no migration on an existing install.
object Screen {

    private const val KEY = "screen_off_log"

    // Two months is more than the behavioural window ever looks at.
    private const val KEEP = 300

    // Screens go dark constantly during the day. Only events with nothing for a
    // while afterwards can be a bedtime, but that is decided at read time; here
    // we just avoid logging a burst of toggles as separate events.
    private const val MIN_GAP_MS = 60_000L

    suspend fun record(context: Context, at: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val arr = read(context)
            val last = if (arr.length() > 0) arr.optLong(arr.length() - 1) else 0L
            if (at - last < MIN_GAP_MS) return@withContext

            arr.put(at)
            val trimmed = if (arr.length() <= KEEP) arr else JSONArray().also { out ->
                for (i in arr.length() - KEEP until arr.length()) out.put(arr.optLong(i))
            }
            db.meta().put(Meta(KEY, trimmed.toString()))
        }

    private suspend fun read(context: Context): JSONArray {
        val raw = Db.get(context).meta().get(KEY) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    // All recorded events, oldest first.
    suspend fun all(context: Context): List<Long> = withContext(Dispatchers.IO) {
        val arr = read(context)
        val out = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optLong(i))
        out.sort()
        out
    }

    // The last screen off in the hours before sleep began. That is the moment
    // the person actually went to bed. Null when the phone was not touched at
    // all, which is rare but honest.
    fun bedtimeBefore(events: List<Long>, sleepStart: Long, windowMs: Long = 3L * 3600 * 1000): Long? {
        var best: Long? = null
        for (e in events) {
            if (e > sleepStart) break
            if (sleepStart - e <= windowMs) best = e
        }
        return best
    }
}
