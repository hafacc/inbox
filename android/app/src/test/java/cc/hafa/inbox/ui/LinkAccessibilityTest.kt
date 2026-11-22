package cc.hafa.inbox.ui

import android.app.Application
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import cc.hafa.inbox.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TalkBack's Copy link and Share on a message's links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LinkAccessibilityTest {
    private val linkId = 1
    private val targetId = 2
    private val textId = 3

    // A page with a link read from its text's URLSpan, one from Chromium's target URL extra, and plain text.
    private val page = object : AccessibilityNodeProvider() {
        val performed = mutableListOf<Pair<Int, Int>>()

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo = AccessibilityNodeInfo().apply {
            when (virtualViewId) {
                linkId -> text = SpannableString("Read more").apply {
                    setSpan(URLSpan("https://example.com/a"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                targetId -> {
                    text = "Unsubscribe"
                    extras.putCharSequence("AccessibilityNodeInfo.targetUrl", "https://example.com/b")
                }
                else -> text = "Hello"
            }
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            performed += virtualViewId to action
            return true
        }
    }

    private val run = mutableListOf<Pair<String, String>>()
    private val provider = LinkActionsProvider(page) { id, url -> run += id to url }

    private fun AccessibilityNodeInfo.actionLabels(): List<String> =
        actionList.filter { it.id == R.id.link_copy || it.id == R.id.link_share }.map { it.label.toString() }

    @Test
    fun aLinkOffersCopyLinkAndShare() {
        assertEquals(listOf("Copy link", "Share"), provider.createAccessibilityNodeInfo(linkId)?.actionLabels())
        assertEquals(listOf("Copy link", "Share"), provider.createAccessibilityNodeInfo(targetId)?.actionLabels())
        assertEquals(emptyList<String>(), provider.createAccessibilityNodeInfo(textId)?.actionLabels())
    }

    @Test
    fun theActionsRunOnTheLinksAddress() {
        assertTrue(provider.performAction(linkId, R.id.link_copy, null))
        assertTrue(provider.performAction(targetId, R.id.link_share, null))
        assertEquals(listOf("copy" to "https://example.com/a", "share" to "https://example.com/b"), run)
        assertTrue(page.performed.isEmpty())
    }

    @Test
    fun everythingElseIsThePagesOwn() {
        assertTrue(provider.performAction(linkId, AccessibilityNodeInfo.ACTION_CLICK, null))
        provider.performAction(textId, R.id.link_copy, null)
        assertEquals(listOf(linkId to AccessibilityNodeInfo.ACTION_CLICK, textId to R.id.link_copy), page.performed)
        assertTrue(run.isEmpty())
    }

    @Test
    fun onlyALinkHasAnAddress() {
        assertEquals("https://example.com/a", linkUrlOf(page.createAccessibilityNodeInfo(linkId)))
        assertNull(linkUrlOf(page.createAccessibilityNodeInfo(textId)))
        assertFalse(linkUrlOf(page.createAccessibilityNodeInfo(targetId)).isNullOrEmpty())
    }
}
