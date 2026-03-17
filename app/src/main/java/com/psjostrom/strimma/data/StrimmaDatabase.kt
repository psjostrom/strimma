package com.psjostrom.strimma.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [GlucoseReading::class], version = 1)
abstract class StrimmaDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        const val DB_NAME = "strimma.db"
    }
}
