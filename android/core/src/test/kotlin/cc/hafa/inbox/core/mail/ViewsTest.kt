package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.gmail.ListOptions
import cc.hafa.inbox.core.todo.LabelTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewsTest {
    @Test
    fun `each view has its own key and Gmail query`() {
        assertEquals("label:Label_1", View.Label("Label_1").key)
        assertEquals("inbox", View.INBOX.key)
        assertEquals(ListOptions(labelIds = listOf("INBOX")), listOptions(View.INBOX))
        assertNull(listOptions(View.Fixed(Mailbox.SNOOZED)))
        assertEquals(ListOptions(query = DONE_QUERY), listOptions(View.Fixed(Mailbox.DONE)))
        assertEquals("-in:inbox -in:spam -in:trash -in:sent -in:draft", DONE_QUERY)
        assertEquals(ListOptions(labelIds = listOf("SPAM"), includeSpamTrash = true), listOptions(View.Fixed(Mailbox.SPAM)))
        assertEquals(ListOptions(query = "from:maya"), listOptions(View.Search("from:maya")))
        assertNull(listOptions(View.Search("is:todo milk")))
        assertEquals(ListOptions(query = ""), listOptions(View.Search("-is:todo")))
    }

    @Test
    fun `a search's #name naming a label runs as its label term`() {
        val labels = LabelTags(listOf(GmailLabel("L1", "Home")))
        val view = View.Search("#Home milk")
        assertEquals(ListOptions(query = "label:home milk"), listOptions(view, labels))
        assertNull(listOptions(view))
        assertEquals(FilteredView.Search("label:home milk"), view.filtered(labels))
        assertEquals("search:#Home milk", view.key)
    }
}
