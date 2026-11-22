package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.testing.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Bouncy Castle against openpgp.js on pgp-fixtures.json's decrypt cases, with keys made on each run:
 * reads the messages shared/src/pgp/interop.ts wrote, and writes this side's for it to check. The
 * Gradle build passes both file paths and runs the script before and after this test.
 */
class PgpInteropTest {
    private val cases = Fixtures.cases(Fixtures.load("pgp-fixtures.json"), "decrypt")

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private fun file(property: String): File =
        File(requireNotNull(System.getProperty(property)) { "$property is unset: run this test through Gradle" })

    @Test
    fun decryptsWhatOpenpgpJsMade() {
        val web = Fixtures.parse(file("pgp.interop.web").readText())
        @Suppress("UNCHECKED_CAST")
        val keys = web["keys"] as Map<String, Map<String, String>>
        val messages = web["messages"] as Map<*, *>
        for (case in cases) {
            val name = case["name"] as String
            val message = fromBase64(requireNotNull(messages[name] as String?) { "no message for $name" })
            val outcome = FixtureMessages.outcome(
                message,
                keys.getValue(case["decryptionKey"] as String).getValue("privateKey"),
                strings(case["verificationKeys"]).map { keys.getValue(it).getValue("publicKey") },
            )
            assertEquals(name, case["expect"], outcome)
        }
    }

    @Test
    fun writesMessagesForOpenpgpJs() {
        val out = file("pgp.interop.android")
        out.parentFile.mkdirs()
        val keys = FixtureMessages.keys.mapValues { (_, pair) -> mapOf("publicKey" to pair.publicKey, "privateKey" to pair.privateKey) }
        val messages = cases.associate { it["name"] as String to toBase64(FixtureMessages.message(it)) }
        out.writeText(JSONObject(mapOf("keys" to keys, "messages" to messages)).toString())
    }
}
