package cc.hafa.inbox.core.mail

/** Fewest threads sharing a user label before they fold into one row. */
const val MIN_GROUP_SIZE = 2

/** A list entry: one thread, or the threads of one user label folded together. */
sealed interface ListEntry<out Thread> : SectionEntry<Thread> {
    /** One thread on its own row. */
    data class Single<Thread>(
        /** the thread */
        val thread: Thread,
    ) : ListEntry<Thread>

    /** Threads sharing [labelId], folded into one row. */
    data class Group<Thread>(
        /** the label they share */
        val labelId: String,
        /** the threads, in list order */
        val threads: List<Thread>,
    ) : ListEntry<Thread>
}

/**
 * Fold threads, newest first, into label groups.
 *
 * A user label with at least [minSize] of the threads becomes one group, placed where its
 * newest thread was. A thread with several such labels joins the label with the most threads (ties:
 * the earlier label in [userLabelIds]). A label left with fewer than [minSize] threads after that
 * gives its threads back as single rows, and the choice is redone without it. A [kept] label (an
 * open bundle) stays a group however few threads it has, and takes any thread carrying it first.
 */
fun <Thread> groupByLabel(
    threads: List<Thread>,
    labelsOf: (Thread) -> List<String>,
    userLabelIds: List<String>,
    minSize: Int = MIN_GROUP_SIZE,
    kept: Set<String> = emptySet(),
): List<ListEntry<Thread>> {
    val order = userLabelIds.withIndex().associate { (index, id) -> id to index }
    var candidates = userLabelIds.toSet()
    var assignment: Array<String?>
    while (true) {
        val counts = mutableMapOf<String, Int>()
        for (thread in threads) {
            for (label in labelsOf(thread)) {
                if (label in candidates) counts[label] = (counts[label] ?: 0) + 1
            }
        }
        val eligible = counts.filter { (label, count) -> count >= minSize || label in kept }.keys
        assignment = arrayOfNulls(threads.size)
        val sizes = mutableMapOf<String, Int>()
        threads.forEachIndexed { index, thread ->
            var best: String? = null
            for (label in labelsOf(thread)) {
                if (label !in eligible) continue
                val current = best
                if (current == null || ranksAbove(label, current, kept, counts, order)) {
                    best = label
                }
            }
            if (best != null) {
                assignment[index] = best
                sizes[best] = (sizes[best] ?: 0) + 1
            }
        }
        val tooSmall = sizes.filter { (label, size) -> size < minSize && label !in kept }.keys
        if (tooSmall.isEmpty()) break
        candidates = eligible - tooSmall
    }

    val out = mutableListOf<ListEntry<Thread>>()
    val groups = mutableMapOf<String, MutableList<Thread>>()
    val groupPositions = mutableMapOf<String, Int>()
    threads.forEachIndexed { index, thread ->
        val label = assignment[index]
        if (label == null) {
            out += ListEntry.Single(thread)
        } else {
            val members = groups.getOrPut(label) {
                groupPositions[label] = out.size
                out += ListEntry.Group(label, emptyList())
                mutableListOf()
            }
            members += thread
        }
    }
    for ((label, position) in groupPositions) out[position] = ListEntry.Group(label, groups.getValue(label).toList())
    return out
}

private fun ranksAbove(label: String, other: String, kept: Set<String>, counts: Map<String, Int>, order: Map<String, Int>): Boolean =
    when {
        (label in kept) != (other in kept) -> label in kept
        counts.getValue(label) != counts.getValue(other) -> counts.getValue(label) > counts.getValue(other)
        else -> order.getValue(label) < order.getValue(other)
    }
