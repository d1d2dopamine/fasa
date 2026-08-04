package dev.vespian.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Night::class,
        HrSample::class,
        LightSample::class,
        Answer::class,
        Sip::class,
        Nap::class,
        ModelState::class,
        Meta::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun nights(): NightDao
    abstract fun hr(): HrDao
    abstract fun light(): LightDao
    abstract fun answers(): AnswerDao
    abstract fun sips(): SipDao
    abstract fun naps(): NapDao
    abstract fun model(): ModelDao
    abstract fun meta(): MetaDao

    companion object {

        /**
         * Adds the all day heart rate table.
         *
         * This must exist and must be written by hand. Without a migration Room
         * refuses to open a database whose schema is older than the code, and
         * the only alternative it offers is wiping the file. That file holds
         * every recorded night, so wiping is never acceptable here.
         *
         * The statement only creates a new empty table. No existing table is
         * touched, so nothing already measured can be lost.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hr` " +
                        "(`at` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, PRIMARY KEY(`at`))"
                )
            }
        }

        /**
         * Adds the honesty columns to the light log.
         *
         * [LightSample.kind] separates a real reading from a covered sensor and
         * from a window where the sensor said nothing at all. [screenMs] and
         * [brightness] record phone use, which the light sensor cannot see.
         *
         * Only new columns with defaults are added, so every existing reading
         * survives untouched and counts as a real one.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `light` ADD COLUMN `kind` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `light` ADD COLUMN `screenMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `light` ADD COLUMN `brightness` INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * Adds the other drinks to the daily answer.
         *
         * `cans` counts caffeinated drinks that are not coffee and joins the
         * same caffeine total. `alcohol` counts standard drinks and is scored
         * by its own parameter.
         *
         * Both are nullable with no default, because null means the question
         * was never answered and zero means it was answered with none. Rows
         * written before this migration are genuinely unanswered, so null is
         * the truthful value for them.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `answers` ADD COLUMN `cans` INTEGER")
                db.execSQL("ALTER TABLE `answers` ADD COLUMN `alcohol` INTEGER")
            }
        }

        /**
         * Adds the per drink log with times.
         *
         * The daily counts in `answers` stay exactly as they are and keep
         * working. This table sits beside them and records when each drink
         * happened, so caffeine can be decayed from the hour it was taken
         * instead of from an assumed eleven in the morning.
         *
         * A new empty table only. Nothing already recorded is read or written
         * here, and days logged before this build keep being scored the old
         * way rather than being silently reinterpreted.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sips` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`loggedAt` INTEGER NOT NULL, " +
                        "`kind` INTEGER NOT NULL, " +
                        "`slackMinutes` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sips_at` ON `sips` (`at`)")
            }
        }

        /**
         * Adds the daytime sleep table.
         *
         * A new empty table only, so nothing recorded can be lost. Naps are not
         * back filled either: the band's older short sessions were never stored
         * and cannot be recovered, and inventing them would change the meaning
         * of every night already fitted.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `naps` " +
                        "(`start` INTEGER NOT NULL, " +
                        "`end` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`start`))"
                )
            }
        }

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Db::class.java,
                    "vespian.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}
