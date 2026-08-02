package dev.vespian.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Nights the person did not end on their own.
//
// Why this matters more than it looks: the model reads a spontaneous wake up as
// proof that sleep pressure reached the floor. That is the single strongest
// piece of evidence it gets, and it is what makes a "sleep need" parameter
// unnecessary. An alarm or another person ending the night breaks that
// inference completely, and without a way to say so every interrupted night
// would quietly teach the model that this body needs less sleep than it does.
//
// Stored as a plain list of date keys in the meta table rather than as a column
// on the night itself, for one practical reason: the flag is usually set in the
// morning, and the night row often arrives later, whenever the band next syncs.
// Keying by date lets the answer be recorded before the data it belongs to
// exists.
object Forced {

    const val KEY = "forced_wakes"

    // Half a year of mornings. Older entries cannot influence any forecast.
    const val KEEP = 180

    private fun parse(raw: String?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (raw.isNullOrBlank()) return out
        for (part in raw.split(',')) {
            val key = part.trim()
            if (key.isNotEmpty()) out.add(key)
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
    // one place is the same tap in the other.
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

    // Returns the state after the change, so a caller can render the button
    // without a second read.
    suspend fun set(context: Context, dateKey: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val db = Db.get(context)
            val set = parse(db.meta().get(KEY))
            if (on) set.add(dateKey) else set.remove(dateKey)
            val trimmed = set.sorted().takeLast(KEEP)
            db.meta().put(Meta(KEY, trimmed.joinToString(",")))
            on
        }
}