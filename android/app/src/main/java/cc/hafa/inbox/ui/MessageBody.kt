package cc.hafa.inbox.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import cc.hafa.inbox.core.mail.safeHref
import cc.hafa.inbox.core.menu.HOLD_TAP
import cc.hafa.inbox.core.menu.linkHoldWedges
import cc.hafa.inbox.ui.theme.paper

// Kept on the WebView as its tag, so a document loads only when it changes.
private class ShownMessage {
    var document: String? = null
    var showImages: Boolean = false
    var onLoaded: (() -> Unit)? = null
    var onZoom: ((Boolean) -> Unit)? = null
    var zoomed: Boolean = false
    // Device pixels per CSS pixel, as WebView last reported it; 0 until it zooms.
    var scale: Float = 0f
}

// A scale this much past the one a body opens at counts as zoomed in.
private const val ZOOM_TOLERANCE = 1.01f

/** Whether a body drawn at [scale] (device pixels per CSS pixel) is zoomed in past its [restScale]. */
internal fun bodyZoomed(scale: Float, restScale: Float): Boolean = scale > restScale * ZOOM_TOLERANCE

/**
 * A sanitized message document in a WebView: scripts, file and content access off; the network is
 * blocked unless [showImages], and then the document's CSP lets only images load, straight from the
 * sender. Links open in the browser; a long press on one opens the hold menu with Open, Copy link
 * and Share, its full address in the pill, and TalkBack offers Copy link and Share among a link's
 * actions. Two fingers pinch it bigger or smaller, from the size it opens at up to WebView's
 * limit; zoomed, a drag pans it and a double tap puts it back. [onLoaded] hears when a document
 * first draws, [onZoom] whether it is zoomed in, each time that changes.
 */
@Composable
fun MessageWebView(
    document: String,
    showImages: Boolean,
    modifier: Modifier = Modifier,
    onLoaded: (() -> Unit)? = null,
    onZoom: ((Boolean) -> Unit)? = null,
) {
    val menu = LocalHoldMenu.current
    val root = LocalView.current
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            LinkActionsWebView(context).apply {
                tag = ShownMessage()
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                isVerticalScrollBarEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    // As soon as the page first draws, not once every image has loaded.
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        (view.tag as ShownMessage).onLoaded?.invoke()
                    }

                    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                        val shown = view.tag as ShownMessage
                        shown.scale = newScale
                        val zoomed = bodyZoomed(newScale, view.resources.displayMetrics.density)
                        if (zoomed != shown.zoomed) {
                            shown.zoomed = zoomed
                            shown.onZoom?.invoke(zoomed)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openLink(view.context, request.url)
                        return true
                    }
                }
                if (menu != null) holdLinks(menu, root)
            }
        },
        update = { view ->
            val shown = view.tag as ShownMessage
            shown.onLoaded = onLoaded
            shown.onZoom = onZoom
            view.settings.blockNetworkImage = !showImages
            view.settings.blockNetworkLoads = !showImages
            if (shown.document != document || shown.showImages != showImages) {
                shown.document = document
                shown.showImages = showImages
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = WebView::destroy,
    )
}

/** Open [uri] in the app that takes it, the browser for a web page, its scheme in lower case; a toast says when no app can. */
internal fun openLink(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.normalizeScheme()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app opens this link", Toast.LENGTH_SHORT).show()
    }
}

/** Open [href], a link in a todo or the composer, as [openLink] does, when it leads to a web or mail address ([safeHref]). */
internal fun openWebLink(context: Context, href: String) {
    val safe = safeHref(href)
    if (safe == null) {
        Toast.makeText(context, "This link can't be opened", Toast.LENGTH_SHORT).show()
    } else {
        openLink(context, safe.toUri())
    }
}

// A message's WebView whose links offer Copy link and Share to TalkBack, as a long press on one does by touch.
// Zoomed in, a double tap puts it back to the size it opened at.
private class LinkActionsWebView(context: Context) : WebView(context) {
    private var linkActions: LinkActionsProvider? = null
    private val unzoom = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(event: MotionEvent): Boolean {
                val shown = tag as? ShownMessage
                val scale = shown?.scale ?: 0f
                val zoomed = shown?.zoomed == true && scale > 0f
                // A body opens at one CSS pixel per dp, so at the density's scale.
                if (zoomed) zoomBy((resources.displayMetrics.density / scale).coerceIn(MIN_ZOOM_STEP, 1f))
                return zoomed
            }
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A second finger is a pinch: the list around the message mustn't scroll or page under it.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        // The page still sees every touch, so a tap on a link keeps working.
        unzoom.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? {
        val inner = super.getAccessibilityNodeProvider() ?: return null
        return linkActions?.takeIf { it.inner === inner }
            ?: LinkActionsProvider(inner) { id, url -> runLinkAction(context, id, url) }.also { linkActions = it }
    }
}

