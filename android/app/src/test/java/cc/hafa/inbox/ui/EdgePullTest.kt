package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pulling an open thread past its top or end closes it, with a tick at the threshold and one on release. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class EdgePullTest {
    @get:Rule
    val compose = createComposeRule()

    private var closes = 0
    private val haptics = mutableListOf<HapticFeedbackType>()

    private fun show(firstShown: Int = 0) {
        val listState = LazyListState(firstVisibleItemIndex = firstShown)
        compose.setContent {
            val recorder = object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                    haptics += hapticFeedbackType
                }
            }
            CompositionLocalProvider(LocalHapticFeedback provides recorder) {
                val pull = rememberEdgePull { closes++ }
                Box(Modifier.fillMaxSize().nestedScroll(pull).testTag("thread")) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(30) { index -> Text("Message $index", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                }
            }
        }
    }

    @Test
    fun aLongPullDownFromTheTopCloses() {
        show()
        compose.onNodeWithTag("thread").performTouchInput { swipeDown(startY = top + 10f, endY = top + 500f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, closes)
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate, HapticFeedbackType.GestureEnd), haptics)
    }

    @Test
    fun aShortPullSpringsBack() {
        show()
        compose.onNodeWithTag("thread").performTouchInput { swipeDown(startY = top + 10f, endY = top + 80f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, closes)
        assertEquals(emptyList<HapticFeedbackType>(), haptics)
    }

    @Test
    fun aLongPullUpFromTheEndCloses() {
        show(firstShown = 29)
        compose.onNodeWithTag("thread").performTouchInput { swipeUp(startY = bottom - 10f, endY = bottom - 500f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun aScrollThatReachesTheTopDoesNotClose() {
        show(firstShown = 2)
        compose.onNodeWithTag("thread").performTouchInput { swipeDown(startY = top + 10f, endY = top + 700f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(0, closes)
    }
}
