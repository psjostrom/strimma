package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GlucoseReading::class], version = 1)
abstract class StrimmaDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

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
