package cc.hafa.inbox.core.contacts

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/contact-match-fixtures.json`, so recipient suggestions match web's. */
class ContactMatchFixturesTest {
    private val fixtures by lazy { Fixtures.load("contact-match-fixtures.json") }

    private fun entry(raw: Any?): BookEntry {
        val map = raw as Map<*, *>
        return BookEntry(map["name"] as String, map["email"] as String, map["photo"] as String?, map["source"] == "connections")
    }

    private fun entries(raw: Any?): List<BookEntry> = (raw as List<*>).map(::entry)

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = (raw as List<String>?).orEmpty()

    @Test
    fun merge() {
        for (case in Fixtures.cases(fixtures, "merge")) {
            assertEquals(case["name"] as String, entries(case["expect"]), mergeBook(entries(case["entries"])))
        }
    }

    @Test
    fun words() {
        for (case in Fixtures.cases(fixtures, "words")) {
            assertEquals(case["text"] as String, strings(case["expect"]), contactWords(case["text"] as String))
        }
    }

    @Test
    fun search() {
        val book = entries(fixtures["book"])
        for (case in Fixtures.cases(fixtures, "search")) {
            assertEquals(
                case["name"] as String,
                strings(case["expect"]),
                searchContacts(book, case["query"] as String, strings(case["exclude"]), (case["limit"] as Number?)?.toInt() ?: 6).map { it.email },
            )
        }
    }
}
