package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.mail.Mailbox
import cc.hafa.inbox.core.mail.View
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The view's name in the top bar: a tap goes to the top, a hold opens the view menu around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class ViewNameTest {
    @get:Rule
    val compose = createComposeRule()

    private var tops = 0
    private val picked = mutableListOf<View>()
    private var menu: HoldMenuState? = null

    private fun show(current: View, todosOn: Boolean = true) {
        compose.setContent {
            InboxTheme(darkTheme = false) {
                HoldMenuHost {
                    menu = LocalHoldMenu.current
                    Box(Modifier.padding(start = 56.dp, top = 12.dp)) {
                        ViewName("Inbox", current = current, todosOn = todosOn, onTop = { tops++ }, onPick = { picked += it })
                    }
                }
            }
        }
    }

    @Test
    fun aTapGoesToTheTopAndOpensNoMenu() {
        show(View.INBOX)
        compose.onNodeWithContentDescription("Inbox, go to top").performClick()
        compose.waitForIdle()
        assertEquals(1, tops)
        assertNull(menu?.spec)
    }

    @Test
    fun aHoldOpensTheViewsDownFromTheNameWithTheCurrentOneMarked() {
        show(View.Fixed(Mailbox.DONE))
        compose.onNodeWithContentDescription("Inbox, go to top").performTouchInput { longClick() }
        compose.waitForIdle()
        val spec = menu?.spec
        assertEquals(listOf("inbox", "snoozed", "done", "todos"), spec?.wedges?.map { it.item.id })
        assertEquals(listOf(HoldTone.PLAIN, HoldTone.PLAIN, HoldTone.ON, HoldTone.PLAIN), spec?.wedges?.map { it.item.tone })
        assertTrue(spec?.wedges.orEmpty().all { it.arc.from >= 240f && it.arc.to <= 360f })
        assertEquals(0, tops)
        // Letting go in place keeps it open for a tap.
        assertTrue(menu?.sticky == true)
        assertEquals("Tap a view", spec?.stickyPill)
    }

    @Test
    fun pickingAnotherViewSwitchesAndPickingTheCurrentOneGoesToTheTop() {
        show(View.INBOX, todosOn = false)
        val name = compose.onNodeWithContentDescription("Inbox, go to top")
        name.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(listOf("inbox", "snoozed", "done"), menu?.spec?.wedges?.map { it.item.id })
        compose.runOnUiThread { menu?.pick(1) }
        assertEquals(listOf<View>(View.Fixed(Mailbox.SNOOZED)), picked)
        name.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.runOnUiThread { menu?.pick(0) }
        assertEquals(1, tops)
        assertEquals(1, picked.size)
    }
}
