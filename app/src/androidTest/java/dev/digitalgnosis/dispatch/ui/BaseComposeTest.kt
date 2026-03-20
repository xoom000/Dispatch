package dev.digitalgnosis.dispatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import dev.digitalgnosis.dispatch.ui.theme.DispatchTheme
import org.junit.Rule

/**
 * Base class for Compose UI tests.
 *
 * Provides a [ComposeContentTestRule] pre-wired with [DispatchTheme] so individual
 * screen tests don't have to repeat theme setup.
 *
 * Usage:
 * ```kotlin
 * class MyScreenTest : BaseComposeTest() {
 *
 *     @Test
 *     fun `title is visible`() {
 *         setContent { MyScreen(onBack = {}) }
 *         composeTestRule.onNodeWithText("Title").assertIsDisplayed()
 *     }
 * }
 * ```
 *
 * Pattern: Bitwarden BaseComposeTest.kt
 */
abstract class BaseComposeTest {

    @get:Rule
    val composeTestRule: ComposeContentTestRule = createComposeRule()

    /**
     * Sets the content inside [DispatchTheme].
     * Call this at the start of each test instead of [ComposeContentTestRule.setContent].
     */
    fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            DispatchTheme {
                content()
            }
        }
    }
}
