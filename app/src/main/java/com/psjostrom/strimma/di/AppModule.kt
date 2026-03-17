package com.psjostrom.strimma.di

import android.content.Context
import androidx.room.Room
import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.StrimmaDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): StrimmaDatabase {
        return Room.databaseBuilder(
            context,
            StrimmaDatabase::class.java,
            "strimma.db"
        ).build()
    }

    @Provides
    fun provideReadingDao(db: StrimmaDatabase): ReadingDao = db.readingDao()
}
