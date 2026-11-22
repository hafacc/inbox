package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.CRLF
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.compose.foldHeader
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Date

// PGP/MIME (RFC 3156) with protected headers; a port of shared/src/pgp/pgp-mime.ts.

/** Outer `Subject` of an encrypted message; the real one travels inside. */
const val OBSCURED_SUBJECT = "..."

/** Headers copied into the encrypted part (and, but for Subject, kept outside too). Never Bcc. */
val PROTECTED_HEADERS = listOf("From", "To", "Cc", "Reply-To", "Subject", "Date", "Message-ID", "In-Reply-To", "References")

/** Preamble of the multipart/encrypted body, for readers that can't decrypt. */
const val PGP_MIME_PREAMBLE = "This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156)"

private val CONTENT_HEADER = Regex("^content-", RegexOption.IGNORE_CASE)
private val PROTECTED_NAMES = PROTECTED_HEADERS.map { it.lowercase() }.toSet()
private const val BUFFER_SIZE = 1 shl 16

/** Why an encrypted message couldn't be opened. */
enum class PgpErrorCode(
    /** the code as web and the server write it */
    val wire: String,
) {
    /** not PGP/MIME and no inline PGP block */
    NOT_ENCRYPTED("not-encrypted"),

    /** encrypted, but to none of the keys given ("Can't open: encrypted to another key") */
    WRONG_KEY("wrong-key"),

    /** damaged or not OpenPGP */
    MALFORMED("malformed"),
}

/** An encrypted message couldn't be opened. */
class PgpException(
    /** why */
    val code: PgpErrorCode,
    message: String,
) : Exception(message)

/** A message split for PGP/MIME: the outer header lines and the part to encrypt. */
data class ProtectedParts(
    /** outer header fields, folded, without the multipart/encrypted Content-Type */
    val outerHeaders: List<String>,
    /** the cryptographic payload: content headers, protected headers, `HP-Outer`s, then the body */
    val inner: String,
)

private val BCC_COPY_HIDDEN = setOf("to", "cc")

/**
 * Split a built message (`buildMime`'s output) for encryption, with protected headers in the
 * style of RFC 9788 and the older `protected-headers="v1"` that Thunderbird and Proton read:
 * outside, every header but the `Content-*` ones with `Subject` replaced by [OBSCURED_SUBJECT];
 * inside, the `Content-*` headers (the Content-Type gaining `protected-headers="v1"` and
 * `hp="cipher"`), the [PROTECTED_HEADERS] present as written (never Bcc), one `HP-Outer` per
 * protected header also outside, then a blank line and the original body.
 *
 * With [bccCopy] (a Bcc recipient's copy, [planEncryptedSends]), `To` and `Cc` are left outside
 * (so delivery goes to the `Bcc` recipient alone, and they get no `HP-Outer`), and `Bcc` goes
 * inside too, in its place among the protected headers.
 */
fun protectedParts(mime: String, bccCopy: Boolean = false): ProtectedParts {
    val match = BLANK_LINE.find(mime) ?: throw IllegalArgumentException("message has no body")
    val fields = parseHeaderFields(mime.substring(0, match.range.first))
    val body = mime.substring(match.range.last + 1)
    val (content, headers) = fields.partition { CONTENT_HEADER.containsMatchIn(it.name) }
    val outer = headers.filterNot { bccCopy && it.name.lowercase() in BCC_COPY_HIDDEN }
    val outerHeaders = outer.map { if (it.name.equals("subject", ignoreCase = true)) "Subject: $OBSCURED_SUBJECT" else it.text }
    val insideNames = if (bccCopy) PROTECTED_NAMES + "bcc" else PROTECTED_NAMES
    val contentType = content.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
    val innerContent = listOf(
        foldHeader("Content-Type", "${contentType?.value ?: "text/plain; charset=UTF-8"}; protected-headers=\"v1\"; hp=\"cipher\""),
    ) + content.filter { it !== contentType }.map { it.text }
    val kept = headers.filter { it.name.lowercase() in insideNames }
    val hpOuter = kept.filter { it.name.lowercase() in PROTECTED_NAMES && outer.any { field -> field === it } }.map {
        foldHeader("HP-Outer", "${it.name}: ${if (it.name.equals("subject", ignoreCase = true)) OBSCURED_SUBJECT else it.value}")
    }
    return ProtectedParts(outerHeaders, (innerContent + kept.map { it.text } + hpOuter + "" + body).joinToString(CRLF))
}

