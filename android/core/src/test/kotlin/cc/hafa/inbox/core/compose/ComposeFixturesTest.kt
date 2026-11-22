package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

/** Runs `shared/testdata/compose-fixtures.json`, so Android builds exactly what web builds. */
class ComposeFixturesTest {
    private fun cases(section: String) = Fixtures.cases(Fixtures.compose, section)

    private fun person(raw: Any?): Person {
        val map = raw as Map<*, *>
        return Person(map["name"] as String, map["email"] as String)
    }

    private fun people(raw: Any?): List<Person> = (raw as List<*>).map(::person)

    private fun participants(raw: Any?): ReplyParticipants {
        val map = raw as Map<*, *>
        return ReplyParticipants(people(map["from"]), people(map["replyTo"]), people(map["to"]), people(map["cc"]))
    }

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun localEpoch(raw: Any?): Long = LocalDateTime.parse(raw as String).atZone(zone).toInstant().toEpochMilli()

    private fun quote(raw: Any?): QuotedMessage? {
        val map = raw as? Map<*, *> ?: return null
        return QuotedMessage(person(map["from"]), localEpoch(map["dateLocal"]), map["text"] as String, map["html"] as String?)
    }

    @Test
    fun markdown() {
        for (case in cases("markdown")) {
            val name = case["name"]
            assertEquals("$name: html", case["html"], normalizeHtml(markdownToHtml(case["markdown"] as String)))
            assertEquals("$name: text", case["text"], markdownToText(case["markdown"] as String))
        }
    }

    @Test
    fun body() {
        for (case in cases("body")) {
            val parts = bodyParts(case["body"] as String, case["signature"] as String?, quote(case["quote"]))
            assertEquals("${case["name"]}: html", case["html"], normalizeHtml(parts.html))
            assertEquals("${case["name"]}: text", case["text"], parts.text)
        }
    }

    @Test
    fun attribution() {
        for (case in cases("attribution")) {
            assertEquals(case["expect"], quoteAttribution(person(case["from"]), localEpoch(case["dateLocal"])))
        }
    }

    @Test
    fun quoteParts() {
        for (case in cases("quoteText")) assertEquals("${case["name"]}", case["expect"], quoteText(quote(case["quote"])!!))
        for (case in cases("quotableHtml")) assertEquals("${case["name"]}", case["expect"], quotableHtml(case["html"] as String))
    }

    @Test
    fun quotedImages() {
        for (case in cases("quotedContentIds")) {
            assertEquals("${case["name"]}", strings(case["expect"]), quotedContentIds(case["html"] as String?))
        }
    }

    @Test
    fun splitQuotes() {
        for (case in cases("splitText")) {
            assertEquals("${case["name"]}", QuoteSplit(case["body"] as String, case["quote"] as String?), splitQuotedText(case["text"] as String))
        }
        for (case in cases("splitHtml")) {
            assertEquals("${case["name"]}", QuoteSplit(case["body"] as String, case["quote"] as String?), splitQuotedHtml(case["html"] as String))
        }
    }

    @Test
    fun subjects() {
        for (case in cases("replySubject")) assertEquals(case["expect"], replySubject(case["subject"] as String))
        for (case in cases("forwardSubject")) assertEquals(case["expect"], forwardSubject(case["subject"] as String))
    }

    @Test
    fun replyHeaders() {
        for (case in cases("replyHeaders")) {
            val source = case["source"] as Map<*, *>
            val expect = case["expect"] as Map<*, *>?
            val result = replyHeaders(
                ReplySource(source["messageId"] as String, source["references"] as String?, source["inReplyTo"] as String?, source["subject"] as String),
            )
            assertEquals(
                "${case["name"]}",
                expect?.let { ReplyHeaders(it["subject"] as String, it["inReplyTo"] as String, strings(it["references"])) },
                result,
            )
        }
    }

    @Test
    fun answerHeaders() {
        for (case in cases("answerHeaders")) {
            val source = case["source"] as Map<*, *>
            val expect = case["expect"] as Map<*, *>
            val result = answerHeaders(
                ReplySource(source["messageId"] as String, source["references"] as String?, source["inReplyTo"] as String?, source["subject"] as String),
                case["forward"] as Boolean,
            )
            val name = "${case["name"]}"
            assertEquals(name, AnswerHeaders(expect["subject"] as String, expect["inReplyTo"] as String?, strings(expect["references"])), result)
            val built = buildMime(
                ComposeInput(
                    from = Person("", "test@hafa.cc"),
                    to = listOf(Person("", "bob@example.com")),
                    cc = emptyList(),
                    bcc = emptyList(),
                    subject = result.subject,
                    markdown = "Hi",
                    signature = null,
                    inReplyTo = result.inReplyTo,
                    references = result.references,
                    inlineImages = emptyList(),
                    attachments = emptyList(),
                    extraHeaders = emptyList(),
                    boundary = "b0",
                ),
            )
            val head = built.substringBefore("\r\n\r\n")
            assertEquals(name, case["mime"], head.substring(head.indexOf("Subject:"), head.indexOf("\r\nMIME-Version:")))
        }
    }

    @Test
    fun recipients() {
        for (case in cases("recipients")) {
            val expect = case["expect"] as Map<*, *>
            val result = replyRecipients(participants(case["original"]), strings(case["own"]), case["replyAll"] as Boolean)
            assertEquals("${case["name"]}", ReplyRecipients(people(expect["to"]), people(expect["cc"])), result)
        }
    }

    @Test
    fun offersReplyAll() {
        for (case in cases("offersReplyAll")) {
            assertEquals("${case["name"]}", case["expect"], offersReplyAll(participants(case["original"]), strings(case["own"])))
        }
    }

