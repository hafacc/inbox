package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.menu.HOLD_MS
import cc.hafa.inbox.core.menu.rowHoldWedges
import cc.hafa.inbox.ui.theme.InboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A hold that opens a round menu owns the finger: letting go doesn't also tap what is under it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp", application = Application::class)
class HoldGestureTest {
    @get:Rule
    val compose = createComposeRule()

    private val pin = HoldMenuItem("pin", "Pin", Icons.Outlined.PushPin)
    private val delete = HoldMenuItem("delete", "Delete", Icons.Outlined.Delete, HoldTone.DANGER)

    @Test
    fun aHoldOnARowsAvatarOpensTheMenuWithoutTappingTheAvatar() {
        var taps = 0
        var state: HoldMenuState? = null
        compose.setContent {
            InboxTheme(darkTheme = false) {
                HoldMenuHost {
                    val menu = LocalHoldMenu.current!!
                    state = menu
                    val haptics = LocalHapticFeedback.current
                    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    Box(
                        Modifier
                            .size(300.dp)
                            .onGloballyPositioned { placed = it }
                            .pointerInput(menu) {
                                holdMenuGestures(
                                    menu,
                                    haptics,
                                    claim = false,
                                    onTap = null,
                                    open = {
                                        menu.show(HoldMenuSpec(Offset(40f, 400f), holdWedges(listOf(pin, delete), rowHoldWedges(listOf("pin", "delete"), 40f, 400f, 360f)), "Row", false, "Email actions", {}))
                                        true
                                    },
                                    throughChildren = true,
                                ) { placed }
                            },
                    ) {
                        Box(Modifier.size(48.dp).testTag("avatar").clickable { taps++ })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("avatar").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_MS + 100)
        compose.onNodeWithTag("avatar").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(state?.sticky == true)
        assertEquals(0, taps)
    }
}
