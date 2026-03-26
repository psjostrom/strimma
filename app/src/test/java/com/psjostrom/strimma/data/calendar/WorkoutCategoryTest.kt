package com.psjostrom.strimma.data.calendar

import org.junit.Assert.*
import org.junit.Test

class WorkoutCategoryTest {

    @Test
    fun `interval keywords match INTERVAL`() {
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("Tempo Run"))
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("4x4 intervals"))
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("Fartlek session"))
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("Threshold workout"))
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("Speed work"))
    }

    @Test
    fun `long keywords match LONG`() {
        assertEquals(WorkoutCategory.LONG, WorkoutCategory.fromTitle("Long Run"))
        assertEquals(WorkoutCategory.LONG, WorkoutCategory.fromTitle("LSR 25km"))
        assertEquals(WorkoutCategory.LONG, WorkoutCategory.fromTitle("Marathon pace"))
    }

    @Test
    fun `strength keywords match STRENGTH`() {
        assertEquals(WorkoutCategory.STRENGTH, WorkoutCategory.fromTitle("Gym session"))
        assertEquals(WorkoutCategory.STRENGTH, WorkoutCategory.fromTitle("Strength training"))
        assertEquals(WorkoutCategory.STRENGTH, WorkoutCategory.fromTitle("Core workout"))
        assertEquals(WorkoutCategory.STRENGTH, WorkoutCategory.fromTitle("Weights"))
        assertEquals(WorkoutCategory.STRENGTH, WorkoutCategory.fromTitle("Lift"))
    }

    @Test
    fun `easy keywords match EASY`() {
        assertEquals(WorkoutCategory.EASY, WorkoutCategory.fromTitle("Easy Run"))
        assertEquals(WorkoutCategory.EASY, WorkoutCategory.fromTitle("Recovery jog"))
        assertEquals(WorkoutCategory.EASY, WorkoutCategory.fromTitle("Walk"))
    }

    @Test
    fun `no match returns FALLBACK`() {
        assertEquals(WorkoutCategory.FALLBACK, WorkoutCategory.fromTitle("Dentist appointment"))
        assertEquals(WorkoutCategory.FALLBACK, WorkoutCategory.fromTitle(""))
    }

    @Test
    fun `interval has priority over easy`() {
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("Easy intervals"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(WorkoutCategory.INTERVAL, WorkoutCategory.fromTitle("TEMPO RUN"))
        assertEquals(WorkoutCategory.EASY, WorkoutCategory.fromTitle("easy run"))
    }
}
