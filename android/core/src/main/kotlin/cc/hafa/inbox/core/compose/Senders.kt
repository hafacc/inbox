package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.gmail.GmailSendAs
import cc.hafa.inbox.core.mail.Person

/**
 * The From list, in web's order (`fromOptions` in web/src/lib/compose/send-as.ts): the @hafa.cc
 * address once it is active, then Gmail's verified send-as addresses with the account's own first;
 * without a send-as list, the account's address alone.
 */
fun senderList(sendAs: List<GmailSendAs>, accountEmail: String, activeAddress: String?): List<Person> {
    val primaryName = sendAs.firstOrNull { it.isPrimary }?.displayName.orEmpty()
    val usable = sendAs.filter { it.usable }.sortedByDescending { it.isPrimary }.map { Person(it.displayName.orEmpty(), it.sendAsEmail) }
    val base = usable.ifEmpty { listOf(Person(primaryName, accountEmail)) }
    if (activeAddress == null) return base
    return listOf(Person(primaryName, activeAddress)) + base.filter { addressKey(it.email) != addressKey(activeAddress) }
}
