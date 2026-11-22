package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/recent-search-fixtures.json`, as `shared/src/recent-searches.spec.ts` does on web. */
class RecentSearchFixturesTest {
    private val fixtures = Fixtures.load("recent-search-fixtures.json")

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    @Test
    fun add() {
        for (case in Fixtures.cases(fixtures, "add")) {
            assertEquals(case["name"] as String, strings(case["expect"]), addRecentSearch(strings(case["recent"]), case["query"] as String))
        }
    }

    @Test
    fun remove() {
        for (case in Fixtures.cases(fixtures, "remove")) {
            assertEquals(case["name"] as String, strings(case["expect"]), removeRecentSearch(strings(case["recent"]), case["query"] as String))
        }
    }
}
