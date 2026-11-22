package cc.hafa.inbox.mail

import android.app.Application
import android.content.Intent
import android.net.Uri
import cc.hafa.inbox.core.compose.ComposePrefill
import cc.hafa.inbox.core.mail.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Other apps' `mailto:` links and shares as a new message's start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ComposeIntentTest {
    private val own = "cc.hafa.inbox"

    @Test
    fun aMailtoLinkOpenedOrSentToFillsTheMessage() {
        val link = Uri.parse("mailto:sam@example.com?cc=bo@example.com&subject=Hello&body=Hi%20Sam")
        val expected = ComposeRequest(
            ComposePrefill(
                to = listOf(Person("", "sam@example.com")),
                cc = listOf(Person("", "bo@example.com")),
                subject = "Hello",
                body = "Hi Sam",
            ),
        )
        assertEquals(expected, composeRequestOf(Intent(Intent.ACTION_VIEW, link), own))
        assertEquals(expected, composeRequestOf(Intent(Intent.ACTION_SENDTO, link), own))
    }

    @Test
    fun aSendToWithExtrasAddsThem() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf("sam@example.com"))
            .putExtra(Intent.EXTRA_BCC, "me@example.com")
            .putExtra(Intent.EXTRA_SUBJECT, "Report")
            .putExtra(Intent.EXTRA_TEXT, "Attached.")
        assertEquals(
            ComposeRequest(
                ComposePrefill(
                    to = listOf(Person("", "sam@example.com")),
                    bcc = listOf(Person("", "me@example.com")),
                    subject = "Report",
                    body = "Attached.",
                ),
            ),
            composeRequestOf(intent, own),
        )
    }

    @Test
    fun aShareCarriesItsTextAndFiles() {
        val photo = Uri.parse("content://com.example.photos/1")
        val single = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo).putExtra(Intent.EXTRA_TEXT, "Look")
        assertEquals(ComposeRequest(ComposePrefill(body = "Look"), listOf(photo)), composeRequestOf(single, own))

        val second = Uri.parse("content://com.example.photos/2")
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, second))
        assertEquals(listOf(photo, second), composeRequestOf(many, own)?.files)
    }

    @Test
    fun sharedTextIsReadAsMarkdown() {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Some *bold* words")
        assertEquals("Some *bold* words", composeRequestOf(share, own)?.prefill?.body)
    }

    @Test
    fun sharedHtmlKeepsItsFormatting() {
        val html = "<p>Some <b>bold</b> and <i>slanted</i> words, <a href=\"https://example.com/a\">a link</a></p>" +
            "<ul><li>one</li><li>two</li></ul>"
        val share = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_TEXT, "Some bold and slanted words, a link\none\ntwo")
            .putExtra(Intent.EXTRA_HTML_TEXT, html)
        assertEquals(
            "Some *bold* and _slanted_ words, [a link](https://example.com/a)\n- one\n- two",
            composeRequestOf(share, own)?.prefill?.body,
        )
    }

    @Test
    fun blankSharedHtmlLeavesThePlainText() {
        val share = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_TEXT, "Look")
            .putExtra(Intent.EXTRA_HTML_TEXT, "<p> </p>")
        assertEquals("Look", composeRequestOf(share, own)?.prefill?.body)
    }

    @Test
    fun onlyFilesAnotherAppHandsOverAreAttached() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf(
                Uri.parse("file:///data/data/cc.hafa.inbox/databases/mail.db"),
                Uri.parse("content://cc.hafa.inbox.files/drafts/a"),
                Uri.parse("content://com.example.docs/1"),
            ),
        )
        assertEquals(listOf(Uri.parse("content://com.example.docs/1")), composeRequestOf(intent, own)?.files)
    }

    @Test
    fun addressesMayComeAsAStringList() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putStringArrayListExtra(Intent.EXTRA_EMAIL, arrayListOf("sam@example.com", "bo@example.com"))
            .putStringArrayListExtra(Intent.EXTRA_CC, arrayListOf("me@example.com"))
        val prefill = composeRequestOf(intent, own)?.prefill
        assertEquals(listOf(Person("", "sam@example.com"), Person("", "bo@example.com")), prefill?.to)
        assertEquals(listOf(Person("", "me@example.com")), prefill?.cc)
    }

    @Test
    fun anIntentDeliveredAgainFromRecentsAsksForNothing() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:sam@example.com"))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(composeRequestOf(link, own))
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Look")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(composeRequestOf(share, own))
    }

    @Test
    fun otherIntentsAskForNothing() {
        assertNull(composeRequestOf(Intent(Intent.ACTION_MAIN), own))
        assertNull(composeRequestOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), own))
    }
}
