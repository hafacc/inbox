package cc.hafa.inbox.ui

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An open thread over its list: growing out of its row, following Back, and closing back into it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class ScreenMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private var opened by mutableStateOf<String?>(null)
    private var back: OnBackPressedDispatcher? = null
    private var backs = 0
    private var listShown = false

    private fun show() {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ThreadOverList(
                opened = opened,
                key = { it },
                list = {
                    DisposableEffect(Unit) {
                        listShown = true
                        onDispose { listShown = false }
                    }
                    Column {
                        Text("List")
                        Box(modifier = Modifier.fillMaxWidth().height(72.dp).openOrigin("a")) { Text("Row a") }
                    }
                },
                thread = { id ->
                    ScreenBackHandler {
                        backs++
                        opened = null
                    }
                    Text("Thread $id")
                },
            )
        }
    }

    private fun frames(millis: Long) {
        Snapshot.sendApplyNotifications()
        repeat((millis / FRAME_MS).toInt()) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun aThreadGrowsOverItsListThenTheListLeaves() {
        show()
        compose.mainClock.autoAdvance = false
        opened = "a"
        frames(FRAME_MS * 3)
        compose.onNodeWithText("Thread a").assertExists()
        assertTrue(listShown)
        frames(SETTLE_MS)
        compose.runOnIdle { assertFalse(listShown) }
    }

    @Test
    fun aClosingThreadStaysUntilItHasShrunkBackIntoItsRow() {
        opened = "a"
        show()
        compose.runOnIdle { assertFalse(listShown) }
        compose.mainClock.autoAdvance = false
        opened = null
        frames(FRAME_MS * 3)
        assertTrue(listShown)
        compose.onNodeWithText("Thread a").assertExists()
        frames(SETTLE_MS)
        compose.onNodeWithText("Thread a").assertDoesNotExist()
        compose.onNodeWithText("Row a").assertExists()
    }

    @Test
    fun aBackGestureShowsTheListAndCancellingKeepsTheThread() {
        opened = "a"
        show()
        val dispatcher = requireNotNull(back)
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 400f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue(listShown)
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("Thread a").assertExists()
        compose.runOnIdle { assertFalse(listShown) }
        assertEquals(0, backs)
    }

    @Test
    fun aFinishedBackGestureClosesTheThread() {
        opened = "a"
        show()
        val dispatcher = requireNotNull(back)
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.8f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals(1, backs)
        compose.onNodeWithText("Thread a").assertDoesNotExist()
        compose.onNodeWithText("Row a").assertExists()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val SETTLE_MS = 600L
    }
}
