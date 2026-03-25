package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.health.HeartRateSample
import com.psjostrom.strimma.data.health.StoredExerciseSession

@Database(
    entities = [
        GlucoseReading::class,
        Treatment::class,
        StoredExerciseSession::class,
        HeartRateSample::class
    ],
    version = 4,
    autoMigrations = [AutoMigration(from = 3, to = 4)]
)
abstract class StrimmaDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun treatmentDao(): TreatmentDao
    abstract fun exerciseDao(): ExerciseDao

    companion object {
        const val DB_NAME = "strimma.db"

        @Volatile
        private var INSTANCE: StrimmaDatabase? = null

        fun getInstance(context: Context): StrimmaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StrimmaDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}
