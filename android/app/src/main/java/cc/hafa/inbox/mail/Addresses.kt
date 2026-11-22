package cc.hafa.inbox.mail

import android.content.Context
import cc.hafa.inbox.core.address.addressRefusalText
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.io.File

private const val PREFS = "addresses"

/**
 * Claiming and checking the @hafa.cc address (docs/address-contract.md).
 */
class Addresses(
    private val functions: FirebaseFunctions,
) {

    /** Why a call failed, as the user reads it. */
    fun errorText(error: Throwable): String {
        val details = (error as? FirebaseFunctionsException)?.details as? Map<*, *>
        return when {
            isOffline(error) -> "You're offline."
            else -> addressRefusalText(details?.get("reason") as? String, details?.get("address") as? String)
                ?: "Something went wrong. Try again."
        }
    }

    /** Claim [name]. */
    suspend fun claim(name: String) {
        call("claimAddress", mapOf("name" to name))
    }

    /** The address this account held before it was deleted, the only one it can take back; or null. */
    suspend fun former(): String? {
        val result = functions.getHttpsCallable("formerAddress").call().await()
        return (result.getData() as? Map<*, *>)?.get("address") as? String
    }

    /** Ask the server to look for the confirmation again. */
    suspend fun check() {
        call("checkAddress", null)
    }

    private suspend fun call(name: String, data: Any?) {
        val callable = functions.getHttpsCallable(name)
        if (data == null) callable.call().await() else callable.call(data).await()
    }

    companion object {
        /** Delete an account's saved state left by older versions, as on sign-out. */
        fun clear(context: Context, uid: String) {
            context.deleteSharedPreferences("$PREFS-$uid")
        }

        /** Delete every account's saved state, as when a sign-out was cut short. */
        fun clearAll(context: Context) {
            File(context.dataDir, "shared_prefs").listFiles()
                ?.map { it.name.removeSuffix(".xml") }
                ?.filter { it.startsWith("$PREFS-") }
                ?.forEach(context::deleteSharedPreferences)
        }
    }
}
