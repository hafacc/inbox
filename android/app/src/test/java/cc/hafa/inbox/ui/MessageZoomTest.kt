package cc.hafa.inbox.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pinch to zoom on an email's body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MessageZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private fun View.findWebView(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebView() }
        else -> null
    }

    @Test
    fun aBodyPinchesWithoutZoomButtons() {
        var root: View? = null
        compose.setContent {
            root = LocalView.current
            MessageWebView("<p>Hello</p>", showImages = false)
        }
        compose.waitForIdle()
        val web = root?.findWebView()
        assertNotNull(web)
        assertTrue(web!!.settings.supportZoom())
        assertTrue(web.settings.builtInZoomControls)
        assertFalse(web.settings.displayZoomControls)
    }

    @Test
    fun aBodyCountsAsZoomedOnlyPastTheScaleItOpensAt() {
        assertFalse(bodyZoomed(2.625f, 2.625f))
        assertFalse(bodyZoomed(2.63f, 2.625f))
        assertFalse(bodyZoomed(2f, 2.625f))
        assertTrue(bodyZoomed(4f, 2.625f))
    }
}
