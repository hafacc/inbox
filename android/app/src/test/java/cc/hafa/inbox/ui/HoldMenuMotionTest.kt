package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.menu.rowHoldWedges
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The hold menu grows in from small at the finger over a few frames, as on the web. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class HoldMenuMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val pin = HoldMenuItem("pin", "Pin", Icons.Outlined.PushPin)
    private val delete = HoldMenuItem("delete", "Delete", Icons.Outlined.Delete, HoldTone.DANGER)

    private fun deleteWidth(): Float = compose.onNodeWithContentDescription("Delete").fetchSemanticsNode().boundsInRoot.width

    @Test
    fun theDiscGrowsInFromSmallRatherThanAppearingWhole() {
        var state: HoldMenuState? = null
        compose.setContent {
            InboxTheme(darkTheme = false) {
                HoldMenuHost {
                    state = LocalHoldMenu.current
                    Text("List")
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val spec = HoldMenuSpec(Offset(100f, 400f), holdWedges(listOf(pin, delete), rowHoldWedges(listOf("pin", "delete"), 40f, 400f, 360f, 800f)), "Pay rent", false, "Todo actions", {})
        compose.runOnUiThread { state?.show(spec) }
        val full = with(compose.density) { 48.dp.toPx() }
        // The frame that adds the menu, then one frame at its smallest, then growing.
        val widths = (0 until 4).map {
            compose.mainClock.advanceTimeByFrame()
            runCatching { deleteWidth() }.getOrNull()
        }
        val smallest = widths.firstNotNullOf { it }
        assertEquals(full * 0.15f, smallest, 0.5f)
        val growing = widths.last() ?: 0f
        assertTrue("$widths of $full", growing > smallest && growing < full)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(full, deleteWidth(), 0.5f)
    }
}
