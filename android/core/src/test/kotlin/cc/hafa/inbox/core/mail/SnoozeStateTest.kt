package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private val fixtureJson = Json { ignoreUnknownKeys = true }

/** Runs `shared/testdata/thread-snooze-fixtures.json`, so snoozes end and sort as on web. */
class SnoozeStateTest {
    @Serializable
    private data class Doc(val snoozeUntil: Long?, val snoozeWatermark: String?) {
        fun toSnoozeDoc() = SnoozeDoc(snoozeUntil, snoozeWatermark)
    }

    @Serializable
    private data class StatusCase(val name: String, val doc: Doc?, val now: Long, val hasNewerMessage: Boolean, val expect: String)

    @Serializable
    private data class SortDateCase(val name: String, val lastDate: Long, val doc: Doc?, val status: String, val expect: Long)

    @Serializable
    private data class ArchivedCase(
        val name: String,
        val doc: Doc?,
        val gmailLabelIds: List<String>,
        val gmailHistoryId: String?,
        val now: Long,
        val hasLocalChange: Boolean,
        val expect: Boolean,
    )

    @Serializable
    private data class NewerMailCase(
        val name: String,
        val watermarks: Map<String, String>,
        val records: List<GmailHistoryRecord>,
        val inInbox: List<String>,
        val skippedInbox: List<String>,
    )

    @Serializable
    private data class SnoozeFixtures(
        val status: List<StatusCase>,
        val sortDate: List<SortDateCase>,
        val archivedElsewhere: List<ArchivedCase>,
        val newerMail: List<NewerMailCase>,
    )

    private val fixtures: SnoozeFixtures = run {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("thread-snooze-fixtures.json")) {
            "testdata/thread-snooze-fixtures.json missing from test resources"
        }.bufferedReader().use { it.readText() }
        fixtureJson.decodeFromString<SnoozeFixtures>(text)
    }

    private fun status(wire: String): SnoozeStatus = SnoozeStatus.valueOf(wire.uppercase())

    @Test
    fun status() {
        for (case in fixtures.status) {
            assertEquals(case.name, status(case.expect), snoozeStatus(case.doc?.toSnoozeDoc(), case.now, case.hasNewerMessage))
        }
    }

    @Test
    fun sortDate() {
        for (case in fixtures.sortDate) {
            assertEquals(case.name, case.expect, inboxSortDate(case.lastDate, case.doc?.toSnoozeDoc(), status(case.status)))
        }
    }

    @Test
    fun archivedElsewhere() {
        for (case in fixtures.archivedElsewhere) {
            assertEquals(
                case.name,
                case.expect,
                snoozeArchivedElsewhere(case.doc?.toSnoozeDoc(), case.gmailLabelIds, case.gmailHistoryId, case.now, case.hasLocalChange),
            )
        }
    }

    @Test
    fun newerMail() {
        for (case in fixtures.newerMail) {
            val result = newerMail(case.records, case.watermarks)
            assertEquals("${case.name}: inInbox", case.inInbox, result.inInbox.sorted())
            assertEquals("${case.name}: skippedInbox", case.skippedInbox, result.skippedInbox.sorted())
        }
    }
}
