package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHtmlTest {
    @Test
    fun `the view's policy allows remote images only when shown`() {
        assertEquals(
            "default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:",
            messageCsp(false),
        )
        assertTrue(messageCsp(true).contains("img-src https: http: data: blob:;"))
        val document = messageDocument("<p>Hi</p>", false)
        assertTrue(document.contains("http-equiv=\"Content-Security-Policy\""))
        assertTrue(document.contains("<body><p>Hi</p></body>"))
        assertFalse(document.contains("script-src"))
    }

    @Test
    fun `styles inline code as a chip only when given its color`() {
        assertFalse(messageDocument("<code>x</code>", false).contains(">code{"))
        val document = messageDocument("<code>x</code>", false, plain = true, codeBackground = "#f3f0ea")
        assertTrue(document.contains(":not(pre)>code{font-family:monospace;font-size:13px;padding:1px 4px;border-radius:4px;background:#f3f0ea}"))
        assertTrue(document.contains("html[data-plain] :not(pre)>code{background-color:#f3f0ea!important}"))
    }

    @Test
    fun `tells designed mail from plain`() {
        assertFalse(hasOwnBackground("<p>Hi</p>"))
        assertFalse(hasOwnBackground("<table bgcolor=\"#ffffff\"><tr><td style=\"background:#FFF\">Hi</td></tr></table>"))
        assertFalse(hasOwnBackground("<div style=\"background-color: rgba(0, 0, 0, 0)\">Hi</div>"))
        assertTrue(hasOwnBackground("<table bgcolor=\"#1a73e8\"><tr><td>Hi</td></tr></table>"))
        assertTrue(hasOwnBackground("<div style=\"background: linear-gradient(red, blue)\">Hi</div>"))
        assertTrue(hasOwnBackground("<table><tr><td background=\"https://x.example/bg.png\">Hi</td></tr></table>"))
        assertTrue(hasOwnBackground("<div style=\"background-color:#e0e0e0\">Hi</div>"))
        assertTrue(messageDocument("<p>Hi</p>", false, plain = true).startsWith("<!doctype html><html data-plain>"))
        // As web's phone frame: designed mail gets room above and below inside its box, plain mail barely any.
        assertTrue(messageDocument("<p>Hi</p>", false).contains("body{padding:12px 0}"))
        assertTrue(messageDocument("<p>Hi</p>", false, plain = true).contains("html[data-plain] body{padding:2px 0}"))
    }

    @Test
    fun `spots remote image sources`() {
        assertTrue(isRemoteUrl("https://t.example/p.gif"))
        assertTrue(isRemoteUrl("//cdn.example/x.png"))
        assertFalse(isRemoteUrl("data:image/png;base64,AAAA"))
        assertFalse(isRemoteUrl("cid:logo@x"))
        assertTrue(hasRemoteCssUrl("background: url('https://t.example/bg.png')"))
        assertFalse(hasRemoteCssUrl("background: url(data:image/png;base64,AA)"))
    }

    @Test
    fun `drops scripts, handlers, forms and frames`() {
        val clean = cleanMessageHtml(
            "<p onclick=\"steal()\">Hi<script>steal()</script></p><form><input name=a></form>" +
                "<iframe src=\"https://x.example\"></iframe><a href=\"javascript:steal()\">x</a>",
            emptyMap(),
        )
        assertFalse(clean.html.contains("script"))
        assertFalse(clean.html.contains("onclick"))
        assertFalse(clean.html.contains("<form"))
        assertFalse(clean.html.contains("<input"))
        assertFalse(clean.html.contains("iframe"))
        assertFalse(clean.html.contains("javascript:"))
        assertTrue(clean.html.contains("Hi"))
    }

    @Test
    fun `keeps layout and swaps cid images for data urls`() {
        val clean = cleanMessageHtml(
            "<style>p{color:red}</style><table cellpadding=\"4\"><tr><td style=\"color:blue\">x</td></tr></table>" +
                "<img src=\"cid:logo@x\"><img src=\"cid:missing\"><a href=\"https://a.example\">a</a>",
            mapOf("logo@x" to "data:image/png;base64,AAAA"),
        )
        assertTrue(clean.html.contains("<style>p{color:red}</style>"))
        assertTrue(clean.html.contains("cellpadding=\"4\""))
        assertTrue(clean.html.contains("style=\"color:blue\""))
        assertTrue(clean.html.contains("src=\"data:image/png;base64,AAAA\""))
        assertFalse(clean.html.contains("cid:"))
        assertTrue(clean.html.contains("rel=\"noopener noreferrer\""))
        assertFalse(clean.hasRemoteImages)
    }

    @Test
    fun `notices remote images in tags, styles and style sheets`() {
        assertTrue(cleanMessageHtml("<img src=\"https://t.example/p.gif\">", emptyMap()).hasRemoteImages)
        assertTrue(cleanMessageHtml("<div style=\"background:url(https://t.example/a.png)\">x</div>", emptyMap()).hasRemoteImages)
        assertTrue(cleanMessageHtml("<style>a{background:url(\"http://t.example/a.png\")}</style>", emptyMap()).hasRemoteImages)
        assertTrue(cleanMessageHtml("<table background=\"https://t.example/bg.png\"></table>", emptyMap()).hasRemoteImages)
    }

    @Test
    fun `plain text is escaped`() {
        assertEquals("<div style=\"white-space:pre-wrap\">a &lt;b&gt; &amp; c</div>", plainTextHtml("a <b> & c"))
        assertEquals("<div style=\"white-space:pre-wrap\">  a\n\nb</div>", plainTextHtml("  a\n\nb\r\n\r\n "))
    }

    @Test
    fun `fixed layouts wider than the view are narrowed to fit it`() {
        val fitted = fitWideLayouts(
            "<table width=\"600\"><tr><td style=\"min-width: 600px;color:red\">A</td></tr></table>" +
                "<div style=\"width:300px\">B</div><table><tr><td width=\"250\">C</td><td width=\"250\">D</td></tr></table>",
            360,
        )
        assertTrue(fitted.contains("<table width=\"600\" style=\"width:100%!important;min-width:0!important;max-width:100%!important\">"))
        assertTrue(fitted.contains("style=\"min-width: 600px;color:red;width:auto!important;min-width:0!important;max-width:100%!important\""))
        assertTrue(fitted.contains("<div style=\"width:300px\">B</div>"))
        assertTrue(fitted.contains("<table style=\"width:100%!important;min-width:0!important;max-width:100%!important\"><tbody><tr><td width=\"250\">C</td>"))
    }

    @Test
    fun `mail that fits, or a view not yet measured, is left as it is`() {
        val html = "<table width=\"100%\"><tr><td style=\"max-width:600px\">A</td></tr></table>"
        assertEquals(html, fitWideLayouts(html, 360))
        assertEquals("<table width=\"600\"></table>", fitWideLayouts("<table width=\"600\"></table>", 0))
    }
}
