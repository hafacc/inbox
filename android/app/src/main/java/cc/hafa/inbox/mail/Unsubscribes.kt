package cc.hafa.inbox.mail

import androidx.compose.runtime.mutableStateMapOf
import cc.hafa.inbox.core.mail.MessageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where unsubscribing from a message stands. */
enum class UnsubscribeStep(
    /** what the control says */
    val label: String,
    /** whether it didn't work, so it can be tried again */
    val failed: Boolean = false,
) {
    /** not started */
    IDLE("Unsubscribe"),

    /** waiting on the request */
    WORKING("Unsubscribing…"),

    /** the sender took the one-click request */
    UNSUBSCRIBED("Unsubscribed"),

    /** the request mail is on its way */
    REQUESTED("Request sent"),

    /** it didn't work */
    FAILED("Couldn't unsubscribe", failed = true),

    /** Gmail access has to be granted again first */
    SIGN_IN("Sign in again to unsubscribe", failed = true),

    /** there was no connection */
    OFFLINE("Can't unsubscribe offline", failed = true),
}

/**
 * Where unsubscribing from each message stands, kept for the session so a thread closed and opened
 * again still shows how it went; requests run in [scope], whatever screen started them.
 */
class Unsubscribes(
    private val scope: CoroutineScope,
    private val unsubscribe: suspend (MessageView) -> UnsubscribeResult,
) {
    private val steps = mutableStateMapOf<String, UnsubscribeStep>()

    /** Where [messageId] stands. */
    fun stepOf(messageId: String): UnsubscribeStep = steps[messageId] ?: UnsubscribeStep.IDLE

    /** Unsubscribe from [message]'s list; a sender's page goes to [openLink] and leaves the step as it was. */
    fun run(message: MessageView, openLink: (String) -> Unit) {
        val before = stepOf(message.id)
        if (before == UnsubscribeStep.WORKING) return
        steps[message.id] = UnsubscribeStep.WORKING
        scope.launch {
            steps[message.id] = when (val result = unsubscribe(message)) {
                UnsubscribeResult.Unsubscribed -> UnsubscribeStep.UNSUBSCRIBED
                UnsubscribeResult.Requested -> UnsubscribeStep.REQUESTED
                UnsubscribeResult.Failed -> UnsubscribeStep.FAILED
                UnsubscribeResult.NeedsSignIn -> UnsubscribeStep.SIGN_IN
                UnsubscribeResult.Offline -> UnsubscribeStep.OFFLINE
                is UnsubscribeResult.OpenLink -> {
                    openLink(result.url)
                    before
                }
            }
        }
    }
}
