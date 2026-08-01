package dev.fasa.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(night: Night)

    @Query("SELECT * FROM nights ORDER BY sleepEnd ASC")
    suspend fun all(): List<Night>

    @Query("SELECT * FROM nights ORDER BY sleepEnd DESC LIMIT :n")
    suspend fun last(n: Int): List<Night>

    @Query("SELECT COUNT(*) FROM nights")
    suspend fun count(): Int

    @Query("SELECT MAX(sleepEnd) FROM nights")
    suspend fun lastSleepEnd(): Long?
}

@Dao
interface LightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(sample: LightSample)

    @Query("SELECT * FROM light WHERE at BETWEEN :from AND :to ORDER BY at ASC")
    suspend fun between(from: Long, to: Long): List<LightSample>

    @Query("DELETE FROM light WHERE at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface AnswerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(answer: Answer)

    @Query("SELECT * FROM answers ORDER BY at DESC LIMIT :n")
    suspend fun last(n: Int): List<Answer>

    @Query("SELECT * FROM answers WHERE dateKey = :dateKey")
    suspend fun byDate(dateKey: String): Answer?
}

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: ModelState)

    @Query("SELECT * FROM model WHERE id = 1")
    suspend fun get(): ModelState?
}

@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: Meta)

    // `key` is a reserved word in newer SQLite, it must stay quoted.
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?
}
