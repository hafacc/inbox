package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnwrapMessageLinksTest {
    @Test
    fun unwrapsRedirectsAndKeepsText() {
        val html = """<a href="https://www.google.com/url?q=https://example.com/a&amp;sa=D">Read</a>"""
        assertEquals("""<a href="https://example.com/a" rel="noopener noreferrer">Read</a>""", cleanMessageHtml(html, emptyMap()).html)
    }

    @Test
    fun leavesOtherLinksAlone() {
        val html = """<p><a href="https://example.com/b">B</a></p>"""
        assertTrue(cleanMessageHtml(html, emptyMap()).html.contains("""href="https://example.com/b""""))
    }

    @Test
    fun collectsRemoteImagesInTheSamePass() {
        val html = """<img src="//t.example/a.png"><div style="background:url(https://t.example/b.png)">x</div>"""
        val clean = cleanMessageHtml(html, emptyMap())
        assertEquals(remoteImageUrls(clean.html), clean.remoteImageUrls)
        assertEquals(listOf("https://t.example/a.png", "https://t.example/b.png"), clean.remoteImageUrls)
    }
}
