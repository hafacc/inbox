package cc.hafa.inbox.mail

import cc.hafa.inbox.core.mail.MessageBody
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.Person
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unsubscribe outcomes stay with each message for the session, whatever screen showed it. */
class UnsubscribesTest {
    private fun message(id: String) =
        MessageView(id, Person("", "news@example.com"), emptyList(), 1, id, false, MessageBody(null, null, emptyList(), emptyList()))

    @Test
    fun outcomeStaysWithTheMessageAndAPressWhileWorkingSendsNothing() {
        val answer = CompletableDeferred<UnsubscribeResult>()
        var calls = 0
        val unsubscribes = Unsubscribes(CoroutineScope(Dispatchers.Unconfined)) {
            calls += 1
            answer.await()
        }
        assertEquals(UnsubscribeStep.IDLE, unsubscribes.stepOf("m1"))
        unsubscribes.run(message("m1")) {}
        assertEquals(UnsubscribeStep.WORKING, unsubscribes.stepOf("m1"))
        unsubscribes.run(message("m1")) {}
        answer.complete(UnsubscribeResult.Unsubscribed)
        assertEquals(UnsubscribeStep.UNSUBSCRIBED, unsubscribes.stepOf("m1"))
        assertEquals(UnsubscribeStep.IDLE, unsubscribes.stepOf("m2"))
        assertEquals(1, calls)
    }

    @Test
    fun aSendersPageOpensAndLeavesTheStepAsItWas() {
        val opened = mutableListOf<String>()
        val unsubscribes = Unsubscribes(CoroutineScope(Dispatchers.Unconfined)) {
            UnsubscribeResult.OpenLink("https://list.example/page")
        }
        unsubscribes.run(message("m1")) { opened += it }
        assertEquals(listOf("https://list.example/page"), opened)
        assertEquals(UnsubscribeStep.IDLE, unsubscribes.stepOf("m1"))
    }
}
