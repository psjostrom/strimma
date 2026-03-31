package com.psjostrom.strimma.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psjostrom.strimma.ui.theme.StrimmaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class BottomNavTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderNav(
        currentRoute: String = "main",
        onNavigate: (String) -> Unit = {}
    ) {
        composeRule.setContent {
            StrimmaTheme {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "main",
                        onClick = { onNavigate("main") },
                        icon = { Icon(Icons.Filled.WaterDrop, contentDescription = null) },
                        label = { Text("BG") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "exercise",
                        onClick = { onNavigate("exercise") },
                        icon = { Icon(Icons.Filled.FitnessCenter, contentDescription = null) },
                        label = { Text("Exercise") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "stats",
                        onClick = { onNavigate("stats") },
                        icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                        label = { Text("Stats") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == "settings",
                        onClick = { onNavigate("settings") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") }
                    )
                }
            }
        }
    }

    @Test
    fun `shows all four navigation items`() {
        renderNav()
        composeRule.onNodeWithText("BG").assertExists()
        composeRule.onNodeWithText("Exercise").assertExists()
        composeRule.onNodeWithText("Stats").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun `BG tab is selected by default`() {
        renderNav(currentRoute = "main")
        composeRule.onNodeWithText("BG").assertIsSelected()
        composeRule.onNodeWithText("Stats").assertIsNotSelected()
    }

    @Test
    fun `tapping Stats fires navigation callback`() {
        var navigatedTo: String? = null
        renderNav(onNavigate = { navigatedTo = it })
        composeRule.onNodeWithText("Stats").performClick()
        assert(navigatedTo == "stats")
    }

    @Test
    fun `tapping Exercise fires navigation callback`() {
        var navigatedTo: String? = null
        renderNav(onNavigate = { navigatedTo = it })
        composeRule.onNodeWithText("Exercise").performClick()
        assert(navigatedTo == "exercise")
    }

    @Test
    fun `tapping Settings fires navigation callback`() {
        var navigatedTo: String? = null
        renderNav(onNavigate = { navigatedTo = it })
        composeRule.onNodeWithText("Settings").performClick()
        assert(navigatedTo == "settings")
    }

    @Test
    fun `stats tab shows as selected when current route is stats`() {
        renderNav(currentRoute = "stats")
        composeRule.onNodeWithText("Stats").assertIsSelected()
        composeRule.onNodeWithText("BG").assertIsNotSelected()
        composeRule.onNodeWithText("Exercise").assertIsNotSelected()
        composeRule.onNodeWithText("Settings").assertIsNotSelected()
    }

    @Test
    fun `exercise tab shows as selected when current route is exercise`() {
        renderNav(currentRoute = "exercise")
        composeRule.onNodeWithText("Exercise").assertIsSelected()
        composeRule.onNodeWithText("BG").assertIsNotSelected()
    }
}