// Run a link's hold menu action on [url].
private fun runLinkAction(context: Context, id: String, url: String) {
    when (id) {
        "open" -> openLink(context, url.toUri())
        "copy" -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", url))
        "share" -> {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            try {
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // Nothing on the device shares text.
            }
        }
    }
}

private val LINK_ITEMS = listOf(
    HoldMenuItem("open", "Open", Icons.AutoMirrored.Outlined.OpenInNew),
    HoldMenuItem("copy", "Copy link", Icons.Outlined.ContentCopy),
    HoldMenuItem("share", "Share", Icons.Outlined.Share),
)

// The smallest factor WebView.zoomBy takes.
private const val MIN_ZOOM_STEP = 0.01f

// The disc's middle keeps this far (dp) above the screen's bottom.
private const val LINK_BOTTOM_ROOM_DP = 40f

/**
 * The hold menu of the link to [url] pressed at [finger] (pixels in [root]): Open, Copy link and
 * Share, its full address in the pill; [onClose] hears it close.
 */
internal fun linkMenuSpec(context: Context, root: View, finger: Offset, url: String, onClose: () -> Unit = {}): HoldMenuSpec {
    val density = root.resources.displayMetrics.density
    val fingerX = finger.x / density
    val fingerY = finger.y / density
    val centerY = minOf(fingerY, root.height / density - LINK_BOTTOM_ROOM_DP)
    return HoldMenuSpec(
        center = Offset(finger.x, centerY * density),
        wedges = holdWedges(LINK_ITEMS, linkHoldWedges(LINK_ITEMS.map { it.id }, fingerX, fingerY, root.width / density)),
        pill = url.removePrefix("https://").removePrefix("http://"),
        url = true,
        label = "Link actions",
        onPick = { item -> runLinkAction(context, item.id, url) },
        onClose = onClose,
    )
}

/**
 * TalkBack's actions for the links to [urls], as a held link in an email offers them: Open (which
 * a tap on the link does by touch), Copy link and Share, each naming its link when there are several.
 */
internal fun linkAccessibilityActions(context: Context, urls: List<String>): List<CustomAccessibilityAction> =
    urls.distinct().flatMap { url ->
        val name = if (urls.distinct().size > 1) " ${url.removePrefix("https://").removePrefix("http://")}" else ""
        listOf("open" to "Open link", "copy" to "Copy link", "share" to "Share").map { (id, label) ->
            CustomAccessibilityAction("$label$name") {
                runLinkAction(context, id, url)
                true
            }
        }
    }

/**
 * A long press on a link opens [menu] at the finger, which then slides into a wedge and lets go,
 * or lets go and taps one. The message runs no JavaScript, so the link comes from the WebView's
 * own hit test; the finger's moves are watched as they pass to the page.
 */
@SuppressLint("ClickableViewAccessibility")
private fun WebView.holdLinks(menu: HoldMenuState, root: View) {
    val rootOnScreen = IntArray(2)
    var finger = Offset.Zero
    var down = false
    var following = false
    val inRoot = { event: MotionEvent ->
        root.getLocationOnScreen(rootOnScreen)
        Offset(event.rawX - rootOnScreen[0], event.rawY - rootOnScreen[1])
    }
    val show = { url: String ->
        menu.show(linkMenuSpec(context, root, finger, url) { following = false }, sticky = !down)
        if (down) {
            following = true
            // The list around the message mustn't scroll while the finger picks a wedge.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }
    setOnTouchListener { _, event ->
        val at = inRoot(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finger = at
                down = true
                following = false
            }
            MotionEvent.ACTION_MOVE -> if (following) menu.move(at) else finger = at
            MotionEvent.ACTION_UP -> {
                down = false
                // The disc's middle may sit above the finger near the screen's bottom; a tap is by the press.
                if (following) menu.release(at, nearPress = (at - finger).getDistance() < HOLD_TAP * root.resources.displayMetrics.density)
                following = false
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                if (following) menu.cancel()
                following = false
            }
        }
        // While the menu follows the finger, the page doesn't scroll or select under it.
        following && event.actionMasked == MotionEvent.ACTION_MOVE
    }
    setOnLongClickListener {
        val hit = hitTestResult
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra?.let(show) != null
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // A linked image's hit test names the image; the link comes back in a message.
                val reply = Handler(Looper.getMainLooper()) { message ->
                    message.data.getString("url")?.let(show)
                    true
                }
                requestFocusNodeHref(reply.obtainMessage())
                true
            }
            else -> false
        }
    }
}

/** The quiet "…" that shows or hides a quoted message, in threads and the composer. */
@Composable
fun QuoteToggle(open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .size(48.dp)
            .clickable(interactionSource = interactions, indication = null, onClick = onToggle)
            .semantics { contentDescription = if (open) "Hide quoted text" else "Show quoted text" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(36.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(paper.muted)
                .indication(interactions, ripple()),
        ) {
            Text(
                "…",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = paper.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}
