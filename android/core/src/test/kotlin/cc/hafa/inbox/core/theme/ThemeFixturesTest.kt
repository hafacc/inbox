package cc.hafa.inbox.core.theme

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/theme-fixtures.json`, so label tones, avatars and colors match web's. */
class ThemeFixturesTest {
    private val fixtures by lazy { Fixtures.load("theme-fixtures.json") }

    @Test
    fun labelTones() {
        for (case in Fixtures.cases(fixtures, "labelTone")) {
            assertEquals("${case["hex"]}", case["tone"], labelTone(case["hex"] as String))
        }
    }

    @Test
    fun avatars() {
        for (case in Fixtures.cases(fixtures, "avatarIndex")) {
            assertEquals("${case["email"]}", (case["index"] as Number).toInt(), avatarIndex(case["email"] as String, (case["count"] as Number).toInt()))
        }
    }

    @Test
    fun colors() {
        for (case in Fixtures.cases(fixtures, "colors")) {
            val mode = if (case["mode"] == "dark") ColorMode.DARK else ColorMode.LIGHT
            val colors = ThemePalette.hexColors(case["theme"] as String, mode)
            for ((token, value) in case["expect"] as Map<*, *>) {
                assertEquals("${case["theme"]} ${case["mode"]} $token", value, colors[token])
            }
        }
    }

    @Test
    fun emailStyles() {
        for (case in Fixtures.cases(fixtures, "emailStyle")) {
            val mode = if (case["mode"] == "dark") ColorMode.DARK else ColorMode.LIGHT
            assertEquals("${case["theme"]} ${case["mode"]}", case["css"], ThemePalette.emailStyle(case["theme"] as String, mode))
        }
    }

    @Test
    fun unknownThemesFallBackToGreen() {
        assertEquals("plum", ThemePalette.themeIdFrom("plum"))
        assertEquals("green", ThemePalette.themeIdFrom("neon"))
        assertEquals("green", ThemePalette.themeIdFrom(null))
    }
}
