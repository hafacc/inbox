package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.Person
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** `mailto:` links and shares from other apps as a new message's start. */
class PrefillTest {
    @Test
    fun aPlainMailtoFillsTo() {
        assertEquals(ComposePrefill(to = listOf(Person("", "sam@example.com"))), parseMailtoPrefill("mailto:Sam@example.com"))
    }

    @Test
    fun everyFieldOfAMailtoIsRead() {
        val prefill = parseMailtoPrefill(
            "MAILTO:a@example.com,b@example.com?to=c@example.com&cc=d@example.com&bcc=e@example.com" +
                "&subject=Lunch%20on%20Friday%3F&body=See%20you%0D%0Athere+soon",
        )
        assertEquals(
            ComposePrefill(
                to = listOf(Person("", "a@example.com"), Person("", "b@example.com"), Person("", "c@example.com")),
                cc = listOf(Person("", "d@example.com")),
                bcc = listOf(Person("", "e@example.com")),
                subject = "Lunch on Friday?",
                body = "See you\r\nthere+soon",
            ),
            prefill,
        )
    }

    @Test
    fun aNamedAddressKeepsItsName() {
        val prefill = parseMailtoPrefill("mailto:%22Sam%20Lee%22%20%3Csam@example.com%3E")
        assertEquals(listOf(Person("Sam Lee", "sam@example.com")), prefill?.to)
    }

    @Test
    fun aMailtoWithNoAddressStillStartsAMessage() {
        assertEquals(ComposePrefill(subject = "Hi"), parseMailtoPrefill("mailto:?subject=Hi"))
    }

    @Test
    fun undeliverableAndRepeatedAddressesAreDropped() {
        val prefill = parseMailtoPrefill("mailto:not-an-address,sam@example.com?to=SAM@example.com&cc=a@b%0Abcc:x@y.z")
        assertEquals(listOf(Person("", "sam@example.com")), prefill?.to)
        assertEquals(emptyList<Person>(), prefill?.cc)
    }

    @Test
    fun aSubjectStaysOnOneLine() {
        assertEquals("Hi there", parseMailtoPrefill("mailto:a@example.com?subject=Hi%0D%0Athere")?.subject)
    }

    @Test
    fun anythingButMailtoIsNotALink() {
        assertNull(parseMailtoPrefill("https://example.com"))
        assertNull(parseMailtoPrefill("sam@example.com"))
    }

    @Test
    fun aShareAddsToItsLink() {
        val link = parseMailtoPrefill("mailto:a@example.com?subject=From%20link&body=Link%20body")
        val share = ComposePrefill(
            to = prefillAddresses(listOf("a@example.com", "Bo <b@example.com>")),
            subject = "From share",
            body = "Shared text",
        )
        assertEquals(
            ComposePrefill(
                to = listOf(Person("", "a@example.com"), Person("Bo", "b@example.com")),
                subject = "From share",
                body = "Link body\n\nShared text",
            ),
            mergePrefill(link, share),
        )
        assertEquals("From link", mergePrefill(link, ComposePrefill()).subject)
        assertEquals(share, mergePrefill(null, share))
    }

    @Test
    fun aSharedFileIsReadWholeWhenAMessageCanCarryIt() {
        val bytes = ByteArray(20_000) { it.toByte() }
        assertArrayEquals(bytes, readAttachment(ByteArrayInputStream(bytes)))
        assertArrayEquals(ByteArray(0), readAttachment(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun aSharedFileTooBigForAMessageStopsBeingRead() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int {
                read++
                return 0
            }
        }
        assertNull(readAttachment(endless))
        assertTrue(encodedSize(read) > MAX_MESSAGE_BYTES)
        assertTrue(read < MAX_MESSAGE_BYTES)
        val limit = 3_000L
        val fits = generateSequence(1L) { it + 1 }.first { encodedSize(it + 1) > limit }
        assertEquals(fits, readAttachment(ByteArrayInputStream(ByteArray(fits.toInt())), limit)?.size?.toLong())
        assertNull(readAttachment(ByteArrayInputStream(ByteArray(fits.toInt() + 1)), limit))
    }
}
