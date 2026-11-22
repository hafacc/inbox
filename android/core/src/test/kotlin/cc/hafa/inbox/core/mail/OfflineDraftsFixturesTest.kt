package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.send.GmailDraftRef
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.SendingDraft
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/offline-draft-fixtures.json`, so drafts list and open offline as on web. */
class OfflineDraftsFixturesTest {
    private fun saved(value: Any?): List<SavedDraftMessage> {
        val threads = (value as Map<*, *>).entries.associate { (threadId, messages) ->
            threadId as String to (messages as Map<*, *>).entries.associate { (messageId, labels) ->
                messageId as String to (labels as List<*>).map { it as String }
            }
        }
        return savedDraftMessages(threads)
    }

    private fun ref(value: Any?): GmailDraftRef {
        val ref = value as Map<*, *>
        return GmailDraftRef(ref["draftId"] as String, ref["messageId"] as String, ref["threadId"] as String)
    }

    private fun refs(value: Any?): List<GmailDraftRef> = (value as List<*>?).orEmpty().map(::ref)

    private fun docs(value: Any?): List<OutboxDoc> = (value as List<*>?).orEmpty().mapIndexed { index, it ->
        val doc = it as Map<*, *>
        OutboxDoc("o$index", doc["draftId"] as String, "me@example.com", OutboxStatus.of(doc["status"] as String), 0, null, doc["threadId"] as String?)
    }

    private fun known(value: Any?): SavedDraftRefs {
        val known = value as Map<*, *>
        return SavedDraftRefs(refs(known["refs"]), (known["unmatched"] as List<*>).map { it as String })
    }

    private fun input(case: Map<String, Any?>) = OfflineDraftsInput(
        saved = saved(case["saved"]),
        refs = refs(case["refs"]),
        docs = docs(case["docs"]),
        sending = (case["sending"] as List<*>?).orEmpty().map {
            val send = it as Map<*, *>
            SendingDraft(send["draftId"] as String?, send["messageId"] as String?, send["threadId"] as String?)
        },
        sent = refs(case["sent"]),
        local = (case["local"] as List<*>?).orEmpty().map {
            val draft = it as Map<*, *>
            OfflineLocalDraft(
                draft["localId"] as String,
                draft["threadId"] as String?,
                draft["messageId"] as String?,
                LocalDraftState.valueOf((draft["state"] as String).uppercase()),
            )
        },
    )

    @Test
    fun listing() {
        val cases = Fixtures.cases(Fixtures.offlineDrafts, "listing")
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case["name"] as String
            val saved = saved(case["saved"])
            val known = known(case["known"])
            val wanted = draftsListingWanted(saved, known)
            assertEquals(name, case["wanted"], wanted)
            val after = if (wanted) afterDraftsListing(refs(case["listed"]), saved) else known
            assertEquals(name, known(case["after"]), after)
        }
    }

    @Test
    fun sent() {
        for (case in Fixtures.cases(Fixtures.offlineDrafts, "sent")) {
            val result = sentDrafts(refs(case["previous"]), docs(case["docs"]), refs(case["known"]), saved(case["saved"]))
            assertEquals(case["name"] as String, refs(case["expect"]), result)
        }
    }

    @Test
    fun threads() {
        for (case in Fixtures.cases(Fixtures.offlineDrafts, "threads")) {
            assertEquals(case["name"] as String, case["expect"], offlineDraftThreads(input(case)))
            assertEquals("${case["name"]} local", case["expectLocal"], localOnlyDrafts(input(case)))
        }
    }

    @Test
    fun open() {
        for (case in Fixtures.cases(Fixtures.offlineDrafts, "open")) {
            val target = case["target"] as Map<*, *>
            val found = offlineDraftOf(
                input(case),
                messageId = target["messageId"] as String?,
                threadIds = (target["threadIds"] as List<*>?).orEmpty().map { it as String }.toSet(),
            )
            assertEquals(case["name"] as String, case["expect"]?.let(::ref), found)
        }
    }

    @Test
    fun editable() {
        for (case in Fixtures.cases(Fixtures.offlineDrafts, "editable")) {
            val parts = case["parts"] as Map<*, *>
            val result = offlineEditable(
                OfflineDraftParts(
                    encrypted = parts["encrypted"] as Boolean,
                    headersKnown = parts["headersKnown"] as Boolean,
                    filesHeld = (parts["files"] as List<*>).map { (it as Map<*, *>)["held"] as Boolean },
                ),
            )
            assertEquals(case["name"] as String, case["expect"], result)
        }
    }
}
