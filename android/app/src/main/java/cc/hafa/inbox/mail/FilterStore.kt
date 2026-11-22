package cc.hafa.inbox.mail

import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.gmail.GmailClient
import cc.hafa.inbox.core.gmail.ListOptions
import cc.hafa.inbox.core.mail.AccountChange
import cc.hafa.inbox.core.mail.filtersWithPending
import cc.hafa.inbox.core.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val PREVIEW_PAGE = 100

/** The account's Gmail filters, as last loaded, with the device's queued changes applied. */
data class FiltersState(
    /** the filters */
    val filters: List<GmailFilter> = emptyList(),
    /** verified forwarding addresses a filter may forward to */
    val forwarding: List<String> = emptyList(),
    /** whether a load is under way */
    val loading: Boolean = false,
    /** whether a load has finished */
    val loaded: Boolean = false,
    /** why the last load failed, or null */
    val error: String? = null,
)

/**
 * Gmail filters as shown: Gmail's list with the [pending] queued saves and deletes applied (the
 * queue in [MailState] sends them), and the live preview.
 */
class FilterStore(private val gmail: GmailClient, pending: Flow<List<AccountChange>>, scope: CoroutineScope) {
    private val state = MutableStateFlow(FiltersState())

    /** Filters and verified forwarding addresses. */
    val filters: StateFlow<FiltersState> = combine(state, pending) { fetched, queue ->
        fetched.copy(filters = filtersWithPending(fetched.filters, queue))
    }.stateIn(scope, SharingStarted.Eagerly, FiltersState())

    /** Load the filters and forwarding addresses from Gmail. */
    suspend fun load() {
        state.value = state.value.copy(loading = true, error = null)
        state.value = try {
            val filters = gmail.listFilters()
            val forwarding = runSuspendCatching { gmail.listForwardingAddresses() }.getOrDefault(state.value.forwarding)
            FiltersState(filters, forwarding, loading = false, loaded = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.value.copy(loading = false, loaded = true, error = if (isOffline(error)) "You're offline." else "Couldn't load filters.")
        }
    }

    /** Note a filter Gmail made. */
    fun made(filter: GmailFilter) {
        state.value = state.value.copy(filters = state.value.filters + filter)
    }

    /** Note a filter Gmail deleted. */
    fun deleted(id: String) {
        state.value = state.value.copy(filters = state.value.filters.filterNot { it.id == id })
    }

    /** How many threads a search finds now, for the preview; "100+" past one page. */
    suspend fun previewCount(query: String): String {
        if (query.isBlank()) return "0"
        val page = gmail.listThreads(ListOptions(query = query, maxResults = PREVIEW_PAGE))
        val count = page.threads.orEmpty().size
        return if (page.nextPageToken != null) "$count+" else count.toString()
    }
}
