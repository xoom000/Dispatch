package dev.digitalgnosis.dispatch.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import dev.digitalgnosis.dispatch.ui.BaseComposeTest
import dev.digitalgnosis.dispatch.ui.components.bubbles.BubbleRichContent
import dev.digitalgnosis.dispatch.ui.theme.DispatchTheme
import org.junit.Test

/**
 * Compose UI tests for the Messages screen components.
 *
 * Note: [MessagesScreen] depends on [hiltViewModel] and requires a full Hilt graph
 * to test end-to-end. These tests focus on composable sub-components that can be
 * exercised without the full DI graph:
 *   - [BubbleRichContent] — renders message text with link/image detection
 *
 * Full MessagesScreen integration tests require @HiltAndroidTest + a custom test module
 * that binds fake repositories. See the task backlog for GAP-T3.
 */
class MessagesScreenTest : BaseComposeTest() {

    @Test
    fun bubbleRichContent_displaysPlainText() {
        val snackbar = SnackbarHostState()

        setContent {
            BubbleRichContent(
                text = "Hello, world!",
                primary = false,
                textColor = androidx.compose.ui.graphics.Color.Black,
                snackbarHostState = snackbar,
            )
        }

        composeTestRule.onNodeWithText("Hello, world!").assertIsDisplayed()
    }

    @Test
    fun bubbleRichContent_displaysOutgoingText() {
        val snackbar = SnackbarHostState()

        setContent {
            BubbleRichContent(
                text = "Outgoing message from Nigel",
                primary = true,
                textColor = androidx.compose.ui.graphics.Color.White,
                snackbarHostState = snackbar,
            )
        }

        composeTestRule.onNodeWithText("Outgoing message from Nigel").assertIsDisplayed()
    }

    @Test
    fun bubbleRichContent_displaysMultilineText() {
        val snackbar = SnackbarHostState()
        val multiline = "Line one\nLine two\nLine three"

        setContent {
            BubbleRichContent(
                text = multiline,
                primary = false,
                textColor = androidx.compose.ui.graphics.Color.Black,
                snackbarHostState = snackbar,
            )
        }

        // Compose renders the full text in a single ClickableText node
        composeTestRule.onNodeWithText(multiline).assertIsDisplayed()
    }
}
