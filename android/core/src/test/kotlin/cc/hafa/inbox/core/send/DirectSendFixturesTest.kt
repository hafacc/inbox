package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.gmail.GmailHeader
import cc.hafa.inbox.core.gmail.GmailSendAs
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/direct-send-fixtures.json`, so a send now goes from the device exactly as on web. */
class DirectSendFixturesTest {
    private val fixtures = Fixtures.directSend

    private fun headersOf(value: Any?): List<GmailHeader> = (value as List<*>).map {
        val header = it as Map<*, *>
        GmailHeader(header["name"] as String, header["value"] as String)
    }

    @Test
    fun limits() {
        assertEquals((fixtures["lateMs"] as Number).toLong(), DIRECT_SEND_LATE_MS)
        assertEquals((fixtures["handoverMaxMs"] as Number).toLong(), DIRECT_HANDOVER_MAX_MS)
        assertEquals((fixtures["sendTimeoutMs"] as Number).toLong(), DIRECT_SEND_TIMEOUT_MS)
        assertEquals(fixtures["clientFields"], CLIENT_OUTBOX_FIELDS)
    }

    @Test
    fun decision() {
        for (case in Fixtures.cases(fixtures, "decision")) {
            val input = case["input"] as Map<*, *>
            val doc = (input["doc"] as Map<*, *>?)?.let {
                DirectSendDoc(it["status"] as String, (it["sendAt"] as Number).toLong(), (it["fields"] as List<*>).map { field -> field as String })
            }
            val decided = directSendDecision(
                DirectSendInput(
                    sendNow = input["sendNow"] as Boolean,
                    docs = (input["docs"] as Number).toInt(),
                    online = input["online"] as Boolean,
                    gmailRoute = input["gmailRoute"] as Boolean,
                    wroteSendAt = (input["wroteSendAt"] as Number).toLong(),
                    doc = doc,
                    now = (input["now"] as Number).toLong(),
                ),
            )
            assertEquals(case["name"] as String, case["expect"], decided.wire)
        }
    }

    @Test
    fun route() {
        val sendAs = (fixtures["sendAs"] as List<*>).map {
            val entry = it as Map<*, *>
            GmailSendAs(
                sendAsEmail = entry["sendAsEmail"] as String,
                isPrimary = entry["isPrimary"] as Boolean? ?: false,
                verificationStatus = entry["verificationStatus"] as String?,
            )
        }
        val domain = fixtures["resendDomain"] as String
        for (case in Fixtures.cases(fixtures, "route")) {
            assertEquals(case["name"] as String, case["expect"] == "send", isGmailRoute(case["from"] as String, sendAs, domain))
        }
    }

    @Test
    fun sender() {
        for (case in Fixtures.cases(fixtures, "sender")) {
            assertEquals(case["name"] as String, case["expect"], draftSender(headersOf(case["headers"])))
        }
    }

    @Test
    fun failure() {
        for (case in Fixtures.cases(fixtures, "failure")) {
            val error = case["error"] as Map<*, *>?
            val outcome = directSendFailure((error?.get("status") as Number?)?.toInt(), error?.get("reason") as String?)
            assertEquals(case["name"] as String, case["expect"], outcome.name.lowercase())
        }
    }
}
