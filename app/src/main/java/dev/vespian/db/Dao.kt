package dev.vespian.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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

    // The newest night that has actually finished. A row dated in the future,
    // whether from a wrong clock or a hand typed entry, must not be shown as
    // the last measurement.
    @Query("SELECT * FROM nights WHERE sleepEnd <= :before ORDER BY sleepEnd DESC LIMIT 1")
    suspend fun lastEnded(before: Long): Night?
}

@Dao
interface HrDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(samples: List<HrSample>)

    @Query("SELECT * FROM hr WHERE at BETWEEN :from AND :to ORDER BY at ASC")
    suspend fun between(from: Long, to: Long): List<HrSample>

    @Query("SELECT COUNT(*) FROM hr")
    suspend fun count(): Int

    /**
     * Keeps the newest [keep] readings and nothing else.
     *
     * A rolling window, not a purge. Deleting by age throws away a whole batch
     * the moment it crosses the line, which on a phone that was off for a week
     * means a sudden hole. Capping by count means one new reading pushes out
     * exactly one old reading, so the history is always the same length and the
     * oldest row is simply replaced by the newest.
     */
    @Query("DELETE FROM hr WHERE at NOT IN (SELECT at FROM hr ORDER BY at DESC LIMIT :keep)")
    suspend fun cap(keep: Int)

    /**
     * Rows no living heart could have produced.
     *
     * A garbled import or a half written row shows up as a beat rate of zero or
     * of several hundred. One of those bends the daily curve the model fits, so
     * they are dropped rather than reasoned about.
     */
    @Query("DELETE FROM hr WHERE bpm < 20 OR bpm > 250 OR at <= 0")
    suspend fun dropBroken(): Int
}

@Dao
interface LightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(sample: LightSample)

    @Query("SELECT * FROM light WHERE at BETWEEN :from AND :to ORDER BY at ASC")
    suspend fun between(from: Long, to: Long): List<LightSample>

    /** Same rolling window as the heart rate table. */
    @Query("DELETE FROM light WHERE id NOT IN (SELECT id FROM light ORDER BY at DESC LIMIT :keep)")
    suspend fun cap(keep: Int)

    /** Impossible readings: negative lux, or a row with no timestamp. */
    @Query("DELETE FROM light WHERE lux < 0 OR at <= 0")
    suspend fun dropBroken(): Int

    /** How many rows were written since [from]. */
    @Query("SELECT COUNT(*) FROM light WHERE at >= :from")
    suspend fun countSince(from: Long): Int

    /**
     * How many of those are real readings of the surroundings, as opposed to a
     * covered sensor or a window the sensor stayed silent through. This is the
     * number that means anything, so it is the number the notification shows.
     */
    @Query("SELECT COUNT(*) FROM light WHERE at >= :from AND kind = 0")
    suspend fun countTrustedSince(from: Long): Int
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
interface SipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(sip: Sip): Long

    @Update
    suspend fun update(sip: Sip)

    @Delete
    suspend fun delete(sip: Sip)

    /** Every drink in a window, oldest first. Used to decay each dose. */
    @Query("SELECT * FROM sips WHERE at >= :from AND at < :to ORDER BY at ASC")
    suspend fun between(from: Long, to: Long): List<Sip>

    /** One kind of drink since a moment, oldest first. */
    @Query("SELECT * FROM sips WHERE at >= :from AND kind = :kind ORDER BY at ASC")
    suspend fun since(from: Long, kind: Int): List<Sip>

    /**
     * The most recently logged drink of one kind, by when the button was
     * pressed rather than by when the drink happened. "I meant an hour ago"
     * always refers to the last thing tapped, even if an earlier drink has
     * already been backdated past it.
     */
    @Query("SELECT * FROM sips WHERE kind = :kind ORDER BY loggedAt DESC LIMIT 1")
    suspend fun lastLogged(kind: Int): Sip?

    @Query("SELECT COUNT(*) FROM sips WHERE at >= :from AND kind = :kind")
    suspend fun countSince(from: Long, kind: Int): Int

    @Query("DELETE FROM sips WHERE at >= :from AND kind = :kind")
    suspend fun clearSince(from: Long, kind: Int)

    /**
     * Rows that cannot be true: no timestamp, or a drink dated in a future
     * that has not happened yet. Same guard as the other tables.
     */
    @Query("DELETE FROM sips WHERE at <= 0 OR loggedAt <= 0 OR at > :notAfter")
    suspend fun dropBroken(notAfter: Long)

    /** Keep the newest rows only, matching the retention of the other logs. */
    @Query("DELETE FROM sips WHERE id NOT IN (SELECT id FROM sips ORDER BY at DESC LIMIT :keep)")
    suspend fun cap(keep: Int)
}

@Dao
interface NapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(nap: Nap)

    /** Every daytime sleep in a window, oldest first. */
    @Query("SELECT * FROM naps WHERE start >= :from AND start < :to ORDER BY start ASC")
    suspend fun between(from: Long, to: Long): List<Nap>

    @Query("SELECT COUNT(*) FROM naps WHERE start >= :from")
    suspend fun countSince(from: Long): Int

    /** Rows that cannot be true: no time, or a sleep that ends before it began. */
    @Query("DELETE FROM naps WHERE start <= 0 OR end <= start OR start > :notAfter")
    suspend fun dropBroken(notAfter: Long)

    /** Keep the newest rows only, matching the retention of the other logs. */
    @Query("DELETE FROM naps WHERE start NOT IN (SELECT start FROM naps ORDER BY start DESC LIMIT :keep)")
    suspend fun cap(keep: Int)
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

    // Everything, for the backup file. The table holds a few dozen rows.
    @Query("SELECT * FROM meta")
    suspend fun all(): List<Meta>
}