private val TRAILING_CRLFS = Regex("""(\r\n)+$""")

/** The whole PGP/MIME message (RFC 3156 §4) around an armored OpenPGP message. */
fun assemblePgpMime(outerHeaders: List<String>, armored: String, boundary: String): String {
    val delimiter = "=_${boundary}_enc"
    val armoredLines = armored.replace(LINE_BREAK, CRLF).replace(TRAILING_CRLFS, "")
    return (
        outerHeaders + listOf(
            foldHeader("Content-Type", "multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"$delimiter\""),
            "",
            PGP_MIME_PREAMBLE,
            "--$delimiter",
            "Content-Type: application/pgp-encrypted",
            "Content-Description: PGP/MIME version identification",
            "",
            "Version: 1",
            "",
            "--$delimiter",
            "Content-Type: application/octet-stream; name=\"encrypted.asc\"",
            "Content-Description: OpenPGP encrypted message",
            "Content-Disposition: inline; filename=\"encrypted.asc\"",
            "",
            armoredLines,
            "--$delimiter--",
            "",
        )
        ).joinToString(CRLF)
}

private fun signingKeyOf(ring: PGPSecretKeyRing): PGPSecretKey =
    ring.secretKeys.asSequence().firstOrNull { it.isSigningKey && !it.isPrivateKeyEmpty && it.isMasterKey }
        ?: ring.secretKeys.asSequence().firstOrNull { it.isSigningKey && !it.isPrivateKeyEmpty }
        ?: throw IllegalArgumentException("the key can't sign")

/** Sign and encrypt [data] to the given keys as one armored OpenPGP message (SEIPD v1, AES-256, no compression). */
internal fun encryptAndSign(data: ByteArray, encryptionKeys: List<String>, signingKey: String, date: Date): String {
    val recipients = encryptionKeys.map { armored ->
        encryptionKeyOf(readPublicKey(armored)) ?: throw IllegalArgumentException("a key can't be encrypted to")
    }
    val secretKey = signingKeyOf(readUnlockedPrivateKey(signingKey))
    val privateKey = requireNotNull(privateKeyOf(secretKey)) { "the private key is locked" }
    val generator = PGPEncryptedDataGenerator(
        BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256).setWithIntegrityPacket(true).setSecureRandom(pgpRandom),
    )
    for (key in recipients) generator.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(key).setSecureRandom(pgpRandom))
    val signer = PGPSignatureGenerator(BcPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA512), secretKey.publicKey)
    signer.init(PGPSignature.BINARY_DOCUMENT, privateKey)
    signer.setHashedSubpackets(
        PGPSignatureSubpacketGenerator().apply {
            setSignatureCreationTime(true, date)
            setIssuerFingerprint(false, secretKey)
        }.generate(),
    )
    val out = ByteArrayOutputStream()
    val armored = ArmoredOutputStream.builder().clearHeaders().build(out)
    val encrypted = generator.open(armored, ByteArray(BUFFER_SIZE))
    signer.generateOnePassVersion(false).encode(encrypted)
    val literalGenerator = PGPLiteralDataGenerator()
    literalGenerator.open(encrypted, PGPLiteralData.BINARY, "", data.size.toLong(), date).use { literal ->
        literal.write(data)
        signer.update(data)
    }
    signer.generate().encode(encrypted)
    encrypted.close()
    armored.close()
    return out.toString("US-ASCII")
}

/**
 * Encrypt and sign a built message as PGP/MIME (RFC 3156, signed and encrypted in one OpenPGP
 * message, §6.2) with protected headers ([protectedParts]).
 *
 * [encryptionKeys] are armored public keys of every recipient (To, Cc and Bcc) **and the
 * sender's own**, so the Sent copy and the draft stay readable. [signingKey] is the sender's
 * unlocked private key, armored. [boundary] is a fresh random token (`randomBoundary()`).
 * [bccCopy] is passed to [protectedParts].
 */
fun encryptMime(
    mime: String,
    encryptionKeys: List<String>,
    signingKey: String,
    boundary: String,
    date: Date = Date(),
    bccCopy: Boolean = false,
): String {
    val (outerHeaders, inner) = protectedParts(mime, bccCopy)
    val armored = encryptAndSign(inner.toByteArray(Charsets.UTF_8), encryptionKeys, signingKey, date)
    return assemblePgpMime(outerHeaders, armored, boundary)
}

