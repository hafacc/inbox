package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/view-filter-fixtures.json`, so a list keeps and drops threads as web's does. */
class ViewFilterFixturesTest {
    private val fixtures = Fixtures.load("view-filter-fixtures.json")

    @Suppress("UNCHECKED_CAST")
    private val labels = fixtures["labels"] as Map<String, String>

    private fun view(value: Any?): FilteredView {
        val view = value as Map<*, *>
        return when (val kind = view["kind"] as String) {
            "label" -> FilteredView.Label(view["labelId"] as String)
            "search" -> FilteredView.Search(view["query"] as String)
            else -> FilteredView.Fixed(FilteredKind.valueOf(kind.uppercase()))
        }
    }

    private fun thread(value: Any?): FilteredThread {
        val thread = value as Map<*, *>
        return FilteredThread((thread["labelIds"] as List<*>).map { it as String }, thread["snoozed"] as Boolean)
    }

    @Test
    fun keeps() {
        for (case in Fixtures.cases(fixtures, "keeps")) {
            val filter = viewFilter(view(case["view"]), labels::get)
            assertEquals(case["name"] as String, case["expect"], keepsThread(filter, thread(case["thread"])))
        }
    }

    @Test
    fun inboxAction() {
        for (case in Fixtures.cases(fixtures, "inboxAction")) {
            val want = (case["expect"] as String?)?.let { InboxAction.valueOf(it.uppercase()) }
            assertEquals(case["name"] as String, want, inboxAction(thread(case["thread"])))
        }
    }

    @Test
    fun inboxPlan() {
        for (case in Fixtures.cases(fixtures, "inboxPlan")) {
            val threads = (case["threads"] as List<*>).map { entry -> (entry as Map<*, *>)["id"] as String to thread(entry) }
            val expect = case["expect"] as Map<*, *>
            val want = InboxPlan((expect["done"] as List<*>).map { it as String }, (expect["inbox"] as List<*>).map { it as String })
            assertEquals(case["name"] as String, want, inboxPlan(threads))
        }
    }
}
