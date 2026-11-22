package cc.hafa.inbox.core.label

// Moving a thread between Gmail's categories from the label sheet's Category choice; the same
// rules as web's shared/src/categories.ts, pinned by shared/testdata/category-fixtures.json.

/** Gmail's Primary category. */
val PRIMARY_CATEGORY: String get() = LabelIcons.primary.first

/** Gmail's categories in the order the label sheet offers them, Primary first. */
val CATEGORY_IDS: List<String> by lazy {
    listOf(PRIMARY_CATEGORY, "CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_UPDATES", "CATEGORY_FORUMS")
}

/** Each category's fixed icon id, Primary included. */
val CATEGORY_CHOICE_ICONS: Map<String, String> by lazy { LabelIcons.categories + (PRIMARY_CATEGORY to LabelIcons.primary.second) }

/** Each category's fixed color tone, Primary included. */
val CATEGORY_CHOICE_TONES: Map<String, String> by lazy { LabelIcons.categoryTones + (PRIMARY_CATEGORY to LabelIcons.primary.third) }

/**
 * The category a thread is in, from the labels of all its messages: the first category other than
 * Primary that it carries, in [CATEGORY_IDS] order; Primary when it carries none.
 */
fun threadCategory(labelIds: Collection<String>): String =
    CATEGORY_IDS.firstOrNull { it != PRIMARY_CATEGORY && it in labelIds } ?: PRIMARY_CATEGORY

/** Labels a `threads.modify` adds and removes. */
data class LabelDelta(
    /** label ids added */
    val add: List<String>,
    /** label ids removed */
    val remove: List<String>,
)

/**
 * The label change that moves a thread into [chosen]: add it and remove every other category the
 * thread carries. Nothing when the thread is already only in [chosen].
 */
fun categoryChange(labelIds: Collection<String>, chosen: String): LabelDelta {
    val remove = CATEGORY_IDS.filter { it != chosen && it in labelIds }
    return if (remove.isEmpty() && threadCategory(labelIds) == chosen) {
        LabelDelta(emptyList(), emptyList())
    } else {
        LabelDelta(listOf(chosen), remove)
    }
}

/**
 * A newest-first id list with [id] placed by date, so a thread moved out of a bundle stays listed
 * until the lists are fetched again. Unchanged when [id] is already there; ids without a date are
 * passed over; [id] goes last when nothing listed is older.
 */
fun keepListed(ids: List<String>, id: String, dateOf: (String) -> Long?): List<String> {
    if (id in ids) return ids
    val date = dateOf(id) ?: 0L
    val index = ids.indexOfFirst { other -> dateOf(other)?.let { it < date } == true }
    return if (index == -1) ids + id else ids.subList(0, index) + id + ids.subList(index, ids.size)
}
