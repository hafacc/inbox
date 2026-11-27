package cc.hafa.inbox.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.ui.geometry.Offset
import cc.hafa.inbox.core.menu.rowHoldWedges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The round menu: where a finger lands in it, and how letting go picks, waits or closes. */
class HoldMenuTest {
    private val pin = HoldMenuItem("pin", "Pin", Icons.Outlined.PushPin)
    private val delete = HoldMenuItem("delete", "Delete", Icons.Outlined.Delete, HoldTone.DANGER)

    @Test
    fun anEmptySlotLeavesOnlyTheOtherWedge() {
        assertEquals(listOf(delete), holdWedges(listOf(pin, delete), rowHoldWedges(listOf(null, "delete"), 40f, 400f, 360f)).map { it.item })
    }

    @Test
    fun lettingGoInAWedgePicksItAndLettingGoInPlaceWaitsForATap() {
        val picked = mutableListOf<String>()
        val state = HoldMenuState()
        val spec = HoldMenuSpec(Offset(100f, 300f), holdWedges(listOf(pin, delete), rowHoldWedges(listOf("pin", "delete"), 40f, 300f, 360f)), "Pay rent", false, "Todo actions", { picked += it.id })
        state.show(spec)
        state.move(Offset(170f, 300f))
        assertEquals(1, state.active)
        state.release(Offset(170f, 300f))
        assertNull(state.spec)
        assertEquals(listOf("delete"), picked)

        state.show(spec)
        state.release(Offset(104f, 302f))
        assertTrue(state.sticky)
        state.pick(0)
        assertEquals(listOf("delete", "pin"), picked)

        state.show(spec)
        state.release(Offset(400f, 700f))
        assertNull(state.spec)
        assertEquals(listOf("delete", "pin"), picked)
    }

    @Test
    fun lettingGoWhereThePressBeganWaitsForATapThoughTheDiscSitsElsewhere() {
        val state = HoldMenuState()
        val spec = HoldMenuSpec(Offset(100f, 300f), holdWedges(listOf(pin, delete), rowHoldWedges(listOf("pin", "delete"), 40f, 300f, 360f)), "Pay rent", false, "Todo actions", {})
        // A link's disc lifted above a finger near the screen's bottom: let go where it pressed, off every wedge.
        state.show(spec)
        state.release(Offset(100f, 320f), nearPress = true)
        assertTrue(state.sticky)

        state.show(spec)
        state.release(Offset(100f, 320f), nearPress = false)
        assertNull(state.spec)
    }

    @Test
    fun closingWithAFadeKeepsTheMenuDrawnUntilItFades() {
        val state = HoldMenuState()
        val spec = HoldMenuSpec(Offset(100f, 300f), holdWedges(listOf(pin, delete), rowHoldWedges(listOf("pin", "delete"), 40f, 300f, 360f)), "Pay rent", false, "Todo actions", {})
        state.show(spec)
        state.close(fade = true)
        assertNull(state.spec)
        assertEquals(spec, state.fading)
        state.show(spec)
        assertNull(state.fading)
    }
}
