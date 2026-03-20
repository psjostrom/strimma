package com.psjostrom.strimma.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [GlucoseReading::class, Treatment::class], version = 3)
abstract class StrimmaDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun treatmentDao(): TreatmentDao

    companion object {
        const val DB_NAME = "strimma.db"

        private const val MGDL_FACTOR = 18.0182

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `treatments` (
                        `id` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `insulin` REAL,
                        `carbs` REAL,
                        `basalRate` REAL,
                        `duration` INTEGER,
                        `enteredBy` TEXT,
                        `fetchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `readings_new` (
                        `ts` INTEGER NOT NULL,
                        `sgv` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `delta` REAL,
                        `pushed` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`ts`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `readings_new` (`ts`, `sgv`, `direction`, `delta`, `pushed`)
                    SELECT `ts`, `sgv`, `direction`,
                        CASE WHEN `deltaMmol` IS NOT NULL THEN `deltaMmol` * $MGDL_FACTOR ELSE NULL END,
                        `pushed`
                    FROM `readings`
                """.trimIndent())
                db.execSQL("DROP TABLE `readings`")
                db.execSQL("ALTER TABLE `readings_new` RENAME TO `readings`")
            }
        }

        @Volatile
        private var INSTANCE: StrimmaDatabase? = null

        fun getInstance(context: Context): StrimmaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StrimmaDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
