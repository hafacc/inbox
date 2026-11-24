package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.ListOptions
import cc.hafa.inbox.core.search.searchScope
import cc.hafa.inbox.core.search.searchTagTerms
import cc.hafa.inbox.core.todo.LabelTags

/** A mailbox shown in the main column. */
sealed interface View {
    /** A stable key for the view, used for its cached list. */
    val key: String

    /** One of the fixed mailboxes. */
    data class Fixed(
        /** the mailbox */
        val box: Mailbox,
    ) : View {
        override val key: String get() = box.key
    }

    /** One user label. */
    data class Label(
        /** Gmail label id */
        val labelId: String,
    ) : View {
        override val key: String get() = "label:$labelId"
    }

    /** The Todos view: todos only, under latr's lists. */
    data object Todos : View {
        override val key: String get() = "todos"
    }

    /** A Gmail search. */
    data class Search(
        /** Gmail search query */
        val query: String,
    ) : View {
        override val key: String get() = "search:$query"
    }

    companion object {
        /** the inbox */
        val INBOX = Fixed(Mailbox.INBOX)
    }
}

/** The view a [View.key] names, or null when it names none. */
fun viewFromKey(key: String): View? = when {
    key == View.Todos.key -> View.Todos
    key.startsWith(LABEL_KEY) -> View.Label(key.removePrefix(LABEL_KEY))
    key.startsWith(SEARCH_KEY) -> View.Search(key.removePrefix(SEARCH_KEY))
    else -> Mailbox.entries.firstOrNull { it.key == key }?.let(View::Fixed)
}

private const val LABEL_KEY = "label:"
private const val SEARCH_KEY = "search:"

/** The fixed mailboxes, with their short names. */
enum class Mailbox(
    /** short name, as in view keys */
    val key: String,
    /** name shown */
    val title: String,
) {
    /** mail not yet done */
    INBOX("inbox", "Inbox"),

    /** snoozed threads */
    SNOOZED("snoozed", "Snoozed"),

    /** done (archived) mail */
    DONE("done", "Done"),

    /** sent mail */
    SENT("sent", "Sent"),

    /** drafts */
    DRAFTS("drafts", "Drafts"),

    /** spam */
    SPAM("spam", "Spam"),
}

/** Gmail's search for Done: archived mail that isn't spam, trash, sent or a draft. */
const val DONE_QUERY = "-in:inbox -in:spam -in:trash -in:sent -in:draft"

/**
 * The `threads.list` query for a view; Snoozed, Todos and a search that lists no Gmail mail
 * ([searchScope]) have none, as they come from Firestore. A search runs with its `#name`s naming
 * [labels] as their `label:` terms ([searchTagTerms]).
 */
fun listOptions(view: View, labels: LabelTags = LabelTags(emptyList())): ListOptions? = when (view) {
    View.Todos -> null
    is View.Fixed -> when (view.box) {
        Mailbox.INBOX -> ListOptions(labelIds = listOf(Labels.INBOX))
        Mailbox.SNOOZED -> null
        Mailbox.DONE -> ListOptions(query = DONE_QUERY)
        Mailbox.SENT -> ListOptions(labelIds = listOf(Labels.SENT))
        Mailbox.DRAFTS -> ListOptions(labelIds = listOf(Labels.DRAFT))
        Mailbox.SPAM -> ListOptions(labelIds = listOf(Labels.SPAM), includeSpamTrash = true)
    }
    is View.Label -> ListOptions(labelIds = listOf(view.labelId))
    is View.Search -> {
        val scope = searchScope(searchTagTerms(view.query, labels))
        scope.gmailQuery?.let { ListOptions(query = it, includeSpamTrash = scope.includeSpamTrash) }
    }
}

/**
 * [this] as its list's filter sees it (see [viewFilter]), a search's query as it runs with [labels]
 * ([searchTagTerms]); null for the Todos view, which lists no threads.
 */
fun View.filtered(labels: LabelTags = LabelTags(emptyList())): FilteredView? = when (this) {
    View.Todos -> null
    is View.Fixed -> FilteredView.Fixed(
        when (box) {
            Mailbox.INBOX -> FilteredKind.INBOX
            Mailbox.SNOOZED -> FilteredKind.SNOOZED
            Mailbox.DONE -> FilteredKind.DONE
            Mailbox.SENT -> FilteredKind.SENT
            Mailbox.DRAFTS -> FilteredKind.DRAFTS
            Mailbox.SPAM -> FilteredKind.SPAM
        },
    )
    is View.Label -> FilteredView.Label(labelId)
    is View.Search -> FilteredView.Search(searchTagTerms(query, labels))
}

/** A label's name as a search's `label:` term writes it: lower case, spaces and slashes as dashes. */
fun searchLabelName(name: String): String = name.trim().lowercase().replace(Regex("""[\s/]+"""), "-")
