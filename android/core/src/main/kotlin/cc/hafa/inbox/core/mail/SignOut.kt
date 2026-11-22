package cc.hafa.inbox.core.mail

private fun counted(count: Int, word: String): String = "$count $word${if (count == 1) "" else "s"}"

/**
 * What signing out would lose, for the question asked first: changes (to threads' labels, and to
 * labels and filters) and drafts (saves, sends and deletes) on this device that haven't reached
 * Gmail. Null when nothing is waiting, so sign-out goes ahead at once.
 * As `shared/src/sign-out.ts`.
 */
fun signOutWarning(changes: Int, drafts: Int): String? {
    val parts = buildList {
        if (changes > 0) add(counted(changes, "change"))
        if (drafts > 0) add(counted(drafts, "draft"))
    }
    return if (parts.isEmpty()) {
        null
    } else {
        val single = parts.size == 1 && changes + drafts == 1
        "${parts.joinToString(" and ")} ${if (single) "hasn't" else "haven't"} synced yet. " +
            "Signing out deletes ${if (single) "it" else "them"} from this device."
    }
}
