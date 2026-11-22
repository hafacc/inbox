package cc.hafa.inbox.core.gmail

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [inner], telling [report] how each call went: [ConnectionEvent.Answered] for any status,
 * [ConnectionEvent.TimedOut] when its socket ran out of time, [ConnectionEvent.Unanswered] for
 * any other network failure ([isNetworkError]). A call cut off by its caller reports nothing.
 */
class ReportingTransport(
    private val inner: HttpTransport,
    private val report: (ConnectionEvent) -> Unit,
) : HttpTransport {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        reported { inner.send(method, url, headers, body) }

    override suspend fun sendUntimed(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
        reported { inner.sendUntimed(method, url, headers, body) }

    private suspend fun reported(call: suspend () -> HttpResponse): HttpResponse {
        val response = try {
            call()
        } catch (error: SocketTimeoutException) {
            report(ConnectionEvent.TimedOut)
            throw error
        } catch (error: IOException) {
            report(ConnectionEvent.Unanswered)
            throw error
        }
        report(ConnectionEvent.Answered(response.status))
        return response
    }
}
