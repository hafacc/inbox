package cc.hafa.inbox.core.mail

/** A message as a thread's dates read it. */
data class DatedMessage(
    /** Gmail's `internalDate`, epoch milliseconds */
    val date: Long,
    /** its Gmail labels */
    val labelIds: List<String>,
)

/** The dates a list can place a thread by, epoch milliseconds. */
data class ThreadDates(
    /** its latest received message (not sent by the user), else its latest message */
    val received: Long,
    /** its latest message sent by the user, else its latest message */
    val sent: Long,
)

/**
 * The dates a thread is placed, sorted and dated by in lists.
 *
 * Every list but Sent uses [ThreadDates.received]: the newest message the user didn't send (Gmail's
 * SENT label marks the ones they did), so a reply of theirs doesn't move the thread. A thread of only
 * their own messages uses its newest message. Sent uses [ThreadDates.sent]: the newest message the
 * user sent. Drafts count only when the thread has nothing else. Gmail's message order isn't trusted:
 * the newest date wins wherever it is.
 */
fun threadDates(messages: List<DatedMessage>): ThreadDates {
    val counted = messages.filterNot { Labels.DRAFT in it.labelIds }.ifEmpty { messages }
    val (own, received) = counted.partition { Labels.SENT in it.labelIds }
    val newest = counted.maxOfOrNull { it.date } ?: 0L
    return ThreadDates(
        received = received.maxOfOrNull { it.date } ?: newest,
        sent = own.maxOfOrNull { it.date } ?: newest,
    )
}

/**
 * Threads as a list orders them: each thread once, newest first. Of a thread listed more than once,
 * the entry with the newest date stays (the first of equals); equal dates keep list order.
 */
fun <Thread> newestThreadsFirst(threads: List<Thread>, idOf: (Thread) -> String, dateOf: (Thread) -> Long): List<Thread> {
    val kept = linkedMapOf<String, Pair<Thread, Long>>()
    for (thread in threads) {
        val date = dateOf(thread)
        val id = idOf(thread)
        val known = kept[id]
        if (known == null || date > known.second) kept[id] = thread to date
    }
    return kept.values.sortedByDescending { (_, date) -> date }.map { (thread, _) -> thread }
}
