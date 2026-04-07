package com.psjostrom.strimma.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.psjostrom.strimma.createTestDataStore
import com.psjostrom.strimma.widget.WidgetSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = SettingsRepository(context, WidgetSettingsRepository(context), createTestDataStore())
    }

    @Test
    fun `setGlucoseSource updates both DataStore and SharedPreferences`() = runTest {
        repo.setGlucoseSource(GlucoseSource.NIGHTSCOUT_FOLLOWER)

        val fromFlow = repo.glucoseSource.first()
        val fromSync = repo.getGlucoseSourceSync()

        assertEquals(GlucoseSource.NIGHTSCOUT_FOLLOWER, fromFlow)
        assertEquals(GlucoseSource.NIGHTSCOUT_FOLLOWER, fromSync)
    }

    @Test
    fun `setStartOnBoot updates both DataStore and SharedPreferences`() = runTest {
        repo.setStartOnBoot(false)

        val fromFlow = repo.startOnBoot.first()
        val fromSync = repo.getStartOnBootSync()

        assertEquals(false, fromFlow)
        assertEquals(false, fromSync)
    }

    @Test
    fun `getGlucoseSourceSync defaults to COMPANION when unset`() {
        assertEquals(GlucoseSource.COMPANION, repo.getGlucoseSourceSync())
    }

    @Test
    fun `getStartOnBootSync defaults to true when unset`() {
        assertEquals(true, repo.getStartOnBootSync())
    }
}
