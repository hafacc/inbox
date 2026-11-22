package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
/**
 * The search field's completions: when they open (typing, backspacing a chip), and that selecting
 * text doesn't touch them; its chips, its cross, and what a turn of the phone keeps.
 */
class SearchBarTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(initial: String = "") {
        compose.setContent {
            SearchBar(initial, labels = emptyList(), contacts = emptyList(), now = 0L, onSearch = {}, onClose = {})
        }
    }

    @Test
    fun completionsOpenOnlyOnceTypingPauses() {
        show()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Search mail").performTextInput("s")
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText("subject:").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search mail").performTextInput("u")
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("subject:").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("subject:").assertExists()
    }

    @Test
    fun selectingTextLeavesCompletionsClosedAndTheSelectionAsIs() {
        show("su")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Search mail").performTextInputSelection(TextRange(0, 2))
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("subject:").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 2)))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backspacingAChipIntoTextOpensCompletions() {
        show("is:unread su")
        compose.mainClock.autoAdvance = false
        val field = compose.onNodeWithContentDescription("Search mail")
        field.performClick()
        field.performTextInputSelection(TextRange(0))
        field.performKeyInput { pressKey(Key.Backspace) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("unread").assertExists()
    }

    @Test
    fun tappingAChipKeepsItAndOnlyItsCrossRemovesIt() {
        show("is:unread su")
        compose.onNodeWithText("unread").performClick()
        compose.onNodeWithText("unread").assertExists()
        compose.onNodeWithContentDescription("Search mail").assertIsFocused()
        compose.onNodeWithContentDescription("Remove is:unread").performClick()
        compose.onNodeWithText("unread").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("su")))
    }

    @Test
    fun theCrossEmptiesTheFieldAndKeepsSearchOpenAndFocused() {
        var closed = false
        compose.setContent {
            SearchBar("is:unread su", labels = emptyList(), contacts = emptyList(), now = 0L, onSearch = {}, onClose = { closed = true })
        }
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("unread").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search mail").assertIsFocused()
        compose.onNodeWithContentDescription("Search mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertFalse("the cross closed search", closed) }
    }

    @Test
    fun aTurnOfThePhoneKeepsWhatWasTyped() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            SearchBar("", labels = emptyList(), contacts = emptyList(), now = 0L, onSearch = {}, onClose = {})
        }
        compose.onNodeWithContentDescription("Search mail").performTextInput("is:unread lea")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("unread").assertExists()
        compose.onNodeWithContentDescription("Search mail").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("\u200Blea")))
    }
}
