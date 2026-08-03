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
