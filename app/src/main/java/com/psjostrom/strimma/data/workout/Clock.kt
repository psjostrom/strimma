package com.psjostrom.strimma.data.workout

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface Clock {
    fun nowMs(): Long
}

@Singleton
class SystemClock @Inject constructor() : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarPollerSourceModule {
    @Binds
    @Singleton
    abstract fun bindCalendarPollerSource(impl: com.psjostrom.strimma.data.calendar.CalendarPoller): CalendarPollerSource
}
