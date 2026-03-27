package com.psjostrom.strimma.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.psjostrom.strimma.data.calendar.MetabolicProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseCategoryTest {

    // --- fromHCType ---

    @Test
    fun `running maps to RUNNING`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
    }

    @Test
    fun `hiking maps to HIKING not WALKING`() {
        assertEquals(ExerciseCategory.HIKING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
    }

    @Test
    fun `walking maps to WALKING`() {
        assertEquals(ExerciseCategory.WALKING, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
    }

    @Test
    fun `weightlifting and calisthenics map to STRENGTH`() {
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS))
    }

    @Test
    fun `yoga maps to YOGA`() {
        assertEquals(ExerciseCategory.YOGA, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_YOGA))
        assertEquals(ExerciseCategory.YOGA, ExerciseCategory.fromHCType(ExerciseSessionRecord.EXERCISE_TYPE_PILATES))
    }

    @Test
    fun `unknown type maps to OTHER`() {
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromHCType(9999))
    }

    // --- fromTitle ---

    @Test
    fun `fromTitle matches running keywords`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Easy Run"))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Morning jog"))
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("Löpning"))
    }

    @Test
    fun `fromTitle matches Swedish keywords`() {
        assertEquals(ExerciseCategory.WALKING, ExerciseCategory.fromTitle("Promenad med hunden"))
        assertEquals(ExerciseCategory.HIKING, ExerciseCategory.fromTitle("Vandring i fjällen"))
        assertEquals(ExerciseCategory.STRENGTH, ExerciseCategory.fromTitle("Styrketräning"))
        assertEquals(ExerciseCategory.CYCLING, ExerciseCategory.fromTitle("Cykeltur"))
        assertEquals(ExerciseCategory.SKIING, ExerciseCategory.fromTitle("Skidtur"))
        assertEquals(ExerciseCategory.CLIMBING, ExerciseCategory.fromTitle("Klättring"))
    }

    @Test
    fun `fromTitle returns OTHER for unrecognized titles`() {
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromTitle("Padel med Johan"))
        assertEquals(ExerciseCategory.OTHER, ExerciseCategory.fromTitle(""))
    }

    @Test
    fun `fromTitle is case insensitive`() {
        assertEquals(ExerciseCategory.RUNNING, ExerciseCategory.fromTitle("RUNNING"))
        assertEquals(ExerciseCategory.SWIMMING, ExerciseCategory.fromTitle("SWIM"))
    }

    // --- defaultMetabolicProfile ---

    @Test
    fun `STRENGTH defaults to RESISTANCE profile`() {
        assertEquals(MetabolicProfile.RESISTANCE, ExerciseCategory.STRENGTH.defaultMetabolicProfile)
    }

    @Test
    fun `CLIMBING defaults to RESISTANCE profile`() {
        assertEquals(MetabolicProfile.RESISTANCE, ExerciseCategory.CLIMBING.defaultMetabolicProfile)
    }

    @Test
    fun `MARTIAL_ARTS defaults to HIGH_INTENSITY profile`() {
        assertEquals(MetabolicProfile.HIGH_INTENSITY, ExerciseCategory.MARTIAL_ARTS.defaultMetabolicProfile)
    }

    @Test
    fun `RUNNING defaults to AEROBIC profile`() {
        assertEquals(MetabolicProfile.AEROBIC, ExerciseCategory.RUNNING.defaultMetabolicProfile)
    }
}
