package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.ComposeInput
import cc.hafa.inbox.core.compose.buildMime
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/pgp-fixtures.json`, so Android opens and builds what web does. */
class PgpFixturesTest {
    private val fixtures = Fixtures.load("pgp-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private val keys = FixtureMessages.keys
    private val alice = keys.getValue("alice")
    private val bob = keys.getValue("bob")

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private fun kdf(raw: Any?): RecoveryKdf {
        val map = raw as Map<*, *>
        return RecoveryKdf(map["name"] as String, map["memoryKiB"] as Int, map["iterations"] as Int, map["parallelism"] as Int)
    }

    @Test
    fun wordlistMatchesTheBundledFile() {
        assertEquals(7776, EFF_LARGE_WORDLIST.size)
        assertEquals("abacus", EFF_LARGE_WORDLIST.first())
        assertEquals("zoom", EFF_LARGE_WORDLIST.last())
    }

    @Test
    fun sealAndUnseal() {
        for (case in cases("seal")) {
            val secret = (case["secretHex"] as String).hexToByteArray()
            assertEquals(case["sealed"], seal(secret, case["plaintext"] as String, (case["ivHex"] as String).hexToByteArray()))
            assertEquals(case["plaintext"], unseal(secret, case["sealed"] as String))
        }
        for (case in cases("unsealFailures")) {
            assertThrows(case["name"] as String, SealException::class.java) { unseal((case["secretHex"] as String).hexToByteArray(), case["sealed"] as String) }
        }
        val secret = ByteArray(32)
        assertNotEquals(seal(secret, "x"), seal(secret, "x"))
        assertThrows(SealException::class.java) { seal(ByteArray(16), "x") }
    }

    @Test
    fun phraseWords() {
        for (case in cases("phraseWords")) {
            val words = strings(case["words"])
            assertEquals(words, wordsFromBytes((case["bytesHex"] as String).hexToByteArray(), words.size))
        }
    }

    @Test
    fun parsesPhrases() {
        for (case in cases("parsePhrase")) {
            @Suppress("UNCHECKED_CAST")
            val unknown = case["unknown"] as List<Int>
            assertEquals(case["input"].toString(), ParsedPhrase(strings(case["words"]), unknown, case["normalized"] as String?), parsePhrase(case["input"] as String))
        }
        val phrase = generatePhrase()
        assertEquals(phrase, parsePhrase(phrase).normalized)
    }

    @Test
    fun recovery() {
        for (case in cases("recovery")) {
            val secret = recoverySecret(case["phrase"] as String, (case["saltHex"] as String).hexToByteArray(), kdf(case["kdf"]))
            assertEquals(case["secretHex"], toHex(secret))
            val sealed = case["sealed"] as Map<*, *>
            val stored = RecoverySealed(sealed["salt"] as String, sealed["sealedPrivateKey"] as String, kdf(sealed["kdf"]))
            assertEquals(case["plaintext"], unsealWithPhrase(case["phraseTyped"] as String, stored))
        }
    }

    @Test
    fun recoveryRoundTripsWithCheapSettings() {
        val cheap = RecoveryKdf("argon2id", memoryKiB = 1024, iterations = 1, parallelism = 1)
        val phrase = generatePhrase()
        val sealed = sealWithPhrase(phrase, "secret key", kdf = cheap)
        assertEquals(cheap, sealed.kdf)
        assertEquals("secret key", unsealWithPhrase(phrase, sealed))
        assertThrows(SealException::class.java) { unsealWithPhrase(generatePhrase(), sealed) }
        assertThrows(IllegalArgumentException::class.java) { sealWithPhrase("not a phrase", "x") }
    }

    @Test
    fun wkd() {
        for (case in cases("wkd")) {
            val address = case["address"] as String
            assertEquals(case["hash"], wkdHash(address.substringBefore('@')))
            assertEquals(WkdUrls(case["advanced"] as String, case["direct"] as String), wkdUrls(address))
        }
        for (case in cases("zbase32")) assertEquals(case["encoded"], zbase32((case["hex"] as String).hexToByteArray()))
    }

    @Test
    fun decisions() {
        for (case in cases("decision")) {
            val withKeys = strings(case["withKeys"])
            val recipients = strings(case["recipients"])
            val known = recipients.associate { it.lowercase() to if (it.lowercase() in withKeys) "key" else null }
            val expect = case["expect"] as Map<*, *>
            val decision = encryptionDecision(case["from"] as String, recipients, known)
            assertEquals(case.toString(), EncryptionDecision(expect["encrypt"] as Boolean, EncryptionReason.entries.first { it.wire == expect["reason"] }, strings(expect["missing"])), decision)
        }
    }

    @Test
    fun picksUsableKeys() {
        val binary = dearmor(bob.publicKey)
        val picked = keyFromAnswer(binary, "BOB@example.com")
        assertNotNull(picked)
        assertArrayEquals(binary, dearmor(picked!!))
        assertNotNull(keyFromAnswer(bob.publicKey, "bob@example.com"))
        assertNull(keyFromAnswer(bob.publicKey, "eve@example.com"))
        assertNull(keyFromAnswer(bob.privateKey, "bob@example.com"))
        assertNull(keyFromAnswer(byteArrayOf(1, 2, 3), "bob@example.com"))
        assertEquals(bob.fingerprint, fingerprintOf(bob.publicKey))
        assertEquals(bob.fingerprint, fingerprintOf(bob.privateKey))
    }

    @Suppress("UNCHECKED_CAST")
    private val autocrypt = fixtures["autocrypt"] as Map<String, List<Map<String, Any?>>>

    @Test
    fun autocrypt() {
        for (case in autocrypt.getValue("build")) {
            assertEquals(case["value"], autocryptHeaderValue(case["address"] as String, case["publicKey"] as String))
        }
        for (case in autocrypt.getValue("parse")) {
            val parsed = parseAutocrypt(case["value"] as String)
            val expect = case["expect"] as Map<*, *>?
            val actual = parsed?.let { mapOf("addr" to it.addr, "preferEncrypt" to it.preferEncrypt, "keydata" to toBase64(it.keydata)) }
            assertEquals(case["value"].toString(), expect, actual)
        }
        for (case in autocrypt.getValue("for")) {
            assertEquals(case["expect"], autocryptFor(strings(case["values"]), case["from"] as String)?.addr)
        }
        val address = FixtureMessages.address("alice")
        val parsed = parseAutocrypt(autocryptHeaderValue(address, alice.publicKey))
        assertNotNull(keyFromAnswer(parsed!!.keydata, address))
    }

    private fun person(raw: Any?): Person {
        val map = raw as Map<*, *>
        return Person(map["name"] as String, map["email"] as String)
    }

    private fun composeInput(input: Map<*, *>): ComposeInput = ComposeInput(
        from = person(input["from"]),
        to = (input["to"] as List<*>).map(::person),
        cc = (input["cc"] as List<*>).map(::person),
        bcc = (input["bcc"] as List<*>).map(::person),
        subject = input["subject"] as String,
        markdown = input["markdown"] as String,
        signature = input["signature"] as String?,
        inReplyTo = input["inReplyTo"] as String?,
        references = strings(input["references"]),
        inlineImages = emptyList(),
        attachments = emptyList(),
        extraHeaders = (input["extraHeaders"] as List<*>).map {
            val pair = it as List<*>
            pair[0] as String to pair[1] as String
        },
        boundary = input["boundary"] as String,
    )

    @Test
    fun protectsAndAssembles() {
        for (case in cases("protect")) {
            val mime = case["mime"] as String
            assertEquals(mime, buildMime(composeInput(case["composeInput"] as Map<*, *>)))
            assertEquals(ProtectedParts(strings(case["outerHeaders"]), case["inner"] as String), protectedParts(mime))
            assertEquals(case["assembled"], assemblePgpMime(strings(case["outerHeaders"]), case["placeholder"] as String, case["boundary"] as String))
        }
    }

    @Test
    fun encryptsSoTheRecipientGetsTheInnerPartBack() {
        for (case in cases("protect")) {
            val encrypted = encryptMime(
                case["mime"] as String,
                listOf(bob.publicKey, alice.publicKey),
                alice.privateKey,
                case["boundary"] as String,
            )
            val parsed = parseMime(encrypted)
            assertEquals("multipart/encrypted", parsed.type)
            assertEquals("application/pgp-encrypted", parsed.params["protocol"])
            assertEquals(EncryptionKind.PGP_MIME, encryptionKind(parsed))
            assertFalse(encrypted.contains("noon"))
            assertTrue(encrypted.contains("Subject: ...\r\n"))
            for (reader in listOf(bob, alice)) {
                val opened = decryptMessage(encrypted.toByteArray(), listOf(reader.privateKey), listOf(alice.publicKey))
                assertEquals(case["inner"], opened.entity.raw)
                assertEquals(SignatureStatus.VALID, opened.signature.status)
            }
        }
    }

    @Test
    fun decrypts() {
        for (case in cases("decrypt")) {
            val outcome = FixtureMessages.outcome(
                FixtureMessages.message(case),
                keys.getValue(case["decryptionKey"] as String).privateKey,
                strings(case["verificationKeys"]).map { keys.getValue(it).publicKey },
            )
            assertEquals(case["name"] as String, case["expect"], outcome)
        }
    }

    @Test
    fun headerDecode() {
        for (case in cases("headerDecode")) {
            assertEquals(case["value"] as String, case["expect"], decodeHeaderValue(case["value"] as String))
        }
    }

    @Test
    fun generatedKeysCarryTheAddressAndWorkAtOnce() {
        val pair = generateKeyPair("dev@hafa.cc", "")
        assertTrue(pair.publicKey.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertTrue(pair.privateKey.contains("BEGIN PGP PRIVATE KEY BLOCK"))
        val ring = readPublicKey(pair.publicKey)
        assertEquals(listOf("<dev@hafa.cc>"), ring.publicKey.userIDs.asSequence().toList())
        assertEquals(pair.fingerprint, fingerprintOf(pair.publicKey))
        assertEquals(pair.fingerprint, fingerprintOf(pair.privateKey))
        assertEquals(22, ring.publicKey.algorithm)
        assertNotNull(keyFromAnswer(pair.publicKey, "dev@hafa.cc"))
        val mime = "From: <dev@hafa.cc>\r\nTo: <bob@example.com>\r\nSubject: Hi\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n"
        val encrypted = encryptMime(mime, listOf(bob.publicKey, pair.publicKey), pair.privateKey, "b")
        val opened = decryptMessage(encrypted.toByteArray(), listOf(bob.privateKey), listOf(pair.publicKey))
        assertEquals(SignatureStatus.VALID, opened.signature.status)
        assertEquals("Hi", opened.protectedHeaders?.get("subject"))
        assertThrows(PgpException::class.java) {
            decryptMessage(encrypted.toByteArray(), listOf(keys.getValue("carol").privateKey), emptyList())
        }
    }

    @Test
    fun base64BodiesKeepTheirLastBytes() {
        val entity = parseMime("Content-Transfer-Encoding: base64\r\n\r\nQUJD\r\nRA==\r\n")
        assertArrayEquals("ABCD".toByteArray(), decodedBody(entity))
    }
}
