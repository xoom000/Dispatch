package dev.digitalgnosis.dispatch.ui.components

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * GAP-S1: Keyboard incognito mode.
 *
 * Sets IME_FLAG_NO_PERSONALIZED_LEARNING on every text field inside this wrapper.
 * Prevents the system keyboard (Gboard, etc.) from learning words typed in Dispatch
 * and sending them to cloud suggestion engines.
 *
 * Pattern sourced from Bitwarden Android (NoPersonalizedLearningInterceptor) and
 * the Google workaround at https://issuetracker.google.com/issues/359257538#comment2.
 *
 * Usage:
 *   IncognitoInput {
 *       TextField(...)   // keyboard will be in incognito mode
 *   }
 *
 * Apply at a high level (e.g. wrapping all screens) or per text field.
 */
@OptIn(ExperimentalComposeUiApi::class)
private object NoPersonalizedLearningInterceptor : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing {
        val noPersonalizedRequest = PlatformTextInputMethodRequest { outAttrs ->
            request.createInputConnection(outAttrs).also {
                outAttrs.imeOptions =
                    outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        }
        nextHandler.startInputMethod(noPersonalizedRequest)
    }
}

/**
 * Wraps [content] so that all text fields within it run in keyboard incognito mode.
 * The keyboard will not suggest, correct, or learn from input.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun IncognitoInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = NoPersonalizedLearningInterceptor,
        content = content,
    )
}