/** How a message is encrypted. */
enum class EncryptionKind {
    /** PGP/MIME (RFC 3156) */
    PGP_MIME,

    /** an inline armored block in the body */
    INLINE,
}

private const val INLINE_BEGIN = "-----BEGIN PGP MESSAGE-----"
private val INLINE_BLOCK = Regex("""-----BEGIN PGP MESSAGE-----[\s\S]*?-----END PGP MESSAGE-----""")

private fun isPgpMime(entity: MimeEntity): Boolean =
    entity.type == "multipart/encrypted" && entity.params["protocol"].orEmpty().lowercase() == "application/pgp-encrypted"

/**
 * The PGP/MIME entity of a message: the root, or one of its direct children when a mailing list
 * wrapped it in a multipart/mixed with a footer; null when neither is.
 */
private fun pgpMimeEntity(entity: MimeEntity): MimeEntity? = when {
    isPgpMime(entity) -> entity
    entity.type.startsWith("multipart/") -> entity.parts.firstOrNull(::isPgpMime)
    else -> null
}

private fun inlinePart(entity: MimeEntity): MimeEntity? =
    walk(entity).firstOrNull { part ->
        part.type == "text/plain" &&
            header(part, "Content-Disposition")?.lowercase()?.startsWith("attachment") != true &&
            decodedText(part).contains(INLINE_BEGIN)
    }

/** Whether a parsed message is encrypted, and how; null when it isn't. */
fun encryptionKind(entity: MimeEntity): EncryptionKind? = when {
    pgpMimeEntity(entity) != null -> EncryptionKind.PGP_MIME
    inlinePart(entity) != null -> EncryptionKind.INLINE
    else -> null
}

/** Signature check result. */
enum class SignatureStatus(
    /** the result as web and the cache write it */
    val wire: String,
) {
    /** signed by one of the verification keys, and it checks out */
    VALID("valid"),

    /** signed by one of the verification keys, and it doesn't check out (show a warning) */
    INVALID("invalid"),

    /** signed by a key we don't have for the sender */
    UNKNOWN_KEY("unknown-key"),

    /** not signed */
    UNSIGNED("unsigned"),
}

/** The sender's signature: its result and the signing key id (hex), or null when unsigned. */
data class SignatureResult(
    /** the result */
    val status: SignatureStatus,
    /** signing key id, hex; null when unsigned */
    val keyId: String?,
)

/** A decrypted message. */
data class DecryptedMessage(
    /** how it was encrypted */
    val kind: EncryptionKind,
    /** the decrypted content: the inner MIME entity, or for inline PGP a text/plain entity */
    val entity: MimeEntity,
    /**
     * protected headers from inside, names lower-case, values decoded (`subject`, `from`, `to`,
     * `cc`, `reply-to`, `date`, `message-id`, `in-reply-to`, `references`, and in a Bcc copy
     * `bcc`); null when there are none. Show these instead of the outer ones.
     *
     * The address headers ([ADDRESS_HEADERS]) come from inside only when the signature is `VALID`
     * and the signing key has a user id for the inner From address; otherwise each holds the outer
     * header's decoded value (left out when there is none outside).
     */
    val protectedHeaders: Map<String, String>?,
    /**
     * address headers (lower-case names) whose inner value differed from the outer one and was not
     * used because the signature doesn't vouch for it; empty when none (show a warning when not)
     */
    val mismatchedHeaders: List<String>,
    /** the sender's signature */
    val signature: SignatureResult,
)

/** Protected headers that name people: taken from inside only under a matching valid signature. */
val ADDRESS_HEADERS = listOf("from", "reply-to", "to", "cc")

private val BRACKETED_ADDRESS = Regex("""<([^<>]*)>""")
private val SOLE_ADDRESS = Regex("""^[^\s@,;<>]+@[^\s@,;<>]+$""")
private val WHITESPACE_RUN = Regex("""\s+""")

