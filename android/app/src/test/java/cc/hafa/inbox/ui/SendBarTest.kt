package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cc.hafa.inbox.mail.SendNotice
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sending pill offers Undo only when given what undoing does, and View on a failed send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class SendBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(notice: SendNotice, undoable: Boolean) {
        compose.setContent {
            InboxTheme(darkTheme = false) {
                SendBar(
                    notice = notice,
                    onUndo = if (undoable) ({ calls += "undo" }) else null,
                    onView = { calls += "view" },
                    onOpen = {},
                )
            }
        }
    }

    @Test
    fun aDiscardedDraftOffersUndo() {
        show(SendNotice.Discarded("l1"), undoable = true)
        compose.onNodeWithText("Undo").performClick()
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun aSentMessageOffersNothing() {
        show(SendNotice.Sent, undoable = false)
        compose.onNodeWithText("Sent").assertExists()
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithText("View").assertDoesNotExist()
    }

    @Test
    fun aFailedSendOffersView() {
        show(SendNotice.Failed("Couldn't send."), undoable = false)
        compose.onNodeWithText("View").performClick()
        assertEquals(listOf("view"), calls)
    }
}
