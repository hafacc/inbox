package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MimeTest {
    @Test
    fun `decodes base64url's - and _ into bytes`() {
        assertEquals(listOf(0xfb, 0xff, 0xbf), decodeBase64Url("-_-_").map { it.toInt() and 0xff })
    }

    @Test
    fun `reads headers case-insensitively`() {
        assertEquals("Hi", headerValue(listOf(GmailHeader("SUBJECT", "Hi")), "subject"))
        assertNull(headerValue(null, "Subject"))
    }
}
