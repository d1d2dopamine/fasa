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
        ModelState::class,
        Meta::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun nights(): NightDao
    abstract fun hr(): HrDao
    abstract fun light(): LightDao
    abstract fun answers(): AnswerDao
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

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Db::class.java,
                    "vespian.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
