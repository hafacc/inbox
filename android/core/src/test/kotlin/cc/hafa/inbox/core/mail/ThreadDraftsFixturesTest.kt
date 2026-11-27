package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-draft-fixtures.json`, so drafts in a thread show as on web. */
class ThreadDraftsFixturesTest {
    private fun messages(value: Any?): List<GmailThreadMessage> = (value as List<*>).map { entry ->
        val message = entry as Map<*, *>
        GmailThreadMessage(message["id"] as String, message["draft"] as Boolean)
    }

    private fun local(value: Any?): List<LocalThreadDraft> = (value as List<*>).map { entry ->
        val draft = entry as Map<*, *>
        LocalThreadDraft(
            draft["localId"] as String,
            draft["messageId"] as String?,
            LocalDraftState.valueOf((draft["state"] as String).uppercase()),
            (draft["updatedAt"] as Number).toLong(),
        )
    }

    private fun outbox(value: Any?): Set<String> = (value as List<*>?).orEmpty().map { it as String }.toSet()

    private fun entries(value: Any?): List<ThreadEntry> = (value as List<*>).map { item ->
        val entry = item as Map<*, *>
        when (entry["kind"]) {
            "message" -> ThreadEntry.Message(entry["id"] as String)
            "draft" -> ThreadEntry.Draft(entry["id"] as String)
            else -> ThreadEntry.Local(entry["localId"] as String, entry["sending"] as Boolean)
        }
    }

    @Test
    fun entries() {
        for (case in Fixtures.cases(Fixtures.threadDrafts, "entries")) {
            assertEquals(case["name"] as String, entries(case["expect"]), threadEntries(messages(case["messages"]), local(case["local"]), outbox(case["outbox"])))
        }
    }

    @Test
    fun snippet() {
        for (case in Fixtures.cases(Fixtures.threadDrafts, "snippet")) {
            assertEquals(case["name"] as String, case["expect"] as String, draftSnippet(case["markdown"] as String))
        }
    }

    private fun event(type: String): ListEvent<Nothing, Nothing> = when (type) {
        "tap" -> ListEvent.Tap("t1")
        "enter" -> ListEvent.Enter
        "openThread" -> ListEvent.OpenThread("t1")
        else -> error("no event $type")
    }

    @Test
    fun opening() {
        for (case in Fixtures.cases(Fixtures.threadDrafts, "opening")) {
            assertEquals(
                case["name"] as String,
                case["expect"] as String?,
                draftsOpening(case["inDrafts"] as Boolean, event(case["event"] as String), case["openBefore"] as String?, case["openAfter"] as String?),
            )
        }
    }
}