/** The one address in a From value (`Name <a@b>` or `a@b`), lower-case, or null. */
private fun soleAddress(value: String): String? {
    val bracketed = BRACKETED_ADDRESS.findAll(value).toList()
    if (bracketed.size > 1) return null
    val address = addressKey(bracketed.firstOrNull()?.groupValues?.get(1) ?: value)
    return if (SOLE_ADDRESS.matches(address)) address else null
}

/** Whether the signature is valid and made by a key with a user id for the inner From address. */
private fun vouchesForFrom(signature: SignatureResult, inner: Map<String, String>, verificationKeys: List<PGPKeyRing>): Boolean {
    val keyId = signature.keyId
    if (signature.status != SignatureStatus.VALID || keyId == null) return false
    val from = soleAddress(inner["from"] ?: "") ?: return false
    val id = keyId.toULong(16).toLong()
    return verificationKeys.any { ring -> ring.getPublicKey(id) != null && from in keyAddresses(ring) }
}

private fun sameValue(first: String?, second: String?): Boolean {
    fun normalize(value: String?) = value?.replace(WHITESPACE_RUN, " ")?.trim()
    return normalize(first) == normalize(second)
}

private class TrustedHeaders(val headers: Map<String, String>?, val mismatched: List<String>)

/**
 * The protected headers to show: the inner ones, but the address headers from outside unless the
 * signature vouches for the inner From ([vouchesForFrom]).
 */
private fun trustedHeaders(
    inner: Map<String, String>?,
    outer: MimeEntity,
    signature: SignatureResult,
    verificationKeys: List<PGPKeyRing>,
): TrustedHeaders {
    if (inner == null) return TrustedHeaders(null, emptyList())
    if (vouchesForFrom(signature, inner, verificationKeys)) return TrustedHeaders(inner, emptyList())
    val headers = LinkedHashMap(inner)
    val mismatched = mutableListOf<String>()
    for (name in ADDRESS_HEADERS) {
        val outerValue = header(outer, name)?.let(::decodeHeaderValue)
        if (name in inner && !sameValue(inner[name], outerValue)) mismatched += name
        if (outerValue == null) {
            headers.remove(name)
        } else if (name in inner) {
            headers[name] = outerValue
        }
    }
    return TrustedHeaders(headers, mismatched)
}

private fun keyIdHex(keyId: Long): String = "%016x".format(keyId)

private fun signerKey(rings: List<PGPKeyRing>, keyId: Long) =
    rings.firstNotNullOfOrNull { it.getPublicKey(keyId) }

private fun syntheticText(text: String): MimeEntity =
    parseMime("Content-Type: text/plain; charset=utf-8$CRLF$CRLF${bytesToBinary(text.toByteArray(Charsets.UTF_8))}")

private fun protectedHeadersOf(entity: MimeEntity): Map<String, String>? {
    val found = linkedMapOf<String, String>()
    for (field in entity.headers) {
        val name = field.name.lowercase()
        if ((name in PROTECTED_NAMES || name == "bcc") && name !in found) found[name] = decodeHeaderValue(field.value)
    }
    return found.ifEmpty { null }
}

private class Opened(val data: ByteArray, val signature: SignatureResult)

private fun malformed(error: Throwable): PgpException = PgpException(PgpErrorCode.MALFORMED, error.message ?: "malformed")

private fun encryptedDataOf(armored: String): PGPEncryptedDataList =
    try {
        val factory = PGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.ISO_8859_1))), fingerprints)
        var item = factory.nextObject()
        while (item != null && item !is PGPEncryptedDataList) item = factory.nextObject()
        if (item is PGPEncryptedDataList) item else throw PgpException(PgpErrorCode.MALFORMED, "no encrypted data")
    } catch (error: PgpException) {
        throw error
    } catch (error: Exception) {
        throw malformed(error)
    }

private fun decryptingKeys(rings: List<PGPSecretKeyRing>): List<PGPSecretKey> =
    rings.flatMap { it.secretKeys.asSequence().filter { key -> !key.isPrivateKeyEmpty }.toList() }

private class Content(val data: ByteArray, val onePasses: List<PGPOnePassSignature>, val signatures: List<PGPSignature>)

