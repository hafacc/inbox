package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.CRLF
import cc.hafa.inbox.core.testing.Fixtures
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Date

// Test-only: key pairs made fresh on every run for the people in pgp-fixtures.json, and the
// messages its decrypt cases describe, built with those keys; a port of
// shared/src/pgp/test-fixtures.ts.

/** Test key pairs and the decrypt cases' messages. */
object FixtureMessages {
    private val KEY_DATE = Date.from(Instant.parse("2026-01-01T00:00:00Z"))
    private val SIGN_DATE = Date.from(Instant.parse("2026-01-02T00:00:00Z"))
    private const val BUFFER_SIZE = 1 shl 16
    private const val BASE64_LINE = 76
    private const val PAD_GROUP = 3
    private const val ONE_PAD = 2

    @Suppress("UNCHECKED_CAST")
    private val people by lazy { Fixtures.load("pgp-fixtures.json")["keys"] as Map<String, Map<String, String>> }

    /** Key pairs for the fixture file's people, by id, made once per test run. */
    val keys: Map<String, KeyPair> by lazy {
        people.mapValues { (_, person) -> generateKeyPair(person.getValue("address"), person.getValue("name"), KEY_DATE) }
    }

    /** A fixture person's address. */
    fun address(id: String): String = people.getValue(id).getValue("address")

    private fun signingKey(armored: String): PGPSecretKey =
        readUnlockedPrivateKey(armored).secretKeys.asSequence().first { it.isSigningKey && !it.isPrivateKeyEmpty }

    private fun signer(id: String, type: Int): PGPSignatureGenerator {
        val secretKey = signingKey(keys.getValue(id).privateKey)
        return PGPSignatureGenerator(BcPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA512), secretKey.publicKey).apply {
            init(type, privateKeyOf(secretKey))
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setSignatureCreationTime(true, SIGN_DATE)
                    setIssuerFingerprint(false, secretKey)
                }.generate(),
            )
        }
    }

    private fun armoredLines(armored: String): String = armored.replace(Regex("\r?\n"), CRLF).trimEnd()

    private fun detachedSignature(id: String, over: String): String {
        val generator = signer(id, PGPSignature.BINARY_DOCUMENT)
        generator.update(over.toByteArray(Charsets.UTF_8))
        return armor(generator.generate().encoded)
    }

    private fun encrypt(data: ByteArray, text: Boolean, to: List<String>, signers: List<String>): String {
        val generator = PGPEncryptedDataGenerator(
            BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(pgpRandom),
        )
        for (id in to) {
            val key = requireNotNull(encryptionKeyOf(readPublicKey(keys.getValue(id).publicKey)))
            generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(key).setSecureRandom(pgpRandom))
        }
        val type = if (text) PGPSignature.CANONICAL_TEXT_DOCUMENT else PGPSignature.BINARY_DOCUMENT
        val signatures = signers.map { signer(it, type) }
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream.builder().clearHeaders().build(out)
        val encrypted = generator.open(armored, ByteArray(BUFFER_SIZE))
        // A one-pass signature packet marked nested is followed by another one.
        signatures.forEachIndexed { index, signature -> signature.generateOnePassVersion(index != signatures.lastIndex).encode(encrypted) }
        val format = if (text) PGPLiteralData.UTF8 else PGPLiteralData.BINARY
        PGPLiteralDataGenerator().open(encrypted, format, "", data.size.toLong(), SIGN_DATE).use { literal ->
            literal.write(data)
            signatures.forEach { it.update(data) }
        }
        signatures.asReversed().forEach { it.generate().encode(encrypted) }
        encrypted.close()
        armored.close()
        return out.toString("US-ASCII")
    }

    private fun base64Lines(bytes: ByteArray): String {
        val encoded = toBase64(bytes)
        val lines = encoded.chunked(BASE64_LINE).joinToString(CRLF)
        return if (encoded.isNotEmpty() && encoded.length % BASE64_LINE == 0) lines + CRLF else lines
    }

    private fun base64WithOnePad(armored: String): String =
        generateSequence(0) { it + 1 }
            .map { marks -> "Hi${"!".repeat(marks)}$CRLF$CRLF$armored".toByteArray(Charsets.UTF_8) }
            .first { it.size % PAD_GROUP == ONE_PAD }
            .let(::base64Lines)

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private fun kindWire(kind: EncryptionKind?): String? = when (kind) {
        EncryptionKind.PGP_MIME -> "pgp-mime"
        EncryptionKind.INLINE -> "inline"
        null -> null
    }

    /**
     * Decrypt a message as a decrypt case does, in the shape of its `expect`; `kind` is null when
     * encryptionKind of the outer message differs from the decrypted one.
     */
    fun outcome(message: ByteArray, decryptionKey: String, verificationKeys: List<String>): Map<String, Any?> {
        val result = try {
            decryptMessage(message, listOf(decryptionKey), verificationKeys)
        } catch (error: PgpException) {
            return mapOf("error" to error.code.wire)
        }
        val text = walk(result.entity).firstOrNull { it.type == "text/plain" }
        val outerKind = encryptionKind(parseMime(bytesToBinary(message)))
        return mapOf(
            "kind" to if (outerKind == result.kind) kindWire(result.kind) else null,
            "signature" to result.signature.status.wire,
            "protectedHeaders" to result.protectedHeaders,
            "mismatchedHeaders" to result.mismatchedHeaders,
            "type" to result.entity.type,
            "text" to text?.let(::decodedText),
        )
    }

    /** The bytes of the message a decrypt case describes, made with this run's keys. */
    fun message(case: Map<String, Any?>): ByteArray {
        val viaMime = case["encryptMime"] as Map<*, *>?
        if (viaMime != null) {
            return encryptMime(
                viaMime["mime"] as String,
                strings(viaMime["to"]).map { keys.getValue(it).publicKey },
                keys.getValue(viaMime["signer"] as String).privateKey,
                viaMime["boundary"] as String,
                SIGN_DATE,
            ).toByteArray(Charsets.UTF_8)
        }
        var raw = case["raw"] as String
        val pgp = case["pgp"] as Map<*, *>?
        if (pgp != null) {
            var data = pgp["data"] as String
            val signature = pgp["signature"] as Map<*, *>?
            if (signature != null) {
                data = data.replace("{{signature}}", armoredLines(detachedSignature(signature["by"] as String, signature["over"] as String)))
            }
            val armored = armoredLines(encrypt(data.toByteArray(Charsets.UTF_8), pgp["format"] == "text", strings(pgp["to"]), strings(pgp["signers"])))
            raw = raw
                .replace("{{pgp}}", armored)
                .replace("{{pgp:qp}}", armored.replace("=", "=3D"))
                .replace("{{pgp:base64}}", base64WithOnePad(armored))
        }
        return raw.toByteArray(Charsets.UTF_8)
    }
}
