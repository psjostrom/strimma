package com.psjostrom.strimma.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.StrimmaDatabase
import com.psjostrom.strimma.data.TreatmentDao
import com.psjostrom.strimma.data.health.ExerciseDao
import com.psjostrom.strimma.data.settingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StrimmaDatabase {
        return StrimmaDatabase.getInstance(context)
    }

    @Provides
    fun provideReadingDao(db: StrimmaDatabase): ReadingDao = db.readingDao()

    @Provides
    fun provideTreatmentDao(db: StrimmaDatabase): TreatmentDao = db.treatmentDao()

    @Provides
    fun provideExerciseDao(db: StrimmaDatabase): ExerciseDao = db.exerciseDao()
}
