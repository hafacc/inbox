package cc.hafa.inbox.ui

import android.os.Bundle
import android.text.Spanned
import android.text.style.URLSpan
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import cc.hafa.inbox.R

// Where Chromium's WebView puts a link's address on its node, besides a URLSpan on its text.
private const val TARGET_URL_EXTRA = "AccessibilityNodeInfo.targetUrl"

/** The address of the link [info] stands for in a WebView's page, or null when it isn't a link. */
internal fun linkUrlOf(info: AccessibilityNodeInfo): String? {
    val target = info.extras?.getCharSequence(TARGET_URL_EXTRA)?.toString()
    val spanned = (info.text as? Spanned)?.let { text -> text.getSpans(0, text.length, URLSpan::class.java).firstOrNull()?.url }
    return target?.takeIf(String::isNotEmpty) ?: spanned?.takeIf(String::isNotEmpty)
}

/**
 * [inner] (a WebView's own accessibility tree) with Copy link and Share added to each link's
 * actions, so TalkBack offers what a long press on a link offers by touch; [onAction] runs one
 * (`copy` or `share`) on the link's address. Everything else is [inner]'s.
 */
internal class LinkActionsProvider(
    /** the WebView's own provider */
    val inner: AccessibilityNodeProvider,
    private val onAction: (id: String, url: String) -> Unit,
) : AccessibilityNodeProvider() {
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        inner.createAccessibilityNodeInfo(virtualViewId)?.let(::withLinkActions)

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val id = when (action) {
            R.id.link_copy -> "copy"
            R.id.link_share -> "share"
            else -> null
        }
        val url = id?.let { inner.createAccessibilityNodeInfo(virtualViewId)?.let(::linkUrlOf) }
        return if (id != null && url != null) {
            onAction(id, url)
            true
        } else {
            inner.performAction(virtualViewId, action, arguments)
        }
    }

    override fun findAccessibilityNodeInfosByText(text: String, virtualViewId: Int): List<AccessibilityNodeInfo>? =
        inner.findAccessibilityNodeInfosByText(text, virtualViewId)?.map(::withLinkActions)

    override fun findFocus(focus: Int): AccessibilityNodeInfo? = inner.findFocus(focus)?.let(::withLinkActions)

    override fun addExtraDataToAccessibilityNodeInfo(virtualViewId: Int, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?) {
        inner.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)
    }

    private fun withLinkActions(info: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (linkUrlOf(info) != null) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.link_copy, "Copy link"))
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.link_share, "Share"))
        }
        return info
    }
}