private fun readContent(stream: InputStream): Content {
    var factory = PGPObjectFactory(stream, fingerprints)
    val onePasses = mutableListOf<PGPOnePassSignature>()
    val signatures = mutableListOf<PGPSignature>()
    var data: ByteArray? = null
    while (true) {
        when (val item = factory.nextObject() ?: break) {
            is PGPCompressedData -> factory = PGPObjectFactory(item.dataStream, fingerprints)
            is PGPOnePassSignatureList -> onePasses += item
            is PGPSignatureList -> signatures += item
            is PGPLiteralData -> data = item.inputStream.readBytes()
            else -> Unit
        }
    }
    return Content(data ?: throw PgpException(PgpErrorCode.MALFORMED, "no literal data"), onePasses, signatures)
}

/**
 * The result for the first signature by one of [verificationKeys]; `UNKNOWN_KEY` (with the first
 * signature's key id) when none is by one of them. Signatures are taken in the order of their
 * signature packets (as openpgp.js reports them), else of their one-pass packets.
 */
private fun signatureOf(content: Content, verificationKeys: List<PGPKeyRing>): SignatureResult {
    val keyIds = content.signatures.map { it.keyID }.ifEmpty { content.onePasses.map { it.keyID } }
    val firstKeyId = keyIds.firstOrNull() ?: return SignatureResult(SignatureStatus.UNSIGNED, null)
    val keyId = keyIds.firstOrNull { signerKey(verificationKeys, it) != null }
        ?: return SignatureResult(SignatureStatus.UNKNOWN_KEY, keyIdHex(firstKeyId))
    val signer = signerKey(verificationKeys, keyId)!!
    val onePass = content.onePasses.firstOrNull { it.keyID == keyId }
    val signature = content.signatures.firstOrNull { it.keyID == keyId }
    val valid = try {
        when {
            signature == null -> false
            onePass != null -> {
                onePass.init(verifiers, signer)
                onePass.update(content.data)
                onePass.verify(signature)
            }
            else -> {
                signature.init(verifiers, signer)
                signature.update(content.data)
                signature.verify()
            }
        }
    } catch (_: Exception) {
        false
    }
    return SignatureResult(if (valid) SignatureStatus.VALID else SignatureStatus.INVALID, keyIdHex(keyId))
}

private fun openArmored(armored: String, decryptionKeys: List<PGPSecretKeyRing>, verificationKeys: List<PGPKeyRing>): Opened {
    val list = encryptedDataOf(armored)
    val sessions = list.encryptedDataObjects.asSequence().filterIsInstance<PGPPublicKeyEncryptedData>().toList()
    val ours = decryptingKeys(decryptionKeys)
    val candidates = sessions.flatMap { session ->
        val identifier = session.keyIdentifier
        val matching = if (identifier.isWildcard) ours else ours.filter { it.keyID == identifier.keyId }
        matching.map { session to it }
    }
    if (candidates.isEmpty()) throw PgpException(PgpErrorCode.WRONG_KEY, "encrypted to another key")
    var opened: Pair<PGPPublicKeyEncryptedData, InputStream>? = null
    for ((session, secretKey) in candidates) {
        val privateKey = privateKeyOf(secretKey) ?: continue
        opened = try {
            session to session.getDataStream(BcPublicKeyDataDecryptorFactory(PGPKeyPair(secretKey.publicKey, privateKey)))
        } catch (_: Exception) {
            continue
        }
        break
    }
    val (session, stream) = opened ?: throw PgpException(PgpErrorCode.WRONG_KEY, "no session key for our keys")
    if (!session.isIntegrityProtected) throw PgpException(PgpErrorCode.MALFORMED, "not integrity protected")
    val content = try {
        readContent(stream)
    } catch (error: PgpException) {
        throw error
    } catch (error: Exception) {
        throw malformed(error)
    }
    val intact = try {
        session.verify()
    } catch (error: Exception) {
        throw malformed(error)
    }
    if (!intact) throw PgpException(PgpErrorCode.MALFORMED, "integrity check failed")
    return Opened(content.data, signatureOf(content, verificationKeys))
}

private fun canonical(text: String): String = text.replace(LINE_BREAK, CRLF)

private fun isPgpSigned(entity: MimeEntity): Boolean =
    entity.type == "multipart/signed" && entity.params["protocol"].orEmpty().lowercase() == "application/pgp-signature"

private class VerifiedEntity(val entity: MimeEntity, val signature: SignatureResult)

