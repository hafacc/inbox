package cc.hafa.inbox.core.send

// Port of shared/src/outbox-copies.ts: an encrypted send with Bcc recipients writes its first
// message's doc `{id}` and one doc per Bcc copy, `{id}-bcc-{n}`. Both run
// shared/testdata/outbox-copy-fixtures.json.

// A copy's id: the send's id, `-bcc-`, and its number.
private val COPY_ID = Regex("^(.+)-bcc-([1-9]\\d*)$")

/** The outbox doc id of a split send's copy number [copy] (from 1). */
fun copyOutboxId(baseId: String, copy: Int): String = "$baseId-bcc-$copy"

/** A Bcc copy's number, or null for a send's first doc. */
fun copyNumber(id: String): Int? = COPY_ID.matchEntire(id)?.groupValues?.get(2)?.toIntOrNull()

/** Whether an outbox doc id is a Bcc copy's. */
fun isCopyId(id: String): Boolean = COPY_ID.matches(id)

/** The id of the send a doc belongs to: its own, or the first message's for a copy. */
fun baseOutboxId(id: String): String = COPY_ID.matchEntire(id)?.groupValues?.get(1) ?: id

/** The ids among [ids] of one send, in order: the first message's and its copies'. */
fun sendGroup(ids: Iterable<String>, baseId: String): List<String> = ids.filter { baseOutboxId(it) == baseId }

/** [count] copy numbers not used by any doc of the send yet, ascending. */
fun nextCopyNumbers(ids: Iterable<String>, baseId: String, count: Int): List<Int> {
    val highest = sendGroup(ids, baseId).mapNotNull(::copyNumber).maxOrNull() ?: 0
    return (1..count).map { highest + it }
}
