package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/encrypted-send-fixtures.json`: Bcc'd encrypted sends split the way web splits them. */
class EncryptedSendFixturesTest {
    private val fixtures = Fixtures.load("encrypted-send-fixtures.json")

    private fun people(raw: Any?): List<Person> =
        (raw as List<*>).map { entry ->
            val map = entry as Map<*, *>
            Person(map["name"] as String, map["email"] as String)
        }

    @Test
    fun plan() {
        for (case in Fixtures.cases(fixtures, "plan")) {
            val input = case["input"] as Map<*, *>
            val actual = planEncryptedSends(input["from"] as String, people(input["to"]), people(input["cc"]), people(input["bcc"]))
            val expected = (case["expect"] as List<*>).map { entry ->
                val map = entry as Map<*, *>
                EncryptedSend(
                    EncryptedSendKind.valueOf((map["kind"] as String).uppercase()),
                    people(map["to"]),
                    people(map["cc"]),
                    people(map["bcc"]),
                    (map["encryptTo"] as List<*>).map { it as String },
                )
            }
            assertEquals(case["name"] as String, expected, actual)
        }
    }

    @Test
    fun protect() {
        for (case in Fixtures.cases(fixtures, "protect")) {
            val parts = protectedParts(case["mime"] as String, case["bccCopy"] as Boolean)
            assertEquals(case["kind"] as String, (case["outerHeaders"] as List<*>).map { it as String }, parts.outerHeaders)
            assertEquals(case["kind"] as String, case["inner"], parts.inner)
        }
    }
}
