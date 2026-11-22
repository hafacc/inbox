package cc.hafa.inbox.core.address

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The name rules and refusal wording of shared/src/address-name.ts, which the server runs too.
// Fixtures: shared/testdata/address-name-fixtures.json.

/** Domain users claim addresses on. */
const val ADDRESS_DOMAIN = "hafa.cc"

private const val NAME_MIN_LENGTH = 2
private const val NAME_MAX_LENGTH = 32
private val NAME_CHARACTERS = Regex("^[a-z0-9.-]+$")
private val DOUBLED_PUNCTUATION = Regex("[.-]{2}")
private val PUNCTUATION = Regex("[.-]")

/** Names nobody can claim: every group in `shared/address/reserved-names.json`, the server's list. */
object ReservedAddressNames {
    /** The names, without dots or hyphens. */
    val names: Set<String> by lazy {
        val text = requireNotNull(ReservedAddressNames::class.java.classLoader?.getResourceAsStream("reserved-names.json")) {
            "reserved-names.json missing from resources"
        }.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.values
            .flatMap { group -> group.jsonArray.map { it.jsonPrimitive.content } }
            .toSet()
    }
}

private fun isEdgeCharacter(character: Char): Boolean = character in 'a'..'z' || character in '0'..'9'

/** A name as the server checks it: trimmed, lower-cased, without a trailing `@hafa.cc`. */
fun normalizeAddressName(input: String): String {
    val name = input.trim().lowercase()
    return name.removeSuffix("@$ADDRESS_DOMAIN")
}

/**
 * Why a name can't be claimed, as the server's `details.reason` (`length`, `characters`, `edges`,
 * `punctuation`, `reserved`), or null when it passes.
 */
fun addressNameProblem(input: String): String? {
    val name = normalizeAddressName(input)
    return when {
        name.length < NAME_MIN_LENGTH || name.length > NAME_MAX_LENGTH -> "length"
        !NAME_CHARACTERS.matches(name) -> "characters"
        !isEdgeCharacter(name.first()) || !isEdgeCharacter(name.last()) -> "edges"
        DOUBLED_PUNCTUATION.containsMatchIn(name) -> "punctuation"
        name in ReservedAddressNames.names || name.replace(PUNCTUATION, "") in ReservedAddressNames.names -> "reserved"
        else -> null
    }
}

/**
 * What to tell the user for a claim error's `details.reason`, naming [address] (the error's
 * `details.address`) for `had-address`; null when the reason isn't one the server sends.
 */
fun addressRefusalText(reason: String?, address: String?): String? =
    if (reason == "had-address" && address != null) {
        "Your account already had $address — you can only take that one back."
    } else {
        addressReasonText(reason)
    }

/** What to tell the user for a claim error's `details.reason`; null when it isn't one the server sends. */
fun addressReasonText(reason: String?): String? = when (reason) {
    "length" -> "Use 2 to 32 characters."
    "characters" -> "Use letters, digits, dots and hyphens."
    "edges" -> "Start and end with a letter or digit."
    "punctuation" -> "Don't put dots or hyphens next to each other."
    "reserved" -> "That name is reserved."
    "taken" -> "That name is taken."
    "has-address" -> "You already have an address."
    "had-address" -> "Your account already had an address — you can only take that one back."
    "no-gmail" -> "Sign in again to set up an address."
    "releasing" -> "Try again in a moment."
    "not-configured" -> "@hafa.cc addresses aren't available yet."
    "no-address" -> "You don't have an address anymore."
    else -> null
}

/** Setup progress as the user reads it, for `addressSetup.status`. */
fun addressStatusText(status: String?, address: String): String = when (status) {
    "pending" -> "Setting up…"
    "verifying" -> "Confirming your Gmail address…"
    "active" -> "Mail to $address arrives here."
    "releasing" -> "Releasing…"
    else -> ""
}

/** What setup is waiting out, for `addressSetup.error.code`; null when nothing to say. */
fun addressErrorText(code: String?): String? = when (code) {
    "confirm-by-hand" -> "Open the link in the email from Cloudflare to finish. Check Spam too."
    "cloudflare" -> "Still setting up; this can take a few minutes."
    "not-configured" -> "@hafa.cc addresses aren't available yet."
    null -> null
    else -> "Still setting up; this can take a few minutes."
}
