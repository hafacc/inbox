package cc.hafa.inbox.core.label

import cc.hafa.inbox.core.gmail.GmailLabel
import java.text.Normalizer

/** The parent part of a nested name (`Home/Bills` → `Home`), or null. */
fun parentName(name: String): String? {
    val index = name.lastIndexOf('/')
    return if (index <= 0) null else name.substring(0, index)
}

/** The last part of a nested name. */
fun leafName(name: String): String = name.substring(name.lastIndexOf('/') + 1)

/** A label's full name from its parent and own name; a `/` typed in its own name becomes `-`. */
fun nestedName(parent: String?, leaf: String): String {
    val own = leaf.trim().replace('/', '-')
    return if (parent.isNullOrEmpty()) own else "$parent/$own"
}

private val MARKS = Regex("""\p{M}+""")

private fun folded(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

/**
 * Label names in list order: part by part, ignoring case and accents, so each label comes right
 * before the labels nested under it (`Work`, `Work/A`, `Work 2`).
 */
val LABEL_NAME_ORDER: Comparator<String> = Comparator { left, right ->
    val leftParts = left.split('/').map(::folded)
    val rightParts = right.split('/').map(::folded)
    leftParts.zip(rightParts).map { (leftPart, rightPart) -> leftPart.compareTo(rightPart) }.firstOrNull { it != 0 }
        ?: (leftParts.size - rightParts.size).takeIf { it != 0 }
        ?: left.compareTo(right)
}

/** Labels sorted by [LABEL_NAME_ORDER]. */
fun sortLabels(labels: List<GmailLabel>): List<GmailLabel> = labels.sortedWith(compareBy(LABEL_NAME_ORDER) { it.name })

/** One label's new name in a rename. */
data class LabelRename(
    /** Gmail label id */
    val id: String,
    /** its new full name */
    val name: String,
)

/**
 * The labels a rename touches: the label and every user label nested under it (its name and a `/`,
 * in any case), with their new names.
 */
fun renames(labels: List<GmailLabel>, id: String, newName: String): List<LabelRename> {
    val label = labels.firstOrNull { it.id == id } ?: return emptyList()
    val name = newName.trim()
    if (name.isEmpty() || name == label.name) return emptyList()
    val prefix = "${label.name}/".lowercase()
    return listOf(LabelRename(id, name)) + labels
        .filter { it.type == "user" && it.name.lowercase().startsWith(prefix) }
        .map { LabelRename(it.id, name + it.name.substring(label.name.length)) }
}

/** Why a full label name can't be used, or null when it can; [exceptId] is the label being renamed. */
fun labelNameError(labels: List<GmailLabel>, name: String, exceptId: String? = null): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || leafName(trimmed).isBlank()) return "Enter a name"
    val taken = labels.any { it.id != exceptId && it.name.lowercase() == trimmed.lowercase() }
    return if (taken) "A label has that name" else null
}

/**
 * The label Gmail counts as named [name], ignoring case as Gmail does (so making [name] again is
 * refused as a duplicate), or null. Web's `labelNamed` is the same.
 */
fun labelNamed(labels: List<GmailLabel>, name: String): GmailLabel? {
    val wanted = name.trim().lowercase()
    return labels.firstOrNull { it.name.lowercase() == wanted }
}
