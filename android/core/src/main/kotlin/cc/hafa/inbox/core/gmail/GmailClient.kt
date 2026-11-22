package cc.hafa.inbox.core.gmail

import cc.hafa.inbox.core.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.hafa.inbox.core.filters.GmailFilter
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** A Gmail API call that came back with an error status, with Google's reason (`userRateLimitExceeded`, `notFound`…) when it gave one. */
class GmailException(
    /** HTTP status */
    val status: Int,
    message: String,
    /** Google's reason, when it gave one */
    val reason: String? = null,
    /** seconds Google asked to wait (`Retry-After`), when it gave them */
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/** A Gmail call that got no answer within its time ([READ_TIMEOUT_MS] for a read, [WRITE_TIMEOUT_MS] for a write). */
class GmailTimeoutException : Exception("Gmail didn't answer in time.")

/** How long a read may take before it fails as a [GmailTimeoutException]. */
const val READ_TIMEOUT_MS = 30_000L

/** How long a write other than a create may take before it fails as a [GmailTimeoutException]. */
const val WRITE_TIMEOUT_MS = 120_000L

/** Whether a Google API error is a usage limit, which passes if the call is tried again later. */
fun isRateLimited(error: Throwable): Boolean = error is GmailException && isUsageLimit(error.status, error.reason)

/** Whether a failed call may go through later: offline, a usage limit, no answer in time, or a Google server error. */
fun isRetryable(error: Throwable): Boolean =
    isNetworkError(error) ||
        isRateLimited(error) ||
        error is GmailTimeoutException ||
        error is SocketTimeoutException ||
        (error is GmailException && error.status >= HttpURLConnection.HTTP_INTERNAL_ERROR)

/** Whether a Google API call failed because what it named doesn't exist (HTTP 404). */
fun isNotFound(error: Throwable): Boolean = error is GmailException && error.status == HttpURLConnection.HTTP_NOT_FOUND

/** Whether an error means the network is down rather than that Gmail refused; a socket timeout is a slow link, not offline. */
fun isNetworkError(error: Throwable): Boolean = error is IOException && error !is SocketTimeoutException

/** Where an access token comes from, and how to drop one Gmail rejected. */
interface TokenSource {
    /** a token good for the next call */
    suspend fun get(): String

    /** forget the current token */
    fun invalidate()
}

/** An HTTP response: status and body text. */
data class HttpResponse(
    /** HTTP status */
    val status: Int,
    /** response body text */
    val body: String,
    /** the `Retry-After` header, when sent */
    val retryAfter: String? = null,
)

/** The HTTP the client needs; throws [IOException] when the network is unreachable. */
fun interface HttpTransport {
    /** send one request */
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse

    /** send one request that waits as long as it takes for the answer, once connected */
    suspend fun sendUntimed(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        send(method, url, headers, body)
}

/** [HttpTransport] over the JDK's `HttpURLConnection`, which Android also has. */
object UrlConnectionTransport : HttpTransport {
    private const val TIMEOUT_MS = 30_000

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        exchange(method, url, headers, body, TIMEOUT_MS)

    // A read timeout of 0 waits for good; connecting is still limited, since nothing was sent before.
    override suspend fun sendUntimed(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        exchange(method, url, headers, body, 0)

    private suspend fun exchange(method: String, url: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): HttpResponse =
        withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                // HttpURLConnection has no PATCH; Google's APIs take the override header instead.
                if (method == "PATCH") {
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    connection.requestMethod = method
                }
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = readTimeoutMs
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                HttpResponse(status, text, connection.getHeaderField("Retry-After"))
            } finally {
                connection.disconnect()
            }
        }
}

/** Options for `threads.list`. */
data class ListOptions(
    /** labels every thread must have */
    val labelIds: List<String> = emptyList(),
    /** Gmail search query */
    val query: String? = null,
    /** token of the page to fetch; null for the first */
    val pageToken: String? = null,
    /** threads per page */
    val maxResults: Int = 50,
    /** whether spam and trash are searched too */
    val includeSpamTrash: Boolean = false,
)

/** Body of `threads.modify`. */
@Serializable
private data class ModifyRequest(val addLabelIds: List<String>, val removeLabelIds: List<String>)

/** Body of `labels.create` and `labels.patch`. */
@Serializable
private data class LabelWrite(
    val name: String?,
    val labelListVisibility: String?,
    val messageListVisibility: String?,
    val color: GmailLabelColor?,
)

/** The `settings.filters.list` response. */
@Serializable
private data class GmailFilterList(val filter: List<GmailFilter>? = null)

/** One `settings.forwardingAddresses` entry. */
@Serializable
private data class GmailForwardingAddress(val forwardingEmail: String, val verificationStatus: String? = null)

/** The `settings.forwardingAddresses.list` response. */
@Serializable
private data class GmailForwardingList(val forwardingAddresses: List<GmailForwardingAddress>? = null)

/** Headers fetched for list rows. */
private val METADATA_HEADERS = listOf("From", "Subject", "To", "Cc", "Content-Type", "Message-ID", "In-Reply-To", "References")

/** The parts of a `metadata` thread read that list rows use. */
private const val METADATA_FIELDS = "id,historyId,snippet,messages(id,threadId,labelIds,snippet,historyId,internalDate,payload(mimeType,headers))"

private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

/** The body of `drafts.send`: the draft to send. */
@Serializable
private data class DraftIdBody(
    /** draft id */
    val id: String,
)
private const val UPLOAD_BASE = "https://gmail.googleapis.com/upload/gmail/v1/users/me"

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun queryString(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }

/**
 * One authorized call to a Google REST API with the user's token: retries once with a fresh token
 * on 401 and turns an error status into [GmailException]. An [untimed] call waits for its answer
 * as long as it takes ([HttpTransport.sendUntimed]).
 */
internal suspend fun googleRequest(
    tokens: TokenSource,
    transport: HttpTransport,
    method: String,
    url: String,
    body: String? = null,
    contentType: String = "application/json",
    retried: Boolean = false,
    untimed: Boolean = false,
): String {
    val token = tokens.get()
    val headers = buildMap {
        put("Authorization", "Bearer $token")
        if (body != null) put("Content-Type", contentType)
    }
    val response = if (untimed) transport.sendUntimed(method, url, headers, body) else transport.send(method, url, headers, body)
    if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED && !retried) {
        tokens.invalidate()
        return googleRequest(tokens, transport, method, url, body, contentType, true, untimed)
    }
    if (response.status !in 200..299) {
        val detail = runCatching { GmailJson.decodeFromString<GmailErrorBody>(response.body).error }.getOrNull()
        val reason = detail?.errors?.firstNotNullOfOrNull { it.reason } ?: detail?.details?.firstNotNullOfOrNull { it.reason }
        throw GmailException(
            response.status,
            detail?.message ?: "HTTP ${response.status}",
            reason,
            response.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
        )
    }
    return response.body
}

