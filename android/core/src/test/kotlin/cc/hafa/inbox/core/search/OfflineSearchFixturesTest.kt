package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/offline-search-fixtures.json`, so searching offline finds what web finds. */
class OfflineSearchFixturesTest {
    private val fixtures by lazy { Fixtures.load("offline-search-fixtures.json") }

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    @Test
    fun terms() {
        for (case in Fixtures.cases(fixtures, "terms")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                FreeTerms(strings(expect["include"]), strings(expect["exclude"])),
                freeTerms(case["free"] as String),
            )
        }
    }

    @Test
    fun match() = runBlocking {
        for (case in Fixtures.cases(fixtures, "match")) {
            val raw = case["thread"] as Map<*, *>
            val thread = ThreadSummary(
                id = "t",
                historyId = "1",
                subject = raw["subject"] as String,
                snippet = raw["snippet"] as String,
                senders = (raw["senders"] as List<*>).map { val person = it as Map<*, *>; Person(person["name"] as String, person["email"] as String) },
                messageCount = 1,
                lastDate = 0,
                labelIds = strings(raw["labelIds"]),
                unread = raw["unread"] as Boolean,
            )
            val saved = raw["saved"] as Map<*, *>?
            val labelNames = (case["labels"] as List<*>?)
                ?.associate { val label = it as Map<*, *>; label["id"] as String to label["name"] as String }
                ?: mapOf("Label_1" to "Home Stuff", "Label_2" to "Work/Projects")
            suspend fun matches(query: String) = matchesOffline(
                thread,
                searchPlan(query),
                labelNames,
                savedHolds = { term -> saved != null && term in (saved["text"] as String) },
                saved = { saved?.let { SavedText(it["recipients"] as String, it["attachment"] as Boolean) } },
            )
            val name = case["name"] as String
            assertEquals(name, emptyList<String>(), strings(case["expect"]).filterNot { matches(it) })
            assertEquals(name, emptyList<String>(), strings(case["reject"]).filter { matches(it) })
        }
    }
}
