package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class LabelGroupsTest {
    private data class Item(val id: String, val labels: List<String>)

    private fun thread(id: String, vararg labels: String) = Item(id, labels.toList())

    private fun shape(entries: List<ListEntry<Item>>): List<String> = entries.map { entry ->
        when (entry) {
            is ListEntry.Single -> entry.thread.id
            is ListEntry.Group -> "${entry.labelId}[${entry.threads.joinToString(",") { it.id }}]"
        }
    }

    private fun group(threads: List<Item>, userLabels: List<String>) = shape(groupByLabel(threads, { it.labels }, userLabels))

    @Test
    fun `three threads with a user label fold into one group where the newest was`() {
        val threads = listOf(
            thread("a"),
            thread("b", "github"),
            thread("c"),
            thread("d", "github"),
            thread("e", "github", "INBOX"),
        )
        assertEquals(listOf("a", "github[b,d,e]", "c"), group(threads, listOf("github")))
    }

    @Test
    fun `one thread stays a row, two fold`() {
        assertEquals(listOf("a"), group(listOf(thread("a", "receipts")), listOf("receipts")))
        val threads = listOf(thread("a", "receipts"), thread("b", "receipts"))
        assertEquals(listOf("receipts[a,b]"), group(threads, listOf("receipts")))
    }

    @Test
    fun `a thread with two labels joins the larger group, ties go to the earlier label`() {
        val threads = listOf(
            thread("a", "family", "home"),
            thread("b", "home"),
            thread("c", "home"),
            thread("d", "family"),
            thread("f", "home"),
        )
        assertEquals(listOf("home[a,b,c,f]", "d"), group(threads, listOf("family", "home")))
        val tied = listOf(
            thread("a", "home", "family"),
            thread("b", "home"),
            thread("d", "family"),
        )
        assertEquals(listOf("family[a,d]", "b"), group(tied, listOf("family", "home")))
    }

    @Test
    fun `a label left too small after assignment gives its threads back and others regroup`() {
        // "big" takes a, b and c; "small" keeps only d, so it stays a row.
        val threads = listOf(
            thread("a", "big", "small"),
            thread("b", "big"),
            thread("c", "big", "small"),
            thread("d", "small"),
            thread("f", "big"),
        )
        assertEquals(listOf("big[a,b,c,f]", "d"), group(threads, listOf("big", "small")))
    }

    @Test
    fun `system labels never group`() {
        val threads = listOf(thread("a", "INBOX"), thread("b", "INBOX"), thread("c", "INBOX"))
        assertEquals(listOf("a", "b", "c"), group(threads, listOf("github")))
    }

    @Test
    fun `equal threads still land in their own places`() {
        val same = thread("x", "L")
        assertEquals(listOf("L[x,x,x]"), group(listOf(same, same, same), listOf("L")))
    }
}
