package cc.hafa.inbox.core.mail

// Ported from shared/src/view-filter.ts; both run shared/testdata/view-filter-fixtures.json.

/** A list as its filter sees it. */
sealed interface FilteredView {
    /** One of the fixed lists. */
    data class Fixed(
        /** which list */
        val kind: FilteredKind,
    ) : FilteredView

    /** One label's list. */
    data class Label(
        /** Gmail label id */
        val labelId: String,
    ) : FilteredView

    /** A Gmail search. */
    data class Search(
        /** Gmail search query */
        val query: String,
    ) : FilteredView
}

/** The fixed lists a filter knows. */
enum class FilteredKind {
    /** the inbox */
    INBOX,

    /** snoozed threads */
    SNOOZED,

    /** done (archived) mail */
    DONE,

    /** sent mail */
    SENT,

    /** drafts */
    DRAFTS,

    /** spam */
    SPAM,

    /** trash */
    TRASH,
}

/** Whether snoozed threads show in a list. */
enum class SnoozedRule {
    /** only snoozed threads */
    ONLY,

    /** never snoozed threads */
    NEVER,

    /** snoozed or not */
    EITHER,
}

/** What a list's threads must and mustn't carry. */
data class ViewFilter(
    /** labels a thread must carry */
    val requires: List<String>,
    /** labels a thread mustn't carry */
    val excludes: List<String>,
    /** whether snoozed threads show */
    val snoozed: SnoozedRule,
)

/** A thread as a filter sees it. */
data class FilteredThread(
    /** the labels of all its messages */
    val labelIds: List<String>,
    /** whether it is snoozed now */
    val snoozed: Boolean,
)

/** The inbox action a thread offers. */
enum class InboxAction {
    /** Done: archive it */
    DONE,

    /** Move to inbox */
    INBOX,
}

/** Done and Move to inbox across selected threads: the ids each acts on. */
data class InboxPlan(
    /** threads Done archives */
    val done: List<String>,
    /** threads Move to inbox moves */
    val inbox: List<String>,
)

private val HIDDEN = listOf(Labels.SPAM, Labels.TRASH)

// Search terms that name a system label, as `in:` or `is:` values.
private val SEARCH_LABELS = mapOf(
    "in:inbox" to Labels.INBOX,
    "in:spam" to Labels.SPAM,
    "in:trash" to Labels.TRASH,
    "in:sent" to Labels.SENT,
    "in:draft" to Labels.DRAFT,
    "in:drafts" to Labels.DRAFT,
    "is:starred" to Labels.STARRED,
    "is:pinned" to Labels.STARRED,
    "is:unread" to Labels.UNREAD,
    "is:important" to "IMPORTANT",
)

// Query syntax that groups or alternates terms, which the top-level terms alone can't describe.
private val GROUPING = Regex("""[(){}]|\bOR\b|\bAND\b""")

/**
 * The filter of [view]. A search is read from its plain top-level terms that name labels
 * (`in:inbox`, `is:starred` or `is:pinned`, `label:name` through [labelIdOf], each possibly
 * negated with `-`, and `is:done`, which is `-in:inbox`);
 * other terms leave it unfiltered on them, and grouping (`OR`, parentheses, braces) leaves only
 * spam and trash out. Spam and trash are left out of every list but their own and a search that
 * asks for them (`in:spam`, `in:trash`, `in:anywhere`).
 */
fun viewFilter(view: FilteredView, labelIdOf: (String) -> String? = { null }): ViewFilter = when (view) {
    is FilteredView.Fixed -> when (view.kind) {
        FilteredKind.INBOX -> ViewFilter(listOf(Labels.INBOX), HIDDEN, SnoozedRule.NEVER)
        FilteredKind.SNOOZED -> ViewFilter(emptyList(), HIDDEN, SnoozedRule.ONLY)
        FilteredKind.DONE -> ViewFilter(emptyList(), listOf(Labels.INBOX) + HIDDEN, SnoozedRule.NEVER)
        FilteredKind.SENT -> ViewFilter(listOf(Labels.SENT), HIDDEN, SnoozedRule.EITHER)
        FilteredKind.DRAFTS -> ViewFilter(listOf(Labels.DRAFT), HIDDEN, SnoozedRule.EITHER)
        FilteredKind.SPAM -> ViewFilter(listOf(Labels.SPAM), emptyList(), SnoozedRule.EITHER)
        FilteredKind.TRASH -> ViewFilter(listOf(Labels.TRASH), emptyList(), SnoozedRule.EITHER)
    }
    is FilteredView.Label -> ViewFilter(listOf(view.labelId), HIDDEN, SnoozedRule.EITHER)
    is FilteredView.Search -> searchFilter(view.query, labelIdOf)
}

private fun searchFilter(query: String, labelIdOf: (String) -> String?): ViewFilter {
    val terms = query.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    val anywhere = terms.any { it.lowercase() == "in:anywhere" }
    val requires = linkedSetOf<String>()
    val excludes = linkedSetOf<String>()
    var snoozed = SnoozedRule.EITHER
    if (!GROUPING.containsMatchIn(query)) {
        for (term in terms) {
            val negated = term.startsWith("-")
            val bare = (if (negated) term.drop(1) else term).lowercase()
            if (bare == "in:snoozed" || bare == "is:snoozed") {
                snoozed = if (negated) SnoozedRule.NEVER else SnoozedRule.ONLY
                continue
            }
            if (bare == "is:done") {
                (if (negated) requires else excludes) += Labels.INBOX
                continue
            }
            val label = if (bare.startsWith("label:")) labelIdOf(bare.removePrefix("label:")) else SEARCH_LABELS[bare]
            if (label == null) continue
            if (negated) excludes += label else requires += label
        }
    }
    if (!anywhere) {
        for (hidden in HIDDEN) {
            if (hidden !in requires) excludes += hidden
        }
    }
    return ViewFilter(requires.toList(), excludes.toList(), snoozed)
}

/** Whether [thread] belongs in a list with [filter]. */
fun keepsThread(filter: ViewFilter, thread: FilteredThread): Boolean {
    val (labelIds, snoozed) = thread
    if (filter.snoozed == SnoozedRule.ONLY && !snoozed) return false
    if (filter.snoozed == SnoozedRule.NEVER && snoozed) return false
    return filter.requires.all { it in labelIds } && filter.excludes.none { it in labelIds }
}

/** Whether an action that turns [before] into [after] takes the thread's row out of a list with [filter]. */
fun actionRemoves(filter: ViewFilter, before: FilteredThread, after: FilteredThread): Boolean =
    keepsThread(filter, before) && !keepsThread(filter, after)

/**
 * The inbox action a thread's row, header and the selection bar offer: Done for one in the inbox or
 * snoozed, Move to inbox for one that is neither (not one in spam or trash, which have their own).
 */
fun inboxAction(thread: FilteredThread): InboxAction? =
    if (Labels.SPAM in thread.labelIds || Labels.TRASH in thread.labelIds) {
        null
    } else if (Labels.INBOX in thread.labelIds || thread.snoozed) {
        InboxAction.DONE
    } else {
        InboxAction.INBOX
    }

/** Done and Move to inbox across selected [threads], by id (see [inboxAction]). */
fun inboxPlan(threads: List<Pair<String, FilteredThread>>): InboxPlan = InboxPlan(
    done = threads.filter { (_, thread) -> inboxAction(thread) == InboxAction.DONE }.map { (id, _) -> id },
    inbox = threads.filter { (_, thread) -> inboxAction(thread) == InboxAction.INBOX }.map { (id, _) -> id },
)
