package dev.vespian.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Mornings that ended by an alarm or by another person rather than by the body.
//
// Stored as a comma separated list of date keys in the meta table, deliberately
// not as a column on the night row: the flag is set in the morning, and the
// band's night row often syncs hours later. A column would mean either losing
// the tap or migrating the schema for one boolean.
object Forced {

    const val KEY = "forced_wakes"

    // Older entries stop mattering once they fall out of the stored nights.
    const val KEEP = 180

    private fun parse(raw: String?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (raw.isNullOrBlank()) return out
        for (part in raw.split(",")) {
            val t = part.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    suspend fun all(context: Context): Set<String> = withContext(Dispatchers.IO) {
        parse(Db.get(context).meta().get(KEY))
    }

    suspend fun has(context: Context, dateKey: String): Boolean =
        all(context).contains(dateKey)

    // The morning a flag set right now belongs to: the day the last recorded
    // night ended. One definition shared by the chat and the app, so a tap in
    // one place means the same thing in the other.
    suspend fun currentKey(context: Context): String = withContext(Dispatchers.IO) {
        val wake = Db.get(context).nights().lastSleepEnd() ?: 0L
        if (wake > 0L) {
            java.time.Instant.ofEpochMilli(wake)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        } else {
            java.time.LocalDate.now().toString()
        }
    }

    // Returns the state after the change, so a caller can draw the button
    // without reading back.
    suspend fun set(context: Context, dateKey: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val cur = parse(db.meta().get(KEY))
            if (on) cur.add(dateKey) else cur.remove(dateKey)
            val kept = if (cur.size > KEEP) cur.toList().takeLast(KEEP) else cur.toList()
            db.meta().put(Meta(KEY, kept.joinToString(",")))
            on
        }
}
