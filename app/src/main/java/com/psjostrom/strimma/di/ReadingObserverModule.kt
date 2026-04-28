package com.psjostrom.strimma.di

import com.psjostrom.strimma.network.NightscoutPusher
import com.psjostrom.strimma.service.ReadingPusher
import com.psjostrom.strimma.service.ReadingUploader
import com.psjostrom.strimma.tidepool.TidepoolUploader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReadingObserverModule {
    @Binds
    abstract fun bindReadingPusher(impl: NightscoutPusher): ReadingPusher

    @Binds
    abstract fun bindReadingUploader(impl: TidepoolUploader): ReadingUploader
}
