package com.example.obdtest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI navigation smoke tests; no adapter or ABS commands are involved.
 * Can run with Bluetooth permissions denied, which must not hide the app.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun deviceBenchmarkResultsAndBackButtons() {
        rule.onNodeWithText("1. Device").assertIsDisplayed()
        rule.onNodeWithText("2. Benchmark").performClick()
        rule.onNodeWithText("Configure benchmark").assertIsDisplayed()
        rule.onNodeWithText("3. Results").performClick()
        rule.onNodeWithText("Benchmark results").assertIsDisplayed()
        rule.onNodeWithText("BACK").performClick()
        rule.onNodeWithText("Configure benchmark").assertIsDisplayed()
        rule.onNodeWithText("BACK").performClick()
        rule.onNodeWithText("Choose the adapter").assertIsDisplayed()
    }

    @Test fun androidBackMovesResultsToBenchmarkAndDevice() {
        rule.onNodeWithText("3. Results").performClick()
        Espresso.pressBack()
        rule.onNodeWithText("Configure benchmark").assertIsDisplayed()
        Espresso.pressBack()
        rule.onNodeWithText("Choose the adapter").assertIsDisplayed()
    }

    @Test fun noAdapterNeverEnablesStart() {
        rule.onNodeWithText("2. Benchmark").performClick()
        rule.onNodeWithText("START BENCHMARK").assertIsNotEnabled()
    }

    @Test fun consoleIsReachableFromEveryTab() {
        rule.onNodeWithText("CONSOLE LOGS (").assertIsDisplayed()
        rule.onNodeWithText("2. Benchmark").performClick()
        rule.onNodeWithText("CONSOLE LOGS (").assertIsDisplayed()
        rule.onNodeWithText("3. Results").performClick()
        rule.onNodeWithText("CONSOLE LOGS (").assertIsDisplayed()
    }
}
