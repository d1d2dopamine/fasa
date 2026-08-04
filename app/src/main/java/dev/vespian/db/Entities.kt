package dev.vespian.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One night. dateKey is the local date of the wake-up, "yyyy-MM-dd". */
@Entity(tableName = "nights")
data class Night(
    @PrimaryKey val dateKey: String,
    val sleepStart: Long,
    val sleepEnd: Long,
    val minutesAsleep: Int,
    val minutesDeep: Int,
    val minutesRem: Int,
    val minutesAwake: Int,
    val hrMin: Int?,
    val hrMinAt: Long?,
    val hrMean: Int?,
    val spo2Mean: Float?,
    val source: String = "mi",
    val importedAt: Long,
)

/**
 * One heart rate reading, at any hour of the day.
 *
 * The nightly minimum alone is a single point: it says where the body clock
 * bottomed out but nothing about how deep or how sharp that bottom was, and one
 * bad beat can move it by an hour. Keeping the whole day lets the model fit a
 * curve through hundreds of readings, which is much harder to fool.
 *
 * Time is the primary key, so re-reading the same window overwrites instead of
 * piling up duplicates.
 */
@Entity(tableName = "hr")
data class HrSample(
    @PrimaryKey val at: Long,
    val bpm: Int,
)

@Entity(tableName = "light")
data class LightSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lux: Float,
    val screenOn: Boolean,
    // Whether this reading says anything about the light the eyes saw.
    //
    // A phone in a pocket reads zero at noon. Feeding that into the clock model
    // as real darkness is a lie the model cannot detect, so untrusted rows are
    // stored and shown but never scored.
    val kind: Int = KIND_OK,
    // Milliseconds the screen was on during the window this row stands for.
    // Free to collect and the honest measure of phone use.
    val screenMs: Long = 0L,
    // System screen brightness setting, 0..255, or -1 when unknown.
    val brightness: Int = -1,
) {
    companion object {
        // A real reading of the surroundings.
        const val KIND_OK = 0

        // Dark with the screen off: pocket, bag, or face down on a table.
        const val KIND_OCCLUDED = 1

        // The sensor returned nothing for the whole window. Written on purpose
        // so a hole in the log cannot be mistaken for the app being dead.
        const val KIND_GAP = 2
    }
}

/**
 * What the person told the app about one day.
 *
 * [mugs] and [cans] are counts, not millilitres: every drink is one tap and the
 * app converts it to milligrams of caffeine using a figure set once in
 * settings. Both feed the same caffeine total, because caffeine is one molecule
 * whatever carried it.
 *
 * [alcohol] is counted in standard drinks and is kept apart on purpose. It does
 * not raise the sleep threshold, it lowers it and then spoils the recovery, so
 * it is a different quantity with its own parameter in the model.
 *
 * Null means not answered, which is not the same as zero.
 */
@Entity(tableName = "answers")
data class Answer(
    @PrimaryKey val dateKey: String,
    val mood: Int?,
    val mugs: Int?,
    val at: Long,
    val cans: Int? = null,
    val alcohol: Int? = null,
)

/**
 * One drink, with the time it was actually drunk and how sure that time is.
 *
 * The daily [Answer] counts drinks. A count is enough to know that caffeine was
 * taken but not enough to know what it did: 130 mg at nine in the morning is
 * gone by bedtime, and the same 130 mg at nine in the evening is most of the
 * reason the night starts at three. Until now the app assumed every drink
 * happened at eleven, which is a guess dressed as a measurement.
 *
 * Asking for a time is not an option either. Typing a time is a chore, and a
 * chore is the one thing an attention disorder will not do twice, so a drink
 * would simply go unlogged and the model would be worse off than with a bad
 * guess. So the app takes the tap as the time and lets the person say, in one
 * tap, that it happened a while ago.
 *
 * @property at the estimated moment the drink was drunk.
 * @property loggedAt the moment the button was actually pressed. Kept so the
 *   record can never be quietly rewritten: the difference between the two is
 *   how late the report was, and that is information about the report, not
 *   about the drink.
 * @property slackMinutes how wrong [at] could be, in minutes either way. Zero
 *   means logged as it happened. A drink remembered two hours later carries an
 *   hour of slack, and the model spreads it over that window instead of
 *   pretending to know the minute. A vague dose spread wide is honest; a vague
 *   dose treated as exact teaches the model a lie it cannot detect.
 */
@Entity(tableName = "sips")
data class Sip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val loggedAt: Long,
    val kind: Int,
    val slackMinutes: Int = 0,
) {
    companion object {
        const val KIND_COFFEE = 0
        const val KIND_CAN = 1
        const val KIND_ALCOHOL = 2
    }
}

/**
 * A daytime sleep.
 *
 * Naps were thrown away until now: anything shorter than two hours was not a
 * night, and not being a night it was not stored at all. That is wrong in the
 * one direction that hurts most here. Sleep pressure is the whole engine of
 * this model, and an hour of afternoon sleep discharges a real part of it. A
 * model that never hears about it sees a body that should have been ready for
 * bed at midnight and a person who was not, and it has nowhere to put the
 * difference except into the body clock, where it does not belong.
 *
 * Delayed sleep phase makes this the normal case rather than an edge case: a
 * night that ended at six in the morning is very often repaid in the afternoon.
 *
 * Stored separately from nights on purpose. A nap is not a short night: it has
 * no stages worth trusting, it says nothing about when the body wanted to
 * sleep, and mixing the two would let an afternoon hour count as evidence about
 * phase. It is only ever read as a discharge of pressure.
 *
 * @property start when the daytime sleep began.
 * @property end when it ended.
 * @property source where it came from, so a nap read from the band can be told
 *   from one entered by hand.
 */
@Entity(tableName = "naps")
data class Nap(
    @PrimaryKey val start: Long,
    val end: Long,
    val source: String = "mi",
) {
    /** How long it lasted, in minutes. */
    val minutes: Int get() = ((end - start) / 60_000L).toInt()
}

@Entity(tableName = "model")
data class ModelState(
    @PrimaryKey val id: Int = 1,
    val particles: String?,
    val updatedAt: Long,
)

@Entity(tableName = "meta")
data class Meta(
    @PrimaryKey val key: String,
    val value: String,
)
