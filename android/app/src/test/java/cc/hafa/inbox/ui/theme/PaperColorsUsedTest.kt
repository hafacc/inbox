package cc.hafa.inbox.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every theme color is drawn somewhere, and the key pieces take the token web uses for them. */
class PaperColorsUsedTest {
    private val sources = File("src/main/java/cc/hafa/inbox/ui").walk()
        .filter { it.extension == "kt" && it.name != "Color.kt" }
        .associate { it.name to it.readText() }

    private fun uses(file: String, token: String): Boolean =
        Regex("""\b(paper|colors)\.$token\b""").containsMatchIn(sources.getValue(file))

    @Test
    fun everyTokenIsUsed() {
        val tokens = PaperColors::class.java.declaredFields.map { it.name }.filter { !it.contains('$') }
        val all = sources.values.joinToString("\n")
        val unused = tokens.filterNot { Regex("""\b(paper|colors)\.$it\b""").containsMatchIn(all) }
        assertTrue("unused theme colors: $unused", unused.isEmpty())
    }

    @Test
    fun keyPiecesUseTheirTokens() {
        assertTrue("focus outlines", uses("Highlight.kt", "focus"))
        assertTrue("hovered rows", uses("Highlight.kt", "hover"))
        assertTrue("text fields", uses("Highlight.kt", "field"))
        assertTrue("menus", uses("SettingsScreen.kt", "raised"))
        assertTrue("the drawer", uses("MailScreen.kt", "sidebar"))
        assertTrue("swiping to Done", uses("ThreadList.kt", "done"))
        assertTrue("deleting", uses("FiltersScreen.kt", "danger"))
    }
}
