package com.psjostrom.strimma.widget

import com.psjostrom.strimma.data.workout.WorkoutModeManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Entry point for non-Hilt-managed Glance app widget to access singleton dependencies.
 * Use via [dagger.hilt.android.EntryPointAccessors.fromApplication].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun workoutModeManager(): WorkoutModeManager
}
