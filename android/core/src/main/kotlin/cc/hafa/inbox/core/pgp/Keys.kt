package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.addressKey
import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date

// OpenPGP keys with Bouncy Castle; a port of shared/src/pgp/keys.ts.

/** A new key pair, both halves armored; the private key is not passphrase-protected (it is sealed). */
data class KeyPair(
    /** armored public key, what `users/{uid}.pgp.publicKey` holds */
    val publicKey: String,
    /** armored private key, unprotected: seal it before it leaves memory */
    val privateKey: String,
    /** primary key fingerprint, lower-case hex */
    val fingerprint: String,
)

internal val fingerprints = BcKeyFingerprintCalculator()
internal val verifiers = BcPGPContentVerifierBuilderProvider()

private val ENCRYPTING_ALGORITHMS = setOf(
    PublicKeyAlgorithmTags.RSA_GENERAL,
    // RSA encrypt-only and Elgamal encrypt-or-sign, whose constants Bouncy Castle deprecates.
    2,
    20,
    PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT,
    PublicKeyAlgorithmTags.ECDH,
    PublicKeyAlgorithmTags.X25519,
    PublicKeyAlgorithmTags.X448,
)

private val USER_ID_CERTIFICATIONS = setOf(
    PGPSignature.DEFAULT_CERTIFICATION,
    PGPSignature.NO_CERTIFICATION,
    PGPSignature.CASUAL_CERTIFICATION,
    PGPSignature.POSITIVE_CERTIFICATION,
)

private const val MILLIS = 1000L

/** Armor key or message bytes, with no Version or Comment headers. */
internal fun armor(bytes: ByteArray): String {
    val out = ByteArrayOutputStream()
    ArmoredOutputStream.builder().clearHeaders().build(out).use { it.write(bytes) }
    return out.toString("US-ASCII")
}

/** The bytes inside the first armored block. */
internal fun dearmor(armored: String): ByteArray =
    ArmoredInputStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8))).use { it.readBytes() }

/**
 * Make an OpenPGP key pair for an address: an EdDSA (Ed25519) primary key for signing and
 * certifying, an ECDH Curve25519 subkey for encryption, in the v4 format with the "legacy"
 * Curve25519 algorithm ids, which GnuPG, Thunderbird, Proton, Mailvelope and FlowCrypt all read.
 * One user id, `name <address>` (just `<address>` when [name] is blank), no expiry.
 */
fun generateKeyPair(address: String, name: String, date: Date = Date()): KeyPair {
    val trimmed = name.trim()
    val userId = if (trimmed.isEmpty()) "<$address>" else "$trimmed <$address>"
    val signingPair = Ed25519KeyPairGenerator().run {
        init(Ed25519KeyGenerationParameters(pgpRandom))
        generateKeyPair()
    }
    val encryptionPair = X25519KeyPairGenerator().run {
        init(X25519KeyGenerationParameters(pgpRandom))
        generateKeyPair()
    }
    val primary = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.EDDSA_LEGACY, signingPair, date)
    val subkey = BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.ECDH, encryptionPair, date)
    val primaryPackets = PGPSignatureSubpacketGenerator().apply {
        setSignatureCreationTime(true, date)
        setKeyFlags(true, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
        setPreferredSymmetricAlgorithms(false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128))
        setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256))
        setPreferredCompressionAlgorithms(false, intArrayOf(CompressionAlgorithmTags.UNCOMPRESSED, CompressionAlgorithmTags.ZLIB))
        setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
        setPrimaryUserID(false, true)
    }.generate()
    val subkeyPackets = PGPSignatureSubpacketGenerator().apply {
        setSignatureCreationTime(true, date)
        setKeyFlags(true, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
    }.generate()
    val generator = PGPKeyRingGenerator(
        PGPSignature.POSITIVE_CERTIFICATION,
        primary,
        userId,
        BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
        primaryPackets,
        null,
        BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.EDDSA_LEGACY, HashAlgorithmTags.SHA512),
        null,
    )
    generator.addSubKey(subkey, subkeyPackets, null)
    val publicRing = generator.generatePublicKeyRing()
    return KeyPair(
        publicKey = armor(publicRing.encoded),
        privateKey = armor(generator.generateSecretKeyRing().encoded),
        fingerprint = toHex(publicRing.publicKey.fingerprint),
    )
}

