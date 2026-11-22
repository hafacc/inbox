package cc.hafa.inbox.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

/** An address field's edits: backspace in the empty field after chips, a separator, and typing. */
class AddressEditTest {
    @Test
    fun backspaceInTheEmptyFieldAfterChipsTakesOffTheLastRecipient() {
        assertEquals(AddressEdit.RemoveLast, addressEdit(ADDRESS_SENTINEL, TextFieldValue("")))
    }

    @Test
    fun backspaceInAFieldWithNoChipsDoesNothing() {
        assertEquals(AddressEdit.Type(TextFieldValue("")), addressEdit("", TextFieldValue("")))
    }

    @Test
    fun typingAfterTheSentinelLeavesItOut() {
        val typed = addressEdit(ADDRESS_SENTINEL, TextFieldValue(ADDRESS_SENTINEL + "a", TextRange(2), TextRange(1, 2)))
        assertEquals(AddressEdit.Type(TextFieldValue("a", TextRange(1), TextRange(0, 1))), typed)
    }

    @Test
    fun aSeparatorFinishesTheAddress() {
        assertEquals(AddressEdit.Finish("maya@example.com,"), addressEdit("maya@example.com", TextFieldValue("maya@example.com,")))
    }
}
