package com.psjostrom.strimma.wallpaper

import com.psjostrom.strimma.data.ReadingDao
import com.psjostrom.strimma.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WallpaperEntryPoint {
    fun readingDao(): ReadingDao
    fun settingsRepository(): SettingsRepository
}