/** Read key rings, armored or binary (a WKD answer is binary, keys.openpgp.org's armored). */
fun readKeyRings(data: ByteArray): List<PGPKeyRing> {
    val start = bytesToBinary(data.copyOfRange(0, minOf(data.size, 64))).trimStart()
    val bytes = if (start.startsWith("-----BEGIN PGP")) bytesToBinary(data).trimStart().toByteArray(Charsets.ISO_8859_1) else data
    val factory = PGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(bytes)), fingerprints)
    val rings = mutableListOf<PGPKeyRing>()
    while (true) {
        when (val item = factory.nextObject() ?: break) {
            is PGPPublicKeyRing -> rings += item
            is PGPSecretKeyRing -> rings += item
            else -> Unit
        }
    }
    return rings
}

/** Read key rings from armored text. */
fun readKeyRings(armored: String): List<PGPKeyRing> = readKeyRings(armored.toByteArray(Charsets.UTF_8))

private val ANGLED_ADDRESS = Regex("<([^<>]+)>")
private val BARE_ADDRESS = Regex("""^[^\s<>@]+@[^\s<>@]+$""")

/** The lower-cased address in a user id (`Name <a@b>` or a bare `a@b`), or null. */
internal fun userIdAddress(userId: String): String? {
    val angled = ANGLED_ADDRESS.findAll(userId).lastOrNull()?.groupValues?.get(1)?.trim()
    val email = angled ?: userId.trim().takeIf { BARE_ADDRESS.matches(it) }
    return email?.lowercase()?.takeIf { it.contains('@') }
}

/** The lower-cased addresses in a key's user ids. */
fun keyAddresses(ring: PGPKeyRing): List<String> =
    ring.publicKey.userIDs.asSequence().mapNotNull(::userIdAddress).toList()

private fun checks(signature: PGPSignature, signer: PGPPublicKey, verify: (PGPSignature) -> Boolean): Boolean =
    try {
        signature.init(verifiers, signer)
        verify(signature)
    } catch (_: Exception) {
        false
    }

private fun live(signature: PGPSignature, now: Date): Boolean {
    if (signature.creationTime.after(now)) return false
    val lifetime = signature.hashedSubPackets?.signatureExpirationTime ?: 0L
    return lifetime == 0L || signature.creationTime.time + lifetime * MILLIS > now.time
}

private fun expired(key: PGPPublicKey, selfSignature: PGPSignature, now: Date): Boolean {
    val lifetime = selfSignature.hashedSubPackets?.keyExpirationTime ?: 0L
    return lifetime > 0L && key.creationTime.time + lifetime * MILLIS <= now.time
}

private fun signaturesOf(key: PGPPublicKey, type: Int): List<PGPSignature> =
    key.getSignaturesOfType(type).asSequence().toList()

private fun primaryRevoked(primary: PGPPublicKey): Boolean =
    signaturesOf(primary, PGPSignature.KEY_REVOCATION).any { signature -> checks(signature, primary) { it.verifyCertification(primary) } }

/** The newest valid self-certification of a user id, or null when it has none or is revoked. */
private fun userIdCertification(primary: PGPPublicKey, userId: String, now: Date): PGPSignature? {
    val signatures = primary.getSignaturesForID(userId)?.asSequence()?.toList().orEmpty()
    val valid = signatures.filter { signature ->
        signature.keyID == primary.keyID && live(signature, now) &&
            (signature.signatureType in USER_ID_CERTIFICATIONS || signature.signatureType == PGPSignature.CERTIFICATION_REVOCATION) &&
            checks(signature, primary) { it.verifyCertification(userId, primary) }
    }
    if (valid.any { it.signatureType == PGPSignature.CERTIFICATION_REVOCATION }) return null
    return valid.maxByOrNull { it.creationTime }
}

