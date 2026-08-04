package dev.vespian.model

import android.content.Context
import dev.vespian.db.Db
import dev.vespian.db.Meta
import dev.vespian.db.Sip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * The two ways the app finds out about caffeine without being told.
 *
 * One tap is as cheap as an interface gets, and it is still too expensive on
 * the days that matter most: the day a person forgets to log is not a random
 * day, it is the day that went badly, and those are the days the model needs.
 * So there are two fallbacks, in order of how much they assume.
 *
 * 1. One question in the evening. Not "how many and when", which is a form to
 *    fill in, but "was there coffee after four", which is one tap and can be
 *    answered from memory. Asked once a day, and only when nothing was logged.
 * 2. The habit. After a few weeks the app knows roughly when the first drink
 *    usually lands and can offer that instead of asking. A guess from a habit
 *    is labelled as a guess and carries the spread of the habit as its slack,
 *    so it can never masquerade as a measurement.
 *
 * A silent day stays a silent day: nothing here writes what was not agreed to.
 */
object Drinks {

    /**
     * The day whose drink question has been settled, as a date key.
     *
     * One key for both fallbacks: answering either silences the other for the
     * rest of the day. Asking again from a different angle is nagging, and
     * nagging is how a person learns to ignore the app.
     */
    const val K_ASKED = "drink_asked"

    /** The logging day starts at four in the morning, as everywhere else. */
    private const val DAY_START_H = 4L
    private const val DAY_MS = 24L * 3600 * 1000

    /** "Late" caffeine: the kind still working at bedtime. */
    const val LATE_FROM_H = 16

    /** Days of history the habit is read from. */
    const val HABIT_WINDOW_DAYS = 45

    /**
     * Logged days needed before the app guesses from habit. Below this there is
     * no routine, only a few scattered days.
     */
    const val HABIT_MIN_DAYS = 10

    private const val HABIT_MIN_SLACK = 20
    private const val HABIT_MAX_SLACK = 90

    /**
     * A recognised routine.
     *
     * @property offsetMinutes minutes after the logging day began, so the value
     *   survives the day boundary being four in the morning, not midnight.
     * @property spreadMinutes how much the time moves day to day; this becomes
     *   the slack of anything logged from the habit.
     * @property days how many days it was read from.
     */
    class Habit(
        val offsetMinutes: Int,
        val spreadMinutes: Int,
        val days: Int,
    ) {
        /** When this habit says today's first drink happened. */
        fun atToday(): Long = dayStart() + offsetMinutes * 60_000L
    }

    // ---- day arithmetic --------------------------------------------------

    /** Start of the current logging day. */
    fun dayStart(now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val day = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .minusHours(DAY_START_H)
            .toLocalDate()
        return ZonedDateTime.of(day, LocalTime.MIDNIGHT, zone)
            .plusHours(DAY_START_H)
            .toInstant()
            .toEpochMilli()
    }

    /** Date key of the current logging day. */
    fun dayKey(now: Long = System.currentTimeMillis()): String =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
            .minusHours(DAY_START_H)
            .toLocalDate()
            .toString()

    private fun todayAtHour(hour: Int): Long =
        ZonedDateTime.of(LocalDate.now(), LocalTime.of(hour, 0), ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // ---- what is known about today ---------------------------------------

    /**
     * Whether today already has a drink on record, by timed tap or by count.
     * The counts are checked too, so a day answered through the bot's number
     * buttons is not treated as an empty day.
     */
    suspend fun anyToday(context: Context): Boolean = withContext(Dispatchers.IO) {
        val db = Db.get(context)
        val start = dayStart()
        val timed = runCatching {
            db.sips().countSince(start, Sip.KIND_COFFEE) +
                db.sips().countSince(start, Sip.KIND_CAN)
        }.getOrDefault(0)
        if (timed > 0) return@withContext true
        val answer = runCatching { db.answers().byDate(dayKey()) }.getOrNull()
        (answer?.mugs ?: 0) > 0 || (answer?.cans ?: 0) > 0
    }

    /** Whether today's drink question has already been settled. */
    suspend fun settled(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching { Db.get(context).meta().get(K_ASKED) }.getOrNull() == dayKey()
    }

    /**
     * Remember that today was answered, whichever way. "No" is an answer worth
     * recording: the difference between no caffeine and nobody asking is the
     * difference between a measurement and a hole.
     */
    suspend fun markSettled(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching { Db.get(context).meta().put(Meta(K_ASKED, dayKey())) }
        }
    }

