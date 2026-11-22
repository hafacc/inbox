package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.menu.CornerSide
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A round corner button: a tap, a slide to a wedge, a tap that keeps its menu open, and a drag down then across to the
 * other corner, where it comes to rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class CornerButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val done = HoldMenuItem("done", "Done", Icons.Outlined.Check, HoldTone.DONE)
    private val snooze = HoldMenuItem("snooze", "Snooze", Icons.Outlined.Schedule)

    private var menuState: HoldMenuState? = null
    private var side = CornerSide.RIGHT

    private fun show(withMenu: Boolean, onTap: () -> Unit = {}, picked: MutableList<String> = mutableListOf()) {
        compose.setContent {
            var shownSide by remember { mutableStateOf(side) }
            InboxTheme(darkTheme = false) {
                HoldMenuHost {
                    menuState = LocalHoldMenu.current
                    CompositionLocalProvider(
                        LocalCornerPlacement provides CornerPlacement(shownSide) {
                            side = it
                            shownSide = it
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            CornerButton(
                                label = if (withMenu) "Thread actions" else "Compose",
                                icon = if (withMenu) Icons.Outlined.MoreVert else Icons.Outlined.Edit,
                                menu = if (withMenu) {
                                    { CornerMenu(listOf(done, snooze), emptyList()) { picked += it.id } }
                                } else {
                                    null
                                },
                                onTap = onTap,
                                modifier = Modifier.align(cornerAlignment(shownSide)).padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aTapOnComposeComposes() {
        var taps = 0
        show(withMenu = false, onTap = { taps += 1 })
        compose.onNodeWithContentDescription("Compose").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun slidingUpToAWedgeAndLettingGoPicksIt() {
        val picked = mutableListOf<String>()
        show(withMenu = true, picked = picked)
        compose.onNodeWithContentDescription("Thread actions").performTouchInput {
            down(center)
            // Straight up from the button, on the inner ring: the first wedge, Done.
            moveBy(Offset(0f, -80.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("done"), picked)
        assertNull(menuState?.spec)
    }

    @Test
    fun aTapKeepsTheMenuOpenForATap() {
        val picked = mutableListOf<String>()
        show(withMenu = true, picked = picked)
        compose.onNodeWithContentDescription("Thread actions").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()
        assertTrue(menuState?.sticky == true)
        compose.onNodeWithContentDescription("Snooze").performClick()
        compose.waitForIdle()
        assertEquals(listOf("snooze"), picked)
    }

    @Test
    fun draggingDownLiftsTheButtonAndLettingGoOnTheLeftMovesItThere() {
        show(withMenu = true)
        val button = compose.onNodeWithContentDescription("Thread actions")
        val rightEdge = button.fetchSemanticsNode().boundsInRoot.right
        button.performTouchInput {
            down(center)
            moveBy(Offset(0f, 30.dp.toPx()))
        }
        compose.waitForIdle()
        assertNull(menuState?.spec)
        button.performTouchInput {
            moveBy(Offset(-300.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(CornerSide.LEFT, side)
        val left = compose.onNodeWithContentDescription("Thread actions").fetchSemanticsNode().boundsInRoot
        assertTrue("$left vs $rightEdge", left.right < rightEdge / 2)
    }

    @Test
    fun lettingGoSnapsTheButtonIntoTheCornerItLandsIn() {
        show(withMenu = true)
        val button = compose.onNodeWithContentDescription("Thread actions")
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        button.performTouchInput {
            down(center)
            moveBy(Offset(0f, 30.dp.toPx()))
            moveBy(Offset(-220.dp.toPx(), 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(CornerSide.LEFT, side)
        // At rest in the left corner, its 16 dp margin from the edge, wherever the finger let go.
        val bounds = compose.onNodeWithContentDescription("Thread actions").fetchSemanticsNode().boundsInRoot
        val margin = with(compose.density) { 16.dp.toPx() }
        assertEquals(margin, bounds.left, 1f)
        assertTrue("$bounds in $width", bounds.right < width / 2)
    }

    @Test
    fun aSlideThatReachedAWedgeNeverDrags() {
        val picked = mutableListOf<String>()
        show(withMenu = true, picked = picked)
        compose.onNodeWithContentDescription("Thread actions").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80.dp.toPx()))
            moveBy(Offset(0f, 120.dp.toPx()))
            up()
        }
        compose.waitForIdle()
        assertEquals(CornerSide.RIGHT, side)
        assertEquals(emptyList<String>(), picked)
    }
}
