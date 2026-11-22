package cc.hafa.inbox.core.todo

/**
 * Text keys the todo lists order by, ported from latr (web `utils/position.ts`); the same as
 * shared/src/todos/position.ts, both running shared/testdata/position-fixtures.json.
 *
 * Keys are text over 0-9a-z compared by code unit, always starting with a 13-digit millisecond
 * time. A written position ends with the todo's id, so no two todos share one and a third row can
 * always go between two others.
 */
object Position {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"
    private const val MIDDLE = 'i'
    private const val TIME_DIGITS = 13
    private const val MAX_TIME = 9_999_999_999_999L
    private val POSITION_PATTERN = Regex("^[0-9]{13}[0-9a-z]*$")

    /** A time as 13 digits, so text order is time order. */
    fun timeKey(epochMillis: Long): String =
        epochMillis.coerceIn(0L, MAX_TIME).toString().padStart(TIME_DIGITS, '0')

    /** The time a key starts with. */
    fun keyTime(key: String): Long = key.take(TIME_DIGITS).toLong()

    /** An id as key text; the ending keeps a key from ending in the lowest character. */
    fun idKey(id: String): String =
        id.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }.lowercase() + "z"

    /** A saved position, or null when it isn't one this build can order by. */
    fun readPosition(value: Any?): String? =
        (value as? String)?.takeIf { POSITION_PATTERN.matches(it) }

    // Digits only ever move to digits, so the leading time stays a time.
    private fun after(rest: String): String {
        for (index in rest.indices) {
            val digit = ALPHABET.indexOf(rest[index])
            val top = if (digit < 10) 9 else ALPHABET.length - 1
            if (digit < top) return rest.take(index) + ALPHABET[(digit + top + 1) shr 1]
        }
        return rest + MIDDLE
    }

    /**
     * Text that sorts strictly between [lo] and [hi] and is never the start of [hi].
     * Null when [lo] isn't below [hi], or [hi] is [lo] followed only by zeros.
     */
    fun between(lo: String, hi: String): String? {
        if (lo >= hi) return null
        var shared = 0
        while (shared < lo.length && lo[shared] == hi[shared]) shared++
        return if (shared == lo.length) {
            var nonZero = shared
            while (nonZero < hi.length && hi[nonZero] == '0') nonZero++
            if (nonZero == hi.length) return null
            val digit = ALPHABET.indexOf(hi[nonZero])
            if (digit >= 2) hi.take(nonZero) + ALPHABET[digit shr 1] else hi.take(nonZero) + "0" + MIDDLE
        } else {
            val low = ALPHABET.indexOf(lo[shared])
            val high = ALPHABET.indexOf(hi[shared])
            if (high - low >= 2) {
                lo.take(shared) + ALPHABET[(low + high) shr 1]
            } else {
                lo.take(shared + 1) + after(lo.substring(shared + 1))
            }
        }
    }

    /**
     * A position for todo [id] between two keys: the time halfway between theirs, or text between
     * them once their times are the same or a millisecond apart. Ends in the id so no two todos
     * share one.
     */
    fun positionBetween(lo: String, hi: String, id: String): String? {
        if (lo >= hi) return null
        val low = keyTime(lo)
        val high = keyTime(hi)
        return if (high - low >= 2) {
            timeKey(Math.floorDiv(low + high, 2L)) + idKey(id)
        } else {
            between(lo, hi)?.let { it.padEnd(TIME_DIGITS, '0') + idKey(id) }
        }
    }

    /** A position for todo [id] just above [lo], within the same millisecond. */
    fun positionAbove(lo: String, id: String): String? = positionBetween(lo, timeKey(keyTime(lo) + 1), id)

    /** A position for todo [id] just below [hi], within the same millisecond. */
    fun positionBelow(hi: String, id: String): String? = positionBetween(timeKey(keyTime(hi)), hi, id)
}