/** The primary key's newest valid user id certification when the key is usable now; null when revoked or expired. */
private fun primaryCertification(ring: PGPKeyRing, now: Date, address: String? = null): PGPSignature? {
    val primary = ring.publicKey
    if (primaryRevoked(primary)) return null
    val certifications = primary.userIDs.asSequence()
        .filter { address == null || userIdAddress(it) == address }
        .mapNotNull { userIdCertification(primary, it, now) }
        .toList()
    val best = certifications.maxByOrNull { it.creationTime } ?: return null
    return if (expired(primary, best, now)) null else best
}

private fun flagsAllowEncryption(signature: PGPSignature, algorithm: Int): Boolean {
    val packets = signature.hashedSubPackets
    return if (packets != null && packets.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)) {
        packets.keyFlags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
    } else {
        algorithm in ENCRYPTING_ALGORITHMS
    }
}

/** The (sub)key to encrypt to now: valid, not revoked or expired, allowed to encrypt; the newest wins. */
internal fun encryptionKeyOf(ring: PGPKeyRing, now: Date = Date()): PGPPublicKey? {
    val primaryCertification = primaryCertification(ring, now) ?: return null
    val primary = ring.publicKey
    val candidates = mutableListOf<PGPPublicKey>()
    if (primary.algorithm in ENCRYPTING_ALGORITHMS && flagsAllowEncryption(primaryCertification, primary.algorithm)) {
        candidates += primary
    }
    for (key in ring.publicKeys.asSequence().filter { !it.isMasterKey }) {
        if (key.algorithm !in ENCRYPTING_ALGORITHMS) continue
        val revoked = signaturesOf(key, PGPSignature.SUBKEY_REVOCATION)
            .any { signature -> checks(signature, primary) { it.verifyCertification(primary, key) } }
        if (revoked) continue
        val binding = signaturesOf(key, PGPSignature.SUBKEY_BINDING)
            .filter { signature -> live(signature, now) && checks(signature, primary) { it.verifyCertification(primary, key) } }
            .maxByOrNull { it.creationTime } ?: continue
        if (expired(key, binding, now) || !flagsAllowEncryption(binding, key.algorithm)) continue
        candidates += key
    }
    return candidates.maxByOrNull { it.creationTime }
}

/** Whether a key can be encrypted to now: not revoked or expired, with a valid encryption (sub)key. */
fun canEncryptTo(ring: PGPKeyRing, now: Date = Date()): Boolean = encryptionKeyOf(ring, now) != null

/**
 * From keys found for an address, the one to encrypt to: a public key (private keys are refused)
 * with a valid user id for that address, that can be encrypted to. Returns it armored, or null.
 */
fun pickKeyFor(rings: List<PGPKeyRing>, address: String, now: Date = Date()): String? {
    val wanted = addressKey(address)
    for (ring in rings) {
        if (ring !is PGPPublicKeyRing) continue
        if (wanted !in keyAddresses(ring)) continue
        if (primaryCertification(ring, now, wanted) == null) continue
        if (canEncryptTo(ring, now)) return armor(ring.encoded)
    }
    return null
}

/** Read an armored public key; refuses private keys. */
fun readPublicKey(armored: String): PGPPublicKeyRing =
    readKeyRings(armored).firstOrNull() as? PGPPublicKeyRing ?: throw IllegalArgumentException("expected a public key")

/** Read an unprotected armored private key (as unsealed). */
fun readUnlockedPrivateKey(armored: String): PGPSecretKeyRing {
    val ring = readKeyRings(armored).firstOrNull() as? PGPSecretKeyRing ?: throw IllegalArgumentException("expected a private key")
    require(ring.secretKeys.asSequence().all { it.isPrivateKeyEmpty || privateKeyOf(it) != null }) { "the private key is locked" }
    return ring
}

/** A secret key's private half, when it isn't passphrase-protected. */
internal fun privateKeyOf(secretKey: PGPSecretKey): PGPPrivateKey? =
    try {
        secretKey.extractPrivateKey(null)
    } catch (_: Exception) {
        null
    }

/** The fingerprint (lower-case hex) of an armored key, public or private. */
fun fingerprintOf(armored: String): String =
    toHex(requireNotNull(readKeyRings(armored).firstOrNull()) { "no key" }.publicKey.fingerprint)