    @Test
    fun replyFrom() {
        for (case in cases("replyFrom")) {
            val result = replyFromAddress(participants(case["original"]), strings(case["own"]), case["fallback"] as String)
            assertEquals("${case["name"]}", case["expect"], result)
        }
    }

    @Test
    fun headers() {
        for (case in cases("mailbox")) assertEquals(case["expect"], formatMailbox(person(case["mailbox"])))
        for (case in cases("invalidMailbox")) {
            assertThrows("${case["name"]}", InvalidAddressError::class.java) { formatMailbox(person(case["mailbox"])) }
        }
        for (case in cases("headerText")) assertEquals(case["expect"], encodeHeaderText(case["text"] as String))
        for (case in cases("fold")) assertEquals(case["expect"], foldHeader(case["name"] as String, case["value"] as String))
    }

    @Test
    fun bodyEncodings() {
        for (case in cases("qp")) assertEquals(case["expect"], quotedPrintable(case["text"] as String))
        for (case in cases("base64Lines")) {
            assertEquals(case["expect"], base64Lines((case["utf8"] as String).toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun mime() {
        for (case in cases("mime")) {
            assertEquals("${case["name"]}", case["expect"], buildMime(composeInput(case["input"] as Map<*, *>)))
        }
    }

    @Test
    fun mimeRefusesControlCharactersInHeaders() {
        for (case in cases("invalidHeader")) {
            assertThrows("${case["name"]}", InvalidHeaderError::class.java) { buildMime(composeInput(case["input"] as Map<*, *>)) }
        }
    }

    /** RSVP messages: `calendar-fixtures.json` `mime`, a text/calendar part after the HTML. */
    @Test
    fun calendarMime() {
        for (case in Fixtures.cases(Fixtures.calendar, "mime")) {
            val expected = (case["expect"] as List<*>).joinToString("\r\n")
            assertEquals("${case["name"]}", expected, buildMime(composeInput(case["input"] as Map<*, *>)))
        }
    }

    private fun composeInput(input: Map<*, *>): ComposeInput {
        val decoder = Base64.getDecoder()
        return ComposeInput(
            from = person(input["from"]),
            to = people(input["to"]),
            cc = people(input["cc"]),
            bcc = people(input["bcc"]),
            subject = input["subject"] as String,
            markdown = input["markdown"] as String,
            signature = input["signature"] as String?,
            inReplyTo = input["inReplyTo"] as String?,
            references = strings(input["references"]),
            inlineImages = (input["inlineImages"] as List<*>).map {
                val image = it as Map<*, *>
                MimeInlineImage(
                    image["filename"] as String,
                    image["mimeType"] as String,
                    decoder.decode(image["data"] as String),
                    image["contentId"] as String,
                )
            },
            attachments = (input["attachments"] as List<*>).map {
                val file = it as Map<*, *>
                MimeAttachment(file["filename"] as String, file["mimeType"] as String, decoder.decode(file["data"] as String))
            },
            extraHeaders = (input["extraHeaders"] as List<*>).map {
                val pair = it as List<*>
                pair[0] as String to pair[1] as String
            },
            boundary = input["boundary"] as String,
            quote = quote(input["quote"]),
            calendar = (input["calendar"] as? Map<*, *>)?.let { CalendarPart(it["method"] as String, it["content"] as String) },
        )
    }

    @Test
    fun builtMessagesUseCrlfOnlyAndLeaveDateToGmail() {
        val message = buildMime(
            ComposeInput(
                Person("Ana", "test@hafa.cc"), listOf(Person("Bob", "bob@example.com")), emptyList(), emptyList(),
                "Hi", "Hello", null, null, emptyList(), emptyList(), emptyList(), emptyList(), "t",
            ),
        )
        assertFalse(message.replace(CRLF, "").contains('\r') || message.replace(CRLF, "").contains('\n'))
        assertTrue(message.endsWith(CRLF))
        assertFalse(message.lines().any { it.startsWith("Date:") || it.startsWith("Message-ID:", ignoreCase = true) })
    }

    @Test
    fun buildMimeRefusesAnAddressThatWouldAddAHeader() {
        assertThrows(InvalidAddressError::class.java) {
            buildMime(
                ComposeInput(
                    Person("Ana", "test@hafa.cc"), listOf(Person("Bob", "bob@example.com")),
                    listOf(Person("", "eve@example.net\r\nBcc: x@example.net")), emptyList(),
                    "Hi", "Hello", null, null, emptyList(), emptyList(), emptyList(), emptyList(), "t",
                ),
            )
        }
    }

    @Test
    fun randomBoundaryIsFresh() {
        val first = randomBoundary()
        assertTrue(Regex("^[0-9a-f]{24}$").matches(first))
        assertTrue(first != randomBoundary())
    }

    @Test
    fun signatureSplitsBack() {
        val parts = bodyParts("Thanks,\nAna", "Ana\nhafa.cc")
        assertEquals("Thanks,\nAna" to "Ana\nhafa.cc", splitSignature(parts.text))
        assertEquals("" to "Ana", splitSignature(bodyParts("", "Ana").text))
        assertEquals("Just this" to null, splitSignature("Just this"))
    }

    @Test
    fun quotedReplySplitsBackToBodySignatureAndQuote() {
        val original = QuotedMessage(Person("Maya Chen", "maya.chen@gmail.example.com"), localEpoch("2026-11-02T11:50:00"), "Lunch on Friday?\nMaya", null)
        val parts = bodyParts("Friday works.", "Ana", original)
        val split = splitQuotedText(parts.text)
        assertEquals("Friday works." to "Ana", splitSignature(split.body))
        assertEquals(original, quoteFromText(split.quote!!, zone))
    }
}
