package dev.vespian.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Night::class, LightSample::class, Answer::class, ModelState::class, Meta::class],
    version = 1,
    exportSchema = false,
)
abstract class Db : RoomDatabase() {
    abstract fun nights(): NightDao
    abstract fun light(): LightDao
    abstract fun answers(): AnswerDao
    abstract fun model(): ModelDao
    abstract fun meta(): MetaDao

    companion object {
        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Db::class.java,
                    "vespian.db",
                ).build().also { instance = it }
            }
    }
}
