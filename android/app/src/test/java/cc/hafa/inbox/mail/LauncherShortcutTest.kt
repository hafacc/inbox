package cc.hafa.inbox.mail

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import org.robolectric.RuntimeEnvironment
import cc.hafa.inbox.MainActivity
import cc.hafa.inbox.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** The launcher's shortcuts: what each asks for, that it is taken once, and New todo only while todos are on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherShortcutTest {
    @Test
    fun eachShortcutsActionNamesItsRequest() {
        for (shortcut in LauncherShortcut.entries) {
            assertEquals(shortcut, launcherShortcutOf(Intent(shortcut.action)))
        }
        assertNull(launcherShortcutOf(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun aShortcutReopenedFromRecentsAsksForNothing() {
        val intent = Intent(LauncherShortcut.SEARCH.action).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(launcherShortcutOf(intent))
    }

    @Test
    fun aRequestIsTakenOnceAndTheNewestWins() {
        val requests = LauncherRequests()
        requests.ask(LauncherShortcut.NEW_EMAIL)
        requests.ask(LauncherShortcut.NEW_TODO)
        assertFalse(requests.take(LauncherShortcut.NEW_EMAIL))
        assertTrue(requests.take(LauncherShortcut.NEW_TODO))
        assertFalse(requests.take(LauncherShortcut.NEW_TODO))
        assertNull(requests.requests.value)
    }

    @Test
    fun theStaticShortcutsOpenMainActivityWithTheirActionsAndLabels() {
        val context = RuntimeEnvironment.getApplication()
        val parser = context.resources.getXml(R.xml.shortcuts)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.name == "shortcut") {
                labels += context.getString(parser.getAttributeResourceValue(ANDROID_NS, "shortcutShortLabel", 0))
                assertTrue(parser.getAttributeResourceValue(ANDROID_NS, "icon", 0) != 0)
            } else if (parser.name == "intent") {
                actions += parser.getAttributeValue(ANDROID_NS, "action")
                assertEquals(MainActivity::class.java.name, parser.getAttributeValue(ANDROID_NS, "targetClass"))
                assertEquals(context.packageName, parser.getAttributeValue(ANDROID_NS, "targetPackage"))
            }
        }
        assertEquals(listOf("New email", "Search"), labels)
        assertEquals(listOf(LauncherShortcut.NEW_EMAIL.action, LauncherShortcut.SEARCH.action), actions)
    }

    @Test
    fun newTodoIsOfferedOnlyWhileTodosAreOn() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ShortcutManager::class.java)
        offerTodoShortcut(context, todosOn = true)
        val offered = manager.dynamicShortcuts.single()
        assertEquals("New todo", offered.shortLabel.toString())
        assertEquals(LauncherShortcut.NEW_TODO.action, offered.intent?.action)
        offerTodoShortcut(context, todosOn = false)
        assertTrue(manager.dynamicShortcuts.isEmpty())
        offerTodoShortcut(context, todosOn = true)
        assertEquals(listOf("new_todo"), manager.dynamicShortcuts.map { it.id })
    }
}