// Check a multipart/signed (RFC 3156 §5) entity against the sender's keys.
private fun verifySignedWith(entity: MimeEntity, keys: List<PGPKeyRing>): VerifiedEntity {
    val content = entity.parts.getOrNull(0)
    val signaturePart = entity.parts.getOrNull(1)
    if (content == null || signaturePart == null) throw PgpException(PgpErrorCode.MALFORMED, "multipart/signed without two parts")
    val signatures = try {
        val factory = PGPObjectFactory(
            PGPUtil.getDecoderStream(ByteArrayInputStream(decodedText(signaturePart).toByteArray(Charsets.ISO_8859_1))),
            fingerprints,
        )
        val found = mutableListOf<PGPSignature>()
        while (true) {
            val item = factory.nextObject() ?: break
            if (item is PGPSignatureList) found += item
        }
        found.ifEmpty { throw PgpException(PgpErrorCode.MALFORMED, "no signature") }
    } catch (error: PgpException) {
        throw error
    } catch (error: Exception) {
        throw malformed(error)
    }
    val signature = signatures.firstOrNull { signerKey(keys, it.keyID) != null }
        ?: return VerifiedEntity(content, SignatureResult(SignatureStatus.UNKNOWN_KEY, keyIdHex(signatures.first().keyID)))
    val keyId = signature.keyID
    val signer = signerKey(keys, keyId)!!
    val valid = try {
        signature.init(verifiers, signer)
        signature.update(binaryToBytes(canonical(content.raw)))
        signature.verify()
    } catch (_: Exception) {
        false
    }
    return VerifiedEntity(content, SignatureResult(if (valid) SignatureStatus.VALID else SignatureStatus.INVALID, keyIdHex(keyId)))
}

/**
 * Decrypt a message as Gmail's `raw` gives it (bytes): PGP/MIME, or inline PGP in a text/plain
 * part from older clients. [decryptionKeys]: the account's unlocked private key(s), armored.
 * [verificationKeys]: the keys we have for the sender (from Autocrypt, WKD or keys.openpgp.org);
 * empty gives `UNKNOWN_KEY` for a signed message. A PGP/MIME message signed inside as
 * multipart/signed (RFC 3156 §6.1) is unwrapped and that signature checked. The multipart/encrypted
 * may be the root or a direct child of it (a mailing list's footer wrapper). Address headers in
 * [DecryptedMessage.protectedHeaders] are the inner ones only under a signature that vouches for
 * the inner From. Throws [PgpException].
 */
fun decryptMessage(raw: ByteArray, decryptionKeys: List<String>, verificationKeys: List<String>): DecryptedMessage =
    decryptMessage(parseMime(bytesToBinary(raw)), decryptionKeys, verificationKeys)

/** [decryptMessage] on a message already parsed. */
internal fun decryptMessage(outer: MimeEntity, decryptionKeys: List<String>, verificationKeys: List<String>): DecryptedMessage {
    val secretRings = decryptionKeys.map(::readUnlockedPrivateKey)
    val publicRings = verificationKeys.map<String, PGPKeyRing>(::readPublicKey)
    val encrypted = pgpMimeEntity(outer)
    if (encrypted != null) {
        val payload = encrypted.parts.getOrNull(1) ?: throw PgpException(PgpErrorCode.MALFORMED, "multipart/encrypted without a payload")
        val opened = openArmored(decodedText(payload), secretRings, publicRings)
        var entity = parseMime(bytesToBinary(opened.data))
        var signature = opened.signature
        if (isPgpSigned(entity) && signature.status == SignatureStatus.UNSIGNED) {
            val checked = verifySignedWith(entity, publicRings)
            entity = checked.entity
            signature = checked.signature
        }
        val trusted = trustedHeaders(protectedHeadersOf(entity), outer, signature, publicRings)
        return DecryptedMessage(EncryptionKind.PGP_MIME, entity, trusted.headers, trusted.mismatched, signature)
    }
    val part = inlinePart(outer)
    val block = part?.let { INLINE_BLOCK.find(decodedText(it)) } ?: throw PgpException(PgpErrorCode.NOT_ENCRYPTED, "not encrypted")
    val opened = openArmored(block.value, secretRings, publicRings)
    val text = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(opened.data)).toString()
    } catch (_: CharacterCodingException) {
        bytesToBinary(opened.data)
    }
    return DecryptedMessage(EncryptionKind.INLINE, syntheticText(text), null, emptyList(), opened.signature)
}
