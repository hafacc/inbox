package cc.hafa.inbox.data

import cc.hafa.inbox.core.mail.AccountChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParkedWorkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun draft(localId: String, dirty: Boolean = false, pending: String? = null) =
        DraftEntity(localId, "sealed:abc", dirty = dirty, deleted = false, pendingSendJson = pending, updatedAt = 1)

    private val change = QueueEntity(3, "t1", "[\"INBOX\"]", "[]", fromNotification = false)
    private val accountChanges = listOf(
        AccountChange.CreateLabel(1, "local:l1", "Trips"),
        AccountChange.PatchLabel(2, "local:l1", name = "Travel"),
    )

    @Test
    fun `keeps unsent drafts with their files and queued changes for the same account`() {
        val parked = ParkedWork(File(folder.root, "parked"))
        val drafts = folder.newFolder("drafts")
        File(drafts, "d1").mkdirs()
        File(drafts, "d1/f1").writeText("sealed bytes")
        File(drafts, "d2").mkdirs()
        val kept = listOf(draft("d1", pending = "{}"), draft("d2"), draft("d3", dirty = true))

        assertTrue(parked.park("u1", kept, listOf(change), accountChanges, emptyList(), drafts))
        assertEquals("u1", parked.parkedUid())

        val restored = folder.newFolder("restored")
        val back = parked.unpark("u1", restored)!!
        assertEquals(listOf("d1", "d3"), back.drafts.map { it.localId })
        assertEquals(listOf(change), back.queue)
        assertEquals(accountChanges, back.accountQueue)
        assertEquals("sealed bytes", File(restored, "d1/f1").readText())
        assertFalse(File(restored, "d2").exists())
        // Kept until the caller has written the work back.
        assertEquals("u1", parked.parkedUid())
    }

    @Test
    fun `keeps queued label and filter edits on their own`() {
        val parked = ParkedWork(File(folder.root, "parked"))
        assertTrue(parked.park("u1", emptyList(), emptyList(), accountChanges, emptyList(), folder.newFolder("drafts")))
        assertEquals(accountChanges, parked.unpark("u1", folder.newFolder("restored"))!!.accountQueue)
    }

    @Test
    fun `keeps label renames the todos' tags haven't followed yet on their own`() {
        val parked = ParkedWork(File(folder.root, "parked"))
        val retags = listOf(TagRename("Trips", "Travel"), TagRename("Travel", "Away"))
        assertTrue(parked.park("u1", emptyList(), emptyList(), emptyList(), retags, folder.newFolder("drafts")))
        assertEquals(retags, parked.unpark("u1", folder.newFolder("restored"))!!.retags)
    }

    @Test
    fun `drops work kept for another account`() {
        val parked = ParkedWork(File(folder.root, "parked"))
        parked.park("u1", emptyList(), listOf(change), accountChanges, emptyList(), folder.newFolder("drafts"))
        assertNull(parked.unpark("u2", folder.newFolder("restored")))
        assertNull(parked.parkedUid())
    }

    @Test
    fun `keeps nothing when nothing is unsent`() {
        val parked = ParkedWork(File(folder.root, "parked"))
        assertFalse(parked.park("u1", listOf(draft("d1")), emptyList(), emptyList(), emptyList(), folder.newFolder("drafts")))
        assertNull(parked.parkedUid())
    }
}
