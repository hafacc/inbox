package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/sanitize-fixtures.json` against [cleanMessageHtml], as web does against its own. */
class SanitizeFixturesTest {
    private fun normalized(html: String): String =
        html.replace(Regex("""<br\s*/>"""), "<br>").replace(Regex(""">\s+<"""), "><").replace(Regex("""\s+"""), " ")

    @Test
    fun cases() {
        for (case in Fixtures.cases(Fixtures.sanitize, "cases")) {
            val name = case["name"] as String
            val cidUrls = (case["cidUrls"] as Map<*, *>).entries.associate { (key, value) -> key as String to value as String }
            val cidMissing = (case["cidMissing"] as Map<*, *>?).orEmpty().entries.associate { (key, value) -> key as String to value as String }
            val clean = cleanMessageHtml(case["html"] as String, cidUrls, cidMissing)
            val html = normalized(clean.html)
            assertEquals("$name: hasRemoteImages", case["hasRemoteImages"], clean.hasRemoteImages)
            assertEquals("$name: colorSchemes", case["colorSchemes"], clean.colorSchemes)
            for (expected in case["contains"] as List<*>) {
                assertTrue("$name: contains $expected in $html", html.contains(expected as String))
            }
            for (absent in case["absent"] as List<*>) {
                assertFalse("$name: lacks $absent in $html", html.lowercase().contains((absent as String).lowercase()))
            }
        }
    }

    @Test
    fun opensOnline() {
        assertEquals(Fixtures.sanitize["opensOnline"], OPENS_ONLINE)
        for (case in Fixtures.cases(Fixtures.sanitize, "missingLabels")) {
            assertEquals(case["expect"], missingPartLabel(case["filename"] as String, (case["size"] as Number).toLong()))
        }
    }
}
