package cc.hafa.inbox.core.mail

/** Where an open thread sat when it opened. */
data class ThreadPlace(
    /** the date it sorted by, epoch milliseconds */
    val date: Long,
    /** whether it sat with the pinned threads */
    val pinned: Boolean,
    /** whether it sat on its own row rather than in a bundle */
    val single: Boolean,
)

/**
 * Places an open thread or bundle keeps in the list until it closes, so acting on it (or on the
 * threads of an open bundle) doesn't move it under the user, as latr keeps a todo being edited in
 * place. A list sorts and sections a held thread or bundle by its held place instead of its live
 * one; once it closes it lets go and moves to where it belongs. The focused thread holds only its
 * pinning, so pinning or unpinning it moves it once focus leaves, while anything else moves it at
 * once.
 */
data class HeldPlaces(
    /** open threads, by thread id */
    val threads: Map<String, ThreadPlace> = emptyMap(),
    /** open bundles by label id: the date of their newest thread when they opened, epoch milliseconds */
    val bundles: Map<String, Long> = emptyMap(),
    /** focused threads not open, by thread id: whether they were pinned when focus came to them */
    val pins: Map<String, Boolean> = emptyMap(),
)

private fun <Place> holdEach(held: Map<String, Place>, open: Map<String, Place>, release: Boolean): Map<String, Place> {
    val next = LinkedHashMap<String, Place>()
    for ((key, place) in held) if (!release || key in open) next[key] = place
    for ((key, place) in open) next.putIfAbsent(key, place)
    return if (next.keys == held.keys) held else next
}

/**
 * The places to hold while [open] is open, each open thread and bundle at the place the list shows
 * it: one held before keeps its held place, one newly open holds the place it shows at, and one no
 * longer open lets go, unless [release] is false (while its close still plays). A focused thread's
 * pinning is held the same way, but lets go as soon as focus leaves, since nothing plays then.
 * Returns [held] itself when nothing changed.
 */
fun holdPlaces(held: HeldPlaces, open: HeldPlaces, release: Boolean = true): HeldPlaces {
    val threads = holdEach(held.threads, open.threads, release)
    val bundles = holdEach(held.bundles, open.bundles, release)
    val pins = holdEach(held.pins, open.pins, release = true)
    return if (threads === held.threads && bundles === held.bundles && pins === held.pins) held else HeldPlaces(threads, bundles, pins)
}
