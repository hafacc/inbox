package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.mail.Person

/** Which message of an encrypted send this is. */
enum class EncryptedSendKind {
    /** goes to To and Cc */
    MAIN,

    /** one Bcc recipient's copy */
    BCC,
}

/** One message of an encrypted send: its own draft and outbox doc. */
data class EncryptedSend(
    /** main message or Bcc copy */
    val kind: EncryptedSendKind,
    /** `To` for `buildMime` */
    val to: List<Person>,
    /** `Cc` for `buildMime` */
    val cc: List<Person>,
    /** `Bcc` for `buildMime`: empty for the main message, the one recipient for a copy */
    val bcc: List<Person>,
    /** addresses ([addressKey] form) whose keys it is encrypted to, besides the sender's own */
    val encryptTo: List<String>,
)

private fun uniqueKeys(people: List<Person>, skip: String): List<String> =
    people.map { addressKey(it.email) }.distinct().filter { it != skip }

/**
 * Plan the messages of an encrypted send the way Thunderbird does, so no recipient learns who was
 * Bcc'd (an OpenPGP message names the key ids it is encrypted to): the main message for To and Cc
 * (when either has anyone), then one copy per Bcc address in order, each encrypted to that
 * recipient alone. A Bcc address also in To or Cc gets no copy; a repeated one gets one. The
 * sender's own key is left out of `encryptTo` everywhere; the caller adds it.
 */
fun planEncryptedSends(from: String, to: List<Person>, cc: List<Person>, bcc: List<Person>): List<EncryptedSend> {
    val sender = addressKey(from)
    val visible = (to + cc).map { addressKey(it.email) }.toSet()
    val sends = mutableListOf<EncryptedSend>()
    if (visible.isNotEmpty()) sends += EncryptedSend(EncryptedSendKind.MAIN, to, cc, emptyList(), uniqueKeys(to + cc, sender))
    val copied = mutableSetOf<String>()
    for (person in bcc) {
        val key = addressKey(person.email)
        if (key in visible || !copied.add(key)) continue
        sends += EncryptedSend(EncryptedSendKind.BCC, to, cc, listOf(person), if (key == sender) emptyList() else listOf(key))
    }
    return sends
}
