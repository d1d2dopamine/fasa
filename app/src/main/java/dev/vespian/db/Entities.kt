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

@Entity(tableName = "light")
data class LightSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val lux: Float,
    val screenOn: Boolean,
)

@Entity(tableName = "answers")
data class Answer(
    @PrimaryKey val dateKey: String,
    val mood: Int?,
    val mugs: Int?,
    val at: Long,
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
