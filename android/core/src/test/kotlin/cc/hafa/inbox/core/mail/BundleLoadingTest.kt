package cc.hafa.inbox.core.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each bundle's "Loading…" is its own: only a later page it was asked for shows, not a refresh or another bundle's load. */
class BundleLoadingTest {
    private fun model(vararg lists: Pair<String, LoadedList>) = MailModel(email = "me@example.com", now = 0L, lists = mapOf(*lists))

    @Test
    fun twoBundlesLoadingMoreAtOnceEachShowTheirOwn() {
        val model = model(
            bundleKey("A") to LoadedList(nextPageToken = "a2", loading = true, loaded = true, loadingMore = true),
            bundleKey("B") to LoadedList(nextPageToken = "b2", loading = true, loaded = true, loadingMore = true),
            bundleKey("C") to LoadedList(nextPageToken = "c2", loaded = true),
        )
        assertTrue(model.bundleLoading("A"))
        assertTrue(model.bundleLoading("B"))
        assertFalse(model.bundleLoading("C"))
    }

    @Test
    fun aBundleRefreshingItsFirstPageShowsNoLoading() {
        val model = model(
            bundleKey("A") to LoadedList(nextPageToken = "a2", loaded = true, loadingMore = true, loading = true),
            bundleKey("B") to LoadedList(nextPageToken = "b2", loaded = true, loading = true),
        )
        assertTrue(model.bundleLoading("A"))
        assertFalse(model.bundleLoading("B"))
    }

    @Test
    fun oneBundlesFailureLeavesAnotherAlone() {
        val model = model(
            bundleKey("A") to LoadedList(nextPageToken = "a2", loaded = true, moreError = "Couldn't load more."),
            bundleKey("B") to LoadedList(nextPageToken = "b2", loaded = true, loading = true, loadingMore = true),
        )
        assertFalse(model.bundleLoading("A"))
        assertTrue(model.bundleLoading("B"))
    }
}
