package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/join-key-fixtures.json`, as shared/src/join-keys.spec.ts does. */
class JoinKeysFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    @Test
    fun keys() {
        for (case in Fixtures.cases(Fixtures.load("join-key-fixtures.json"), "keys")) {
            val messages = (case["messages"] as List<*>).map { raw ->
                val message = raw as Map<*, *>
                JoinKeyMessage(
                    message["messageId"] as String?,
                    message["inReplyTo"] as String?,
                    message["references"] as String?,
                    (message["date"] as Number).toLong(),
                )
            }
            val expected = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                JoinKeys(strings(expected["messageIds"]), strings(expected["answers"]), (expected["firstDate"] as Number).toLong()),
                joinKeys(messages, case["hiddenSubject"] as Boolean),
            )
        }
    }
}
