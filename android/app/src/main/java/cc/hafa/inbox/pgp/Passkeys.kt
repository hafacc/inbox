package cc.hafa.inbox.pgp

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import cc.hafa.inbox.core.pgp.SEAL_SECRET_LENGTH
import cc.hafa.inbox.core.pgp.fromBase64
import cc.hafa.inbox.core.pgp.randomBytes
import cc.hafa.inbox.core.pgp.toBase64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Why a passkey step didn't give a secret. */
enum class PasskeyProblem {
    /** the user closed the prompt */
    CANCELED,

    /** the password manager can't do PRF, so it can't unlock mail */
    NO_PRF,

    /** no passkey on this device matches the account's list */
    NONE_FOUND,

    /** this password manager already holds one of the account's passkeys */
    ALREADY_HERE,

    /** anything else */
    FAILED,
}

/** A passkey step that didn't give a secret. */
class PasskeyException(
    /** what went wrong */
    val problem: PasskeyProblem,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A passkey made for the account, with its PRF secret. */
class CreatedPasskey(
    /** the credential id, base64url */
    val credentialId: String,
    /** the PRF salt, base64url */
    val salt: String,
    /** the PRF output */
    val secret: ByteArray,
)

/** A passkey that answered an unlock, with its PRF secret. */
class AssertedPasskey(
    /** the credential id, base64url */
    val credentialId: String,
    /** the PRF output */
    val secret: ByteArray,
)

/**
 * WebAuthn passkeys with the PRF extension through Credential Manager (docs/pgp-contract.md
 * "Passkeys"): the same JSON the web app passes to `navigator.credentials`, binary fields
 * base64url. Relying party `inbox.hafa.cc` on both platforms, so one synced passkey serves both;
 * Android needs `https://inbox.hafa.cc/.well-known/assetlinks.json` to list this app.
 */
object Passkeys {
    // The relying party id, shared with the web app.
    private const val RP_ID = "inbox.hafa.cc"

    private const val CHALLENGE_BYTES = 32
    private const val ES256 = -7
    private const val RS256 = -257
    private val json = Json { ignoreUnknownKeys = true }

    private fun prfFirst(responseJson: String): ByteArray? {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val prf = (root["clientExtensionResults"] as? JsonObject)?.get("prf") as? JsonObject ?: return null
        val first = ((prf["results"] as? JsonObject)?.get("first"))?.jsonPrimitive?.content ?: return null
        return fromBase64(first).takeIf { it.size == SEAL_SECRET_LENGTH }
    }

    private fun prfEnabled(responseJson: String): Boolean {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val prf = (root["clientExtensionResults"] as? JsonObject)?.get("prf") as? JsonObject ?: return false
        return prf["enabled"]?.jsonPrimitive?.content == "true" || prf["results"] != null
    }

    private fun credentialIdOf(responseJson: String): String =
        json.parseToJsonElement(responseJson).jsonObject["id"]?.jsonPrimitive?.content?.let(::normalizedId)
            ?: throw PasskeyException(PasskeyProblem.FAILED, "no credential id")

    /** A credential id as base64url without padding, however it was written. */
    fun normalizedId(id: String): String = runCatching { toBase64Url(fromBase64(id)) }.getOrDefault(id)

    /**
     * Make a passkey for the account and take its PRF output for a fresh salt. [exclude] lists the
     * account's passkeys already made, so a password manager holding one says so instead of
     * replacing it.
     */
    suspend fun create(context: Context, uid: String, gmailAddress: String, hafaAddress: String, exclude: List<String>): CreatedPasskey {
        val salt = toBase64Url(randomBytes(SEAL_SECRET_LENGTH))
        val request = buildJsonObject {
            putJsonObject("rp") {
                put("id", RP_ID)
                put("name", "inbox")
            }
            putJsonObject("user") {
                put("id", toBase64Url(uid.toByteArray(Charsets.UTF_8)))
                put("name", gmailAddress)
                put("displayName", hafaAddress)
            }
            put("challenge", toBase64Url(randomBytes(CHALLENGE_BYTES)))
            putJsonArray("pubKeyCredParams") {
                for (algorithm in listOf(ES256, RS256)) {
                    addJsonObject {
                        put("type", "public-key")
                        put("alg", algorithm)
                    }
                }
            }
            putJsonArray("excludeCredentials") {
                for (id in exclude) {
                    addJsonObject {
                        put("type", "public-key")
                        put("id", id)
                    }
                }
            }
            putJsonObject("authenticatorSelection") {
                put("residentKey", "required")
                put("userVerification", "required")
            }
            putJsonObject("extensions") {
                putJsonObject("prf") { putJsonObject("eval") { put("first", salt) } }
            }
        }
        val response = try {
            CredentialManager.create(context).createCredential(context, CreatePublicKeyCredentialRequest(request.toString()))
        } catch (error: CreateCredentialCancellationException) {
            throw PasskeyException(PasskeyProblem.CANCELED, "canceled", error)
        } catch (error: CreatePublicKeyCredentialDomException) {
            val problem = if (error.domError is InvalidStateError) PasskeyProblem.ALREADY_HERE else PasskeyProblem.FAILED
            throw PasskeyException(problem, error.message ?: "failed", error)
        } catch (error: CreateCredentialException) {
            throw PasskeyException(PasskeyProblem.FAILED, error.message ?: "failed", error)
        }
        val registration = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyException(PasskeyProblem.FAILED, "not a passkey")
        if (!prfEnabled(registration)) throw PasskeyException(PasskeyProblem.NO_PRF, "no PRF")
        val credentialId = credentialIdOf(registration)
        // Some providers turn PRF on at creation but only evaluate it on the next use.
        val secret = prfFirst(registration) ?: assert(context, mapOf(credentialId to salt)).secret
        return CreatedPasskey(credentialId, salt, secret)
    }

    /** Ask for one of the given passkeys (credential id → salt) and take its PRF output. */
    suspend fun assert(context: Context, given: Map<String, String>): AssertedPasskey {
        val salts = given.mapKeys { normalizedId(it.key) }
        if (salts.isEmpty()) throw PasskeyException(PasskeyProblem.NONE_FOUND, "no passkeys")
        val request = buildJsonObject {
            put("rpId", RP_ID)
            put("challenge", toBase64Url(randomBytes(CHALLENGE_BYTES)))
            put("userVerification", "required")
            putJsonArray("allowCredentials") {
                for (id in salts.keys) {
                    addJsonObject {
                        put("type", "public-key")
                        put("id", id)
                    }
                }
            }
            putJsonObject("extensions") {
                putJsonObject("prf") {
                    putJsonObject("evalByCredential") {
                        for ((id, salt) in salts) putJsonObject(id) { put("first", salt) }
                    }
                }
            }
        }
        val response = try {
            CredentialManager.create(context).getCredential(context, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(request.toString()))))
        } catch (error: GetCredentialCancellationException) {
            throw PasskeyException(PasskeyProblem.CANCELED, "canceled", error)
        } catch (error: NoCredentialException) {
            throw PasskeyException(PasskeyProblem.NONE_FOUND, "no passkey here", error)
        } catch (error: GetCredentialException) {
            throw PasskeyException(PasskeyProblem.FAILED, error.message ?: "failed", error)
        }
        val authentication = (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyException(PasskeyProblem.FAILED, "not a passkey")
        val credentialId = credentialIdOf(authentication)
        if (credentialId !in salts) throw PasskeyException(PasskeyProblem.NONE_FOUND, "another passkey answered")
        val secret = prfFirst(authentication) ?: throw PasskeyException(PasskeyProblem.NO_PRF, "no PRF result")
        return AssertedPasskey(credentialId, secret)
    }
}
