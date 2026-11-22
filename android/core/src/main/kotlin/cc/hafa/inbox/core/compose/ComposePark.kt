package cc.hafa.inbox.core.compose

/**
 * Parking a message being written: dragging the composer's top bar down, or its Minimize button,
 * shrinks it into a strip at the bottom of the screen naming its subject, and a tap on the strip
 * opens it again as it was. Port of `shared/src/compose-park.ts`; fixtures in
 * `shared/testdata/compose-park-fixtures.json`.
 */
object ComposePark {
    /** How far the top bar must be dragged down, in dp, for letting go to park. */
    const val DISTANCE = 96f

    /** How fast a shorter drag must be moving down when let go, in dp per second, to park. */
    const val VELOCITY = 800f

    /** The strip's height above the navigation bar, in dp. */
    const val STRIP_HEIGHT = 56

    /** The strip's text: the subject, else what the message is. */
    fun title(subject: String, kind: ComposeKind): String {
        val trimmed = subject.trim()
        return if (trimmed.isNotEmpty()) {
            trimmed
        } else {
            when (kind) {
                ComposeKind.REPLY, ComposeKind.REPLY_ALL -> "Reply"
                ComposeKind.FORWARD -> "Forward"
                ComposeKind.NEW -> "New message"
            }
        }
    }

    /** Whether letting go of the top bar parks the composer, after a drag of [dragged] dp moving at [velocity] dp/s (negative going up). */
    fun parksOnRelease(dragged: Float, velocity: Float): Boolean =
        if (dragged <= 0f || velocity <= -VELOCITY) false else dragged >= DISTANCE || velocity >= VELOCITY
}