/**
 * The Gmail REST calls this app makes, straight from the device with the user's token. Every call
 * waits for a slot from [gate]: a write as [CallPriority.ACTION], a read at the [GmailPriority] of
 * its coroutine ([CallPriority.VISIBLE] when none is given).
 */
class GmailClient(
    private val tokens: TokenSource,
    /** the pacing every call waits for */
    val gate: RateGate = RateGate(),
    /** where requests and decoding run, off the caller's thread */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    // A read in flight, shared by everyone asking for it; it waits at the most urgent asker's priority.
    private class SharedRead(var priority: CallPriority) {
        val result = CompletableDeferred<String>()

        @Synchronized
        fun raise(to: CallPriority): Boolean = (to < priority).also { if (it) priority = to }

        @Synchronized
        fun current(): CallPriority = priority
    }

    // Reads in flight by URL, so the same read asked for twice at once goes out once.
    private val reading = ConcurrentHashMap<String, SharedRead>()

    // A create isn't timed out: one cut off may still have been made, and its retry would make a second.
    private suspend fun request(method: String, path: String, body: String? = null, create: Boolean = false): String {
        val url = "$BASE$path"
        if (method != "GET") {
            return paced(isRead = false, { CallPriority.ACTION }, timed = !create) {
                googleRequest(tokens, transport, method, url, body, untimed = create)
            }
        }
        val asker = currentCoroutineContext()[GmailPriority]
        val priority = asker?.priority ?: CallPriority.VISIBLE
        val mine = SharedRead(priority)
        while (true) {
            val existing = reading.putIfAbsent(url, mine) ?: break
            if (existing.raise(priority)) gate.poke()
            try {
                return existing.result.await()
            } catch (error: CancellationException) {
                // The caller making the read stopped waiting for it; unless this one did too, make it again.
                currentCoroutineContext().ensureActive()
            }
        }
        // Leave the map before completing, so a waiter that retries finds the slot free.
        // The asker's priority may rise while the read waits ([GmailPriority.raise]).
        val current = {
            asker?.let { mine.raise(it.priority) }
            mine.current()
        }
        // Cancellation too is caught here, so the waiters hear of it and the slot is freed; it is thrown on below.
        val result = runCatching { paced(isRead = true, current) { googleRequest(tokens, transport, method, url, body) } }
        reading.remove(url, mine)
        mine.result.completeWith(result)
        return result.getOrThrow()
    }

    // A call in its turn from the gate, given up after its timeout when [timed]; a usage limit starts a pause, and a read is tried again.
    private suspend fun paced(isRead: Boolean, priority: () -> CallPriority, timed: Boolean = true, call: suspend () -> String): String {
        val timeout = if (isRead) READ_TIMEOUT_MS else WRITE_TIMEOUT_MS
        var attempt = 0
        while (true) {
            try {
                val answer = gate.withSlot(priority) { if (timed) withTimeoutOrNull(timeout) { call() } else call() }
                    ?: throw GmailTimeoutException()
                return answer.also { gate.passed() }
            } catch (error: GmailException) {
                if (!isRateLimited(error)) throw error
                gate.slowDown(error.status, error.reason, error.retryAfterSeconds, priority())
                if (!retriesAfterLimit(isRead, attempt)) throw error
                attempt++
            }
        }
    }

    // Decoding a full thread is heavy enough to keep off the caller's (often main) thread.
    private suspend inline fun <reified Result> decoded(method: String, path: String, body: String? = null, create: Boolean = false): Result =
        withContext(io) { GmailJson.decodeFromString(request(method, path, body, create)) }

    private suspend inline fun <reified Result> get(path: String): Result = decoded("GET", path)

    /** One page of threads matching labels and/or a search. */
    suspend fun listThreads(options: ListOptions): GmailThreadList {
        val params = mutableListOf<Pair<String, String>>()
        for (label in options.labelIds) params += "labelIds" to label
        options.query?.takeIf { it.isNotEmpty() }?.let { params += "q" to it }
        options.pageToken?.let { params += "pageToken" to it }
        params += "maxResults" to options.maxResults.toString()
        if (options.includeSpamTrash) params += "includeSpamTrash" to "true"
        return get("/threads?${queryString(params)}")
    }

    /** A thread with headers only ([full] false) or with bodies. */
    suspend fun getThread(id: String, full: Boolean): GmailThread {
        val params = mutableListOf("format" to if (full) "full" else "metadata")
        if (!full) {
            for (header in METADATA_HEADERS) params += "metadataHeaders" to header
            params += "fields" to METADATA_FIELDS
        }
        return get("/threads/${encode(id)}?${queryString(params)}")
    }

    /** One page of messages matching a search, newest first. */
    suspend fun listMessages(query: String, pageToken: String? = null, maxResults: Int = 50): GmailMessageList {
        val params = mutableListOf("q" to query, "maxResults" to maxResults.toString())
        pageToken?.let { params += "pageToken" to it }
        return get("/messages?${queryString(params)}")
    }

    /** One message with its whole MIME tree. */
    suspend fun getMessage(id: String): GmailMessage = get("/messages/${encode(id)}?format=full")

    /** One message as sent, byte for byte (`format=raw`), for decrypting. */
    suspend fun getRawMessage(id: String): GmailRawMessage = get("/messages/${encode(id)}?format=raw")

    /** Add and remove labels on every message of a thread. */
    suspend fun modifyThread(id: String, addLabelIds: List<String>, removeLabelIds: List<String>) {
        val body = GmailJson.encodeToString(ModifyRequest.serializer(), ModifyRequest(addLabelIds, removeLabelIds))
        request("POST", "/threads/${encode(id)}/modify", body)
    }

    /** Every label of the account, without counts. */
    suspend fun listLabels(): List<GmailLabel> = get<GmailLabelList>("/labels").labels ?: emptyList()

    /** Create a user label, shown in label lists. */
    suspend fun createLabel(name: String, color: GmailLabelColor? = null): GmailLabel {
        val body = GmailJson.encodeToString(LabelWrite.serializer(), LabelWrite(name, "labelShow", "show", color))
        return decoded("POST", "/labels", body, create = true)
    }

    /** Rename a label or change its color; null leaves that part as it is. */
    suspend fun updateLabel(id: String, name: String? = null, color: GmailLabelColor? = null): GmailLabel {
        val body = GmailJson.encodeToString(LabelWrite.serializer(), LabelWrite(name, null, null, color))
        return decoded("PATCH", "/labels/${encode(id)}", body)
    }

    /** Every Gmail filter of the account. */
    suspend fun listFilters(): List<GmailFilter> = get<GmailFilterList>("/settings/filters").filter.orEmpty()

    /** Create a filter; Gmail has no update, so an edit is a delete and a create. */
    suspend fun createFilter(filter: GmailFilter): GmailFilter =
        decoded("POST", "/settings/filters", GmailJson.encodeToString(GmailFilter.serializer(), filter.copy(id = null)), create = true)

    /** Delete a filter. */
    suspend fun deleteFilter(id: String) {
        request("DELETE", "/settings/filters/${encode(id)}")
    }

    /** Addresses Gmail has verified for forwarding, the only ones a filter may forward to. */
    suspend fun listForwardingAddresses(): List<String> =
        get<GmailForwardingList>("/settings/forwardingAddresses").forwardingAddresses.orEmpty()
            .filter { it.verificationStatus == "accepted" }
            .map { it.forwardingEmail }

    /** One label with its thread counts. */
    suspend fun getLabel(id: String): GmailLabel = get("/labels/${encode(id)}")

    /** Message and label additions since a history id, one page. */
    suspend fun listHistory(
        startHistoryId: String,
        pageToken: String? = null,
        maxResults: Int = 500,
        types: List<String>? = listOf("messageAdded", "labelAdded"),
    ): GmailHistoryList {
        val params = mutableListOf("startHistoryId" to startHistoryId)
        types?.forEach { params += "historyTypes" to it }
        params += "maxResults" to maxResults.toString()
        pageToken?.let { params += "pageToken" to it }
        return get("/history?${queryString(params)}")
    }

    /** An attachment's base64url data. */
    suspend fun getAttachment(messageId: String, attachmentId: String): String =
        get<GmailAttachment>("/messages/${encode(messageId)}/attachments/${encode(attachmentId)}").data

    /** The account's address and current history id. */
    suspend fun getProfile(): GmailProfile = get("/profile")

    /** The account's send-as addresses, verified or not. */
    suspend fun listSendAs(): List<GmailSendAs> = get<GmailSendAsList>("/settings/sendAs").sendAs.orEmpty()

    // Media upload takes the whole message (up to 35 MB) where a JSON `raw` body is capped far lower.
    private suspend fun uploadDraft(method: String, path: String, metadata: DraftMetadata, mime: String): GmailDraft {
        val boundary = "draft_${System.nanoTime()}"
        val body = listOf(
            "--$boundary",
            "Content-Type: application/json; charset=UTF-8",
            "",
            GmailJson.encodeToString(DraftMetadata.serializer(), metadata),
            "--$boundary",
            "Content-Type: message/rfc822",
            "",
            mime,
            "--$boundary--",
        ).joinToString("\r\n")
        return withContext(io) {
            val create = method == "POST"
            val text = paced(isRead = false, { CallPriority.ACTION }, timed = !create) {
                googleRequest(tokens, transport, method, "$UPLOAD_BASE$path?uploadType=multipart", body, "multipart/related; boundary=$boundary", untimed = create)
            }
            GmailJson.decodeFromString(GmailDraft.serializer(), text)
        }
    }

    /** Save a built message (CRLF text) as a new draft, in [threadId] for a reply. */
    suspend fun createDraft(mime: String, threadId: String?): GmailDraft =
        uploadDraft("POST", "/drafts", DraftMetadata(message = DraftMessageRef(threadId)), mime)

    /** Replace a draft's message, keeping its id. */
    suspend fun updateDraft(draftId: String, mime: String, threadId: String?): GmailDraft =
        uploadDraft("PUT", "/drafts/${encode(draftId)}", DraftMetadata(draftId, DraftMessageRef(threadId)), mime)

    /** A draft with its whole message ([full]) or just its headers. */
    suspend fun getDraft(draftId: String, full: Boolean = true): GmailDraft =
        get("/drafts/${encode(draftId)}?format=${if (full) "full" else "metadata"}")

    /**
     * Send a draft as it stands (`drafts.send`), tried once: past the gate, which could hold it back
     * after its claim, and never again after a usage limit, which could send it twice.
     */
    suspend fun sendDraft(draftId: String): GmailMessage = withContext(io) {
        val body = GmailJson.encodeToString(DraftIdBody.serializer(), DraftIdBody(draftId))
        val text = withTimeoutOrNull(WRITE_TIMEOUT_MS) { googleRequest(tokens, transport, "POST", "$BASE/drafts/send", body) }
            ?: throw GmailTimeoutException()
        GmailJson.decodeFromString(GmailMessage.serializer(), text)
    }

    /** Delete a draft for good. */
    suspend fun deleteDraft(draftId: String) {
        request("DELETE", "/drafts/${encode(draftId)}")
    }

    /** One page of drafts (ids with their message and thread ids). */
    suspend fun listDrafts(pageToken: String? = null): GmailDraftList {
        val params = mutableListOf("maxResults" to "500")
        pageToken?.let { params += "pageToken" to it }
        return get("/drafts?${queryString(params)}")
    }
}

/** Run [task] over [items] with at most [limit] running at once, keeping the input order. */
suspend fun <Item, Output> mapLimited(
    items: List<Item>,
    limit: Int,
    task: suspend (Item) -> Output,
): List<Result<Output>> = coroutineScope {
    val semaphore = Semaphore(limit.coerceAtLeast(1))
    items.map { item ->
        async { semaphore.withPermit { runSuspendCatching { task(item) } } }
    }.awaitAll()
}
