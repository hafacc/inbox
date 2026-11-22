package cc.hafa.inbox.core.address

/** Why the address needs the user, worded for the notice in the inbox; null when nothing is wrong. Matches web's `addressNoticeText`. */
fun addressNoticeText(status: String?, errorCode: String?, updatedAt: Long?, now: Long): String? =
    when (addressTrouble(status, errorCode, updatedAt, now)) {
        AddressTrouble.ERROR -> addressErrorText(errorCode) ?: "Setup stopped."
        AddressTrouble.STALLED -> "Setup has stalled."
        null -> null
    }

/** How long setup may sit unfinished before the app points it out. */
const val SETUP_STALL_MS: Long = 30 * 60 * 1000L

/** Why the @hafa.cc address needs the user. */
enum class AddressTrouble {
    /** setup reported a problem */
    ERROR,

    /** setup has sat pending or verifying for over half an hour */
    STALLED,
}

/**
 * Why the @hafa.cc address needs the user, or null when nothing is wrong; matches web's
 * `addressTrouble` (`shared/src/address-notice.ts`). The server retries Cloudflare by itself, so
 * its `cloudflare` code counts only once setup has stalled.
 */
fun addressTrouble(status: String?, errorCode: String?, updatedAt: Long?, now: Long): AddressTrouble? {
    if (status == null) return null
    val unfinished = status == "pending" || status == "verifying"
    return when {
        errorCode != null && errorCode != "cloudflare" -> AddressTrouble.ERROR
        unfinished && updatedAt != null && now - updatedAt > SETUP_STALL_MS -> AddressTrouble.STALLED
        else -> null
    }
}

/** What the inbox's address notice shows. */
enum class AddressNoticeState {
    /** setup is under way and fine: a calm line, no dots */
    WORKING,

    /** setup needs the user: the reason, "Fix it" and the dots */
    ERROR,
}

/** What the address notice shows, or null for none; matches web's `addressNoticeState`. */
fun addressNoticeState(status: String?, errorCode: String?, updatedAt: Long?, now: Long): AddressNoticeState? = when {
    addressTrouble(status, errorCode, updatedAt, now) != null -> AddressNoticeState.ERROR
    status == "pending" || status == "verifying" -> AddressNoticeState.WORKING
    else -> null
}

