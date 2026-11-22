package cc.hafa.inbox.ui

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Back goes to the layer that opened last, whatever order the layers are composed in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class LayerBackTest {
    @get:Rule
    val compose = createComposeRule()

    private var back: OnBackPressedDispatcher? = null

    private fun pressBack() = compose.runOnIdle { requireNotNull(back).onBackPressed() }

    @Test
    fun selectionMadeInSearchResultsIsClearedBeforeSearchCloses() {
        var searching by mutableStateOf(false)
        var selected by mutableStateOf(false)
        val closed = mutableListOf<String>()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Column {
                // As in MailScreen: the screen's own layers are composed with it, before search opens.
                LayerBackHandler(open = selected) {
                    selected = false
                    closed += "selection"
                }
                if (searching) {
                    SearchBar(
                        "from:maya",
                        labels = emptyList(),
                        contacts = emptyList(),
                        now = 0L,
                        onSearch = {},
                        onClose = {
                            searching = false
                            closed += "search"
                        },
                    )
                }
            }
        }
        compose.runOnIdle { searching = true }
        compose.runOnIdle { selected = true }
        pressBack()
        assertEquals(listOf("selection"), closed)
        assertTrue("search closed with the selection", searching)
        pressBack()
        assertEquals(listOf("selection", "search"), closed)
    }

    @Test
    fun layersCloseInTheReverseOfTheOrderTheyOpened() {
        var bundle by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var drawer by mutableStateOf(false)
        val closed = mutableListOf<String>()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LayerBackHandler(open = drawer) {
                drawer = false
                closed += "drawer"
            }
            LayerBackHandler(open = selected) {
                selected = false
                closed += "selection"
            }
            LayerBackHandler(open = bundle) {
                bundle = false
                closed += "bundle"
            }
        }
        compose.runOnIdle { selected = true }
        compose.runOnIdle { bundle = true }
        compose.runOnIdle { drawer = true }
        repeat(3) { pressBack() }
        assertEquals(listOf("drawer", "bundle", "selection"), closed)
    }

    @Test
    fun aClosedLayerTakesNoBack() {
        var open by mutableStateOf(true)
        val heard = mutableListOf<String>()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { heard += "screen" }
            LayerBackHandler(open = open) { heard += "layer" }
        }
        compose.runOnIdle { open = false }
        pressBack()
        assertEquals(listOf("screen"), heard)
    }

    @Test
    fun aLayerOnItsWayOutPassesBackToTheOneUnderIt() {
        var leaving by mutableStateOf(false)
        val heard = mutableListOf<String>()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LayerBackHandler { heard += "bundle" }
            CompositionLocalProvider(LocalLayerLeaving provides leaving) {
                ScreenBackHandler { heard += "thread" }
                LayerBackHandler { heard += "thread's sheet" }
            }
        }
        pressBack()
        compose.runOnIdle { leaving = true }
        pressBack()
        assertEquals(listOf("thread's sheet", "bundle"), heard)
    }

    @Test
    fun aComposerOnItsWayDownHoldsBackUntilItIsGone() {
        var leaving by mutableStateOf(false)
        var gone by mutableStateOf(false)
        val heard = mutableListOf<String>()
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BackHandler { heard += "thread" }
            if (!gone) {
                HeldWhileLeaving(leaving) { LayerBackHandler { heard += "composer" } }
            }
        }
        pressBack()
        compose.runOnIdle { leaving = true }
        pressBack()
        assertEquals(listOf("composer"), heard)
        compose.runOnIdle { gone = true }
        pressBack()
        assertEquals(listOf("composer", "thread"), heard)
    }

    @Test
    fun noLayerUsesAComposeTimeBackHandler() {
        // A `BackHandler(enabled = …)` joins the stack when composed, not when its layer opens.
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found no sources", sources.isNotEmpty())
        val offenders = sources.filter { file ->
            val text = file.readText()
            "BackHandler(enabled" in text.replace("`BackHandler(enabled = …)`", "")
        }
        assertEquals(emptyList<File>(), offenders)
    }
}
