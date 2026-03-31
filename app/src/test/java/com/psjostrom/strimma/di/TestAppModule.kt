package com.psjostrom.strimma.di

import android.content.Context
import androidx.room.Room
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.health.ExerciseDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StrimmaDatabase {
        return Room.inMemoryDatabaseBuilder(context, StrimmaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Provides
    fun provideReadingDao(db: StrimmaDatabase): ReadingDao = db.readingDao()

    @Provides
    fun provideTreatmentDao(db: StrimmaDatabase): TreatmentDao = db.treatmentDao()

    @Provides
    fun provideExerciseDao(db: StrimmaDatabase): ExerciseDao = db.exerciseDao()
}
