package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImagesTest {
    private val html = "<style>.h{background:url(https://cdn.example/hero.png)}</style>" +
        "<img src=\"https://cdn.example/a.png?x=1&amp;y=2\" alt=\"a\">" +
        "<img src=\"//cdn.example/b.png\">" +
        "<img srcset=\"https://cdn.example/c.png 1x, https://cdn.example/d.png 2x\" src=\"https://cdn.example/c.png\">" +
        "<table background=\"https://cdn.example/bg.png\"><tr><td style=\"background-image:url('http://cdn.example/td.png')\">x</td></tr></table>" +
        "<img src=\"data:image/png;base64,AAAA\"><img src=\"https://cdn.example/a.png?x=1&amp;y=2\">"

    @Test
    fun `collects remote images once, in order, entity-decoded`() {
        assertEquals(
            listOf(
                "https://cdn.example/hero.png",
                "https://cdn.example/a.png?x=1&y=2",
                "https://cdn.example/b.png",
                "https://cdn.example/c.png",
                "https://cdn.example/d.png",
                "https://cdn.example/bg.png",
                "http://cdn.example/td.png",
            ),
            remoteImageUrls(html),
        )
    }

    @Test
    fun `makes protocol-relative images https and keeps the rest`() {
        val shown = absoluteImages(html)
        assertTrue(shown.contains("src=\"https://cdn.example/b.png\""))
        assertFalse(shown.contains("\"//cdn.example"))
        assertTrue(shown.contains("src=\"data:image/png;base64,AAAA\""))
        assertTrue(shown.contains("https://cdn.example/d.png 2x"))
        assertTrue(shown.contains("url('http://cdn.example/td.png')"))
        assertEquals(remoteImageUrls(html), remoteImageUrls(shown))
    }

    @Test
    fun `drops images of other schemes`() {
        val shown = absoluteImages("<img src=\"ftp://x.example/a.png\" alt=\"a\"><p>x</p>")
        assertFalse(shown.contains("ftp:"))
        assertTrue(shown.contains("<p>x</p>"))
    }

    @Test
    fun `the shown view hides no images`() {
        val document = messageDocument("<p>x</p>", true)
        assertTrue(document.contains("img-src https: http: data: blob:"))
        assertFalse(document.contains("visibility:hidden"))
    }
}
