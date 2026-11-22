package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The todo editor opened from a menu, as the open email's Add todo does, and from a tap on the text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class TodoEditorFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anEditorOpenedFromAMenuKeepsItsFocus() {
        val blurs = mutableListOf<String>()
        compose.setContent {
            var menu by remember { mutableStateOf(false) }
            var editing by remember { mutableStateOf(false) }
            Column {
                Box {
                    TextButton(onClick = { menu = true }) { Text("More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add todo") },
                            onClick = {
                                menu = false
                                editing = true
                            },
                        )
                    }
                }
                if (editing) {
                    TodoEditor(
                        initial = "",
                        onChange = {},
                        onEnter = { false },
                        onBlur = { blurs += it },
                        suggest = { _, _ -> emptyList() },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Black,
                        label = "Todo",
                    )
                }
            }
        }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Add todo").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Todo").assertIsFocused()
        assertEquals(emptyList<String>(), blurs)
    }

    @Test
    fun anEditorOpenedBeforeItsWindowHasFocusKeepsItOnceTheWindowDoes() {
        val blurs = mutableListOf<String>()
        var windowFocused by mutableStateOf(false)
        compose.setContent {
            val base = LocalWindowInfo.current
            val window = remember(base) {
                object : WindowInfo by base {
                    override val isWindowFocused: Boolean get() = windowFocused
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                TodoEditor(
                    initial = "",
                    onChange = {},
                    onEnter = { false },
                    onBlur = { blurs += it },
                    suggest = { _, _ -> emptyList() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Black,
                    label = "Todo",
                )
            }
        }
        compose.onNodeWithContentDescription("Todo").assertIsFocused()
        windowFocused = true
        compose.onNodeWithContentDescription("Todo").assertIsFocused()
        assertEquals(emptyList<String>(), blurs)
    }

    @Test
    fun theKeyboardsActionKeyFollowsWhetherEnterContinuesAndNeverChangesWhileTyping() {
        var continues by mutableStateOf(true)
        compose.setContent {
            TodoEditor(
                initial = "",
                onChange = {},
                onEnter = { true },
                onBlur = {},
                suggest = { _, _ -> emptyList() },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black,
                label = "Todo",
                continues = continues,
            )
        }
        val field = compose.onNodeWithContentDescription("Todo")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field.performTextInput("Pay rent")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        field.performTextClearance()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Next))
        continues = false
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
        field.performTextInput("Pay rent")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Done))
    }

    @Test
    fun aTapOnTheTextOpensTheEditorWithTheCursorWhereItLanded() {
        compose.setContent {
            var editing by remember { mutableStateOf(false) }
            var caret by remember { mutableStateOf<Int?>(null) }
            if (editing) {
                TodoEditor(
                    initial = "hello world",
                    onChange = {},
                    onEnter = { false },
                    onBlur = {},
                    suggest = { _, _ -> emptyList() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Black,
                    label = "Todo",
                    caret = caret,
                )
            } else {
                TodoText(
                    "hello world",
                    MaterialTheme.typography.bodyLarge,
                    Color.Black,
                    Modifier.clickable { editing = true },
                    onPress = { caret = it },
                )
            }
        }
        compose.onNodeWithText("hello world").performTouchInput { click(centerLeft + Offset(1f, 0f)) }
        compose.onNodeWithContentDescription("Todo").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0)))
    }

    @Test
    fun anEditorOpenedWithoutATapPutsTheCursorAtTheEnd() {
        compose.setContent {
            TodoEditor(
                initial = "hello world",
                onChange = {},
                onEnter = { false },
                onBlur = {},
                suggest = { _, _ -> emptyList() },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black,
                label = "Todo",
            )
        }
        compose.onNodeWithContentDescription("Todo").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(11)))
    }
}
