package cc.hafa.inbox.core.mail

/** The Gmail query naming pinned threads, added to the view's own query in focus mode. */
const val FOCUS_QUERY = "is:starred"

/** The labels to add and remove to mark a thread important or not; both empty when it already is. */
data class ImportantChange(
    /** label ids added */
    val add: List<String>,
    /** label ids removed */
    val remove: List<String>,
)

/** Whether a thread carrying [labelIds] is important. */
fun isImportant(labelIds: List<String>): Boolean = Labels.IMPORTANT in labelIds

/** The label change that marks a thread important ([important] true) or not important. */
fun importantChange(labelIds: List<String>, important: Boolean): ImportantChange =
    if (isImportant(labelIds) == important) {
        ImportantChange(emptyList(), emptyList())
    } else if (important) {
        ImportantChange(listOf(Labels.IMPORTANT), emptyList())
    } else {
        ImportantChange(emptyList(), listOf(Labels.IMPORTANT))
    }

/** Sections with focus mode applied, and how many entries it hid. */
data class FocusResult(
    /** the sections still showing something */
    val sections: List<SectionModel>,
    /** rows hidden for not being pinned: threads, todos and bundles, one each */
    val hidden: Int,
)

// Whether focus mode shows an entry: pinned, held pinned while focused, or holding the open thread
// so unpinning it doesn't close it.
private fun shown(entry: SectionEntry<Row>, openId: String?, heldPins: Map<String, Boolean>): Boolean = when (entry) {
    is ListEntry.Single -> entry.thread.thread.let { thread ->
        thread.id == openId || thread.isPinned || heldPins[thread.id] == true
    }
    is TodoEntry -> entry.todo.pinned || heldPins[todoRowKey(entry.todo.id)] == true
    is ListEntry.Group -> openId != null && entry.threads.any { it.thread.id == openId }
}

/**
 * Apply focus mode to a list's sections.
 *
 * Only pinned threads (Gmail's star) and pinned todos show, plus the open thread, alone or in its bundle, so unpinning it doesn't close it, and the focused
 * thread or todo while [heldPins] ([HeldPlaces.pins]) holds it pinned, so unpinning it doesn't hide it
 * until focus leaves. Pinned threads never sit in bundles, so bundles are hidden. Sections left with
 * nothing are dropped.
 */
fun focusSections(sections: List<SectionModel>, openId: String?, heldPins: Map<String, Boolean> = emptyMap()): FocusResult {
    var hidden = 0
    val kept = sections.mapNotNull { section ->
        val entries = section.entries.filter { entry ->
            val keep = shown(entry, openId, heldPins)
            if (!keep) hidden += 1
            keep
        }
        if (entries.isEmpty()) null else section.copy(entries = entries)
    }
    return FocusResult(kept, hidden)
}

/** The Gmail query focus mode lists the inbox with: the view's own [query] and pinned threads only. */
fun focusQuery(query: String): String {
    val own = query.trim()
    return if (own.isEmpty()) FOCUS_QUERY else "$own $FOCUS_QUERY"
}
