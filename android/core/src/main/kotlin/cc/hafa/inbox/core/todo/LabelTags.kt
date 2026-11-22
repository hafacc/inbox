package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.ViewFilter
import cc.hafa.inbox.core.search.labelQueryValue

// A todo's `#tags` read as the user's own Gmail labels, the same as web's
// shared/src/todos/label-tags.ts; both run shared/testdata/label-tag-fixtures.json.

/**
 * The user labels a todo's `#tag` names, by the tag's key ([Tags.key]), each key's labels in name
 * order: `#read-later` names "Read Later", `#work/projects` the nested "Work/Projects". Only user
 * labels count, never Gmail's own or its categories.
 */
data class LabelTags(
    /** the account's labels, of every kind */
    val labels: List<GmailLabel>,
) {
    /** the labels by tag key */
    val byKey: Map<String, List<GmailLabel>> = labels
        .sortedWith(compareBy<GmailLabel> { it.name.lowercase() }.thenBy { it.name })
        .filter { it.type == "user" }
        .mapNotNull { label -> labelTag(label.name)?.let { it to label } }
        .groupBy({ it.first }, { it.second })

    /** The tags these labels offer as a `#tag` is typed, one per key, in key order. */
    val names: List<String> by lazy { byKey.keys.sorted() }

    // The ids of every label a tag can name.
    private val ids: Set<String> by lazy { byKey.values.flatten().mapTo(HashSet()) { it.id } }

    /** The label a tag (without `#`) names, the first by name when several do; null when none does. */
    fun labelOf(tag: String): GmailLabel? = byKey[Tags.key(tag)]?.firstOrNull()

    /** The ids of the labels [text]'s tags name, in the order the tags come, each once. */
    fun labelIds(text: String): List<String> {
        val found = LinkedHashSet<String>()
        for (span in Tags.parse(text)) {
            byKey[Tags.key(text.substring(span.start + 1, span.end))]?.forEach { found += it.id }
        }
        return found.toList()
    }

    /** Whether [text] names a label that Gmail's `label:` takes as [value]. */
    fun namesQuery(text: String, value: String): Boolean {
        val wanted = labelQueryValue(value)
        return Tags.parse(text).any { span ->
            byKey[Tags.key(text.substring(span.start + 1, span.end))].orEmpty().any { labelQueryValue(it.name) == wanted }
        }
    }

    /** The todos whose text names the label [labelId], in their order: a label's own list. */
    fun todosOf(todos: List<Todo>, labelId: String): List<Todo> = todos.filter { labelId in labelIds(it.text) }

    /** The email todos whose text names the label [labelId], in their order: the emails a label's list adds. */
    fun emailTodosOf(todos: List<EmailTodo>, labelId: String): List<EmailTodo> = todos.filter { labelId in labelIds(it.text) }

    /** [filter] as it checks a thread added by [taggedThreadIds]: no user label of these required. */
    fun taggedFilter(filter: ViewFilter): ViewFilter = filter.copy(requires = filter.requires.filterNot { it in ids })

    companion object {
        private val letter = Regex("""\p{L}""")

        /** A label's name as a tag, without `#` (its [Tags.key]); null when no tag can name it (no letter). */
        fun labelTag(name: String): String? = Tags.key(name).takeIf { letter.containsMatchIn(it) }

        /**
         * [text] with the tags naming the label [oldName] renamed to name [newName], and those naming
         * a label nested under it moved along (`#work/projects` to `#job/projects` when Work becomes
         * Job). A renamed tag is spelled as [labelTag] has it; the nested parts keep their spelling.
         * Text is left as it is when either name can't be a tag.
         */
        fun rename(text: String, oldName: String, newName: String): String {
            val oldKey = labelTag(oldName) ?: return text
            val newTag = labelTag(newName) ?: return text
            if (oldKey == newTag) return text
            val depth = oldKey.split('/').size
            var renamed = text
            for (span in Tags.parse(text).asReversed()) {
                val name = text.substring(span.start + 1, span.end)
                val key = Tags.key(name)
                val replacement = when {
                    key == oldKey -> newTag
                    key.startsWith("$oldKey/") -> {
                        val rest = name.split('/').filter { Tags.key(it).isNotEmpty() }.drop(depth).joinToString("/")
                        "$newTag/$rest"
                    }
                    else -> null
                }
                if (replacement != null) renamed = renamed.substring(0, span.start + 1) + replacement + renamed.substring(span.end)
            }
            return renamed
        }

        /**
         * The Gmail threads a list adds for their todos: each of [threadIds] (the emails whose todo
         * the list shows through its text) that [listed] doesn't hold, each once, in order. Its row
         * shows, the todo inside, though Gmail doesn't give the thread the label or match it to the search.
         */
        fun taggedThreadIds(threadIds: List<String>, listed: Collection<String>): List<String> {
            val held = listed.toHashSet()
            return threadIds.filter { held.add(it) }
        }
    }
}