    // ---- the evening question -------------------------------------------

    /**
     * Whether to ask about late caffeine now. Only in the evening, only when
     * the day is otherwise empty, and only once. Asked at four the answer would
     * be a forecast rather than a memory.
     */
    suspend fun shouldAskLate(context: Context): Boolean {
        if (System.currentTimeMillis() < todayAtHour(LATE_FROM_H + 3)) return false
        if (settled(context)) return false
        return !anyToday(context)
    }

    /**
     * Record a yes as one drink in the middle of the window that was asked
     * about, with slack covering the whole of it. The model then reads it for
     * what it is: something happened this evening, exact hour unknown.
     */
    suspend fun logLate(context: Context) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val from = todayAtHour(LATE_FROM_H)
            val middle = from + (now - from) / 2
            val slack = ((now - from) / 2 / 60_000L).toInt().coerceIn(30, 180)
            runCatching {
                Db.get(context).sips().put(
                    Sip(at = middle, loggedAt = now, kind = Sip.KIND_COFFEE, slackMinutes = slack)
                )
            }
            Engine.invalidate()
        }
        markSettled(context)
    }

    // ---- the habit ------------------------------------------------------

    /**
     * When the first drink of the day usually happens, if there is a pattern.
     *
     * Read from the timed log only: the daily counts have no times in them,
     * which is the whole reason this exists. The middle value is used rather
     * than the average and the spread is the typical distance from it, so one
     * night shift cannot move a routine built out of weeks.
     */
    suspend fun habit(context: Context): Habit? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val anchor = dayStart(now)
        val from = anchor - HABIT_WINDOW_DAYS * DAY_MS
        val rows = runCatching { Db.get(context).sips().between(from, now + 1) }
            .getOrDefault(emptyList())
            .filter { it.kind == Sip.KIND_COFFEE || it.kind == Sip.KIND_CAN }
        if (rows.isEmpty()) return@withContext null

        // Earliest drink of each logging day. Grouping on the day index keeps
        // the four in the morning boundary; an hour of daylight saving shifts a
        // day by one hour, which is well inside the spread this reports.
        val firstByDay = HashMap<Long, Long>()
        for (sip in rows) {
            val index = Math.floorDiv(sip.at - anchor, DAY_MS)
            val current = firstByDay[index]
            if (current == null || sip.at < current) firstByDay[index] = sip.at
        }

        // Today is still in progress, so it is not evidence about a routine.
        firstByDay.remove(0L)
        if (firstByDay.size < HABIT_MIN_DAYS) return@withContext null

        val offsets = firstByDay.map { (index, at) ->
            ((at - (anchor + index * DAY_MS)) / 60_000L).toInt()
        }.sorted()
        val middle = median(offsets)
        val spread = median(offsets.map { abs(it - middle) }.sorted())

        Habit(
            offsetMinutes = middle,
            spreadMinutes = (spread * 3 / 2).coerceIn(HABIT_MIN_SLACK, HABIT_MAX_SLACK),
            days = firstByDay.size,
        )
    }

    /**
     * Whether to offer the habit as today's first drink. Only once the usual
     * time has comfortably passed: offering a guess about a time that has not
     * arrived is asking someone to predict their own morning.
     */
    suspend fun shouldOfferHabit(context: Context, habit: Habit): Boolean {
        if (System.currentTimeMillis() < habit.atToday() + 30 * 60_000L) return false
        if (settled(context)) return false
        return !anyToday(context)
    }

    /**
     * Accept the guess: one drink at the usual time, carrying the spread of the
     * routine as its slack so it is never mistaken for a logged time.
     */
    suspend fun logHabit(context: Context, habit: Habit) {
        withContext(Dispatchers.IO) {
            runCatching {
                Db.get(context).sips().put(
                    Sip(
                        at = habit.atToday(),
                        loggedAt = System.currentTimeMillis(),
                        kind = Sip.KIND_COFFEE,
                        slackMinutes = habit.spreadMinutes,
                    )
                )
            }
            Engine.invalidate()
        }
        markSettled(context)
    }

    /** Middle value of an already sorted list. */
    private fun median(sorted: List<Int>): Int {
        if (sorted.isEmpty()) return 0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
