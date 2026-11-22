package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.htmlunit.cssparser.dom.CSSMediaRuleImpl
import org.htmlunit.cssparser.dom.CSSStyleRuleImpl
import org.htmlunit.cssparser.parser.CSSOMParser
import org.htmlunit.cssparser.parser.InputSource
import org.htmlunit.cssparser.parser.LexicalUnit
import org.htmlunit.cssparser.parser.javacc.CSS3Parser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import kotlin.math.abs

/** Runs `shared/testdata/dark-mail-fixtures.json`, as web's dark-mail specs do. */
class DarkMailFixturesTest {
    private val fixtures = Fixtures.load("dark-mail-fixtures.json")

    private fun color(value: Any?): Rgba {
        val channels = (value as List<*>).map { (it as Number).toDouble() }
        return Rgba(channels[0].toInt(), channels[1].toInt(), channels[2].toInt(), channels.getOrElse(3) { 1.0 })
    }

    private val palette: DarkPalette = (fixtures["palette"] as Map<*, *>).let {
        DarkPalette(color(it["page"]), color(it["text"]), color(it["link"]))
    }

    private fun numbers(css: String): List<Double> = if (css.startsWith("#")) {
        listOf(1, 3, 5).map { css.substring(it, it + 2).toInt(16).toDouble() }
    } else {
        css.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim().toDouble() }
    }

    // Within 1 per channel of the fixture's color, allowing for float differences between platforms.
    private fun assertColor(message: String, expected: String, actual: String?) {
        assertNotNull(message, actual)
        if (expected == actual) return
        val (want, have) = numbers(expected) to numbers(actual!!)
        assertEquals("$message: $actual vs $expected", want.size, have.size)
        want.zip(have).forEachIndexed { index, (one, two) ->
            assertTrue("$message: $actual vs $expected", abs(one - two) <= if (index < 3) 1.0 else 0.001)
        }
    }

    @Test
    fun parsing() {
        for (case in Fixtures.cases(fixtures, "parsing")) {
            val input = case["input"] as String
            val parsed = when (case["kind"]) {
                "hex" -> hexColor(input)
                "named" -> namedColor(input)
                "attribute" -> attributeColor(input)
                else -> functionColor(
                    input,
                    (case["args"] as List<*>).map {
                        val argument = it as Map<*, *>
                        ColorArgument((argument["value"] as Number).toDouble(), argument["unit"] as String)
                    },
                )
            }
            assertEquals("${case["kind"]} $input", case["expect"], parsed?.let(::cssColor))
        }
    }

    @Test
    fun turned() {
        for (case in Fixtures.cases(fixtures, "turned")) {
            val role = if (case["role"] == "text") ColorRole.TEXT else ColorRole.BACKGROUND
            assertColor("${case["role"]} ${case["color"]}", case["expect"] as String, cssColor(darkColor(color(case["color"]), role, palette)))
        }
    }

    @Test
    fun readable() {
        for (case in Fixtures.cases(fixtures, "readable")) {
            val background = color(case["background"])
            val result = readableText(color(case["text"]), background)
            assertColor("${case["text"]} on ${case["background"]}", case["expect"] as String, cssColor(result))
            assertTrue(contrast(result, background) >= MIN_CONTRAST)
        }
    }

    @Test
    fun rules() {
        for (case in Fixtures.cases(fixtures, "roles")) {
            assertEquals(case["property"] as String, case["role"], colorRole(case["property"] as String)?.name?.lowercase())
        }
        for (case in Fixtures.cases(fixtures, "media")) {
            assertEquals(case["query"] as String, case["applies"], mediaApplies(case["query"] as String))
        }
        for (case in Fixtures.cases(fixtures, "ownDark")) {
            @Suppress("UNCHECKED_CAST")
            val own = hasOwnDarkDesign(case["media"] as List<String>, case["colorSchemes"] as List<String>)
            assertEquals("$case", case["own"], own)
        }
        for (case in Fixtures.cases(fixtures, "darkMail")) {
            val samples = (case["samples"] as List<*>).map {
                val sample = it as Map<*, *>
                TextSample(color(sample["background"]), (sample["weight"] as Number).toInt())
            }
            assertEquals("$case", case["dark"], isDarkMail(samples))
        }
        for (case in Fixtures.cases(fixtures, "lengths")) {
            assertEquals(case["text"] as String, (case["length"] as Number).toInt(), visibleLength(case["text"] as String))
        }
    }

    private fun units(first: LexicalUnit?): List<LexicalUnit> = generateSequence(first) { it.nextLexicalUnit }.toList()

    private fun colorsIn(first: LexicalUnit?): List<String> = units(first).flatMap { unit ->
        val color = when (unit.lexicalUnitType) {
            LexicalUnit.LexicalUnitType.IDENT -> namedColor(unit.stringValue)
            LexicalUnit.LexicalUnitType.RGBCOLOR, LexicalUnit.LexicalUnitType.HSLCOLOR -> functionColor(
                unit.functionName,
                units(unit.parameters).filter { it.lexicalUnitType.name.startsWith("OPERATOR").not() }.map {
                    if (it.lexicalUnitType == LexicalUnit.LexicalUnitType.PERCENTAGE) ColorArgument(it.doubleValue, "%")
                    else if (it.lexicalUnitType == LexicalUnit.LexicalUnitType.INTEGER) ColorArgument(it.integerValue.toDouble())
                    else ColorArgument(it.doubleValue)
                },
            )
            else -> null
        }
        when {
            color != null -> if (color.alpha > 0) listOf(cssColor(color)) else emptyList()
            unit.parameters != null -> colorsIn(unit.parameters)
            else -> emptyList()
        }
    }

    private fun lexical(text: String): LexicalUnit? = CSS3Parser().parsePropertyValue(InputSource(StringReader(text)))

    private fun declarationColors(style: String, property: String): List<String> =
        CSSOMParser().parseStyleDeclaration(style).properties.lastOrNull { it.name.lowercase() == property }
            ?.let { colorsIn(lexical(it.value.cssText)) }.orEmpty()

    private fun sheetColors(text: String): Set<String> {
        val colors = mutableSetOf<String>()
        fun visit(rules: List<org.htmlunit.cssparser.dom.AbstractCSSRuleImpl>) {
            for (rule in rules) {
                if (rule is CSSStyleRuleImpl) rule.style.properties.forEach { colors += colorsIn(lexical(it.value.cssText)) }
                if (rule is CSSMediaRuleImpl) visit(rule.cssRules.rules)
            }
        }
        visit(CSSOMParser().parseStyleSheet(InputSource(StringReader(text)), null).cssRules.rules)
        return colors
    }

    @Test
    fun emails() {
        for (case in Fixtures.cases(fixtures, "emails")) {
            val name = case["name"] as String
            val html = case["html"] as String
            @Suppress("UNCHECKED_CAST")
            val output = darkenMessageHtml(html, palette, case["colorSchemes"] as List<String>, case["plain"] as Boolean)
            if (case["unchanged"] == true) {
                assertEquals(name, html, output)
                continue
            }
            val document = Jsoup.parseBodyFragment(output)
            for (entry in (case["expect"] as List<*>?).orEmpty()) {
                val check = entry as Map<*, *>
                val element = document.selectFirst(check["selector"] as String)
                assertNotNull("$name ${check["selector"]}", element)
                val style = element!!.attr("style")
                (check["color"] as String?)?.let { assertColor("$name ${check["selector"]} color", it, declarationColors(style, "color").lastOrNull()) }
                (check["background"] as String?)?.let {
                    assertColor("$name ${check["selector"]} background", it, declarationColors(style, "background-color").lastOrNull())
                }
            }
            val colors = document.getElementsByTag("style").flatMap { sheetColors(it.data()) }.toSet()
            for (expected in (case["sheetColors"] as List<*>?).orEmpty()) assertTrue("$name: sheet has $expected in $colors", expected in colors)
            for (absent in (case["sheetAbsent"] as List<*>?).orEmpty()) assertTrue("$name: sheet lacks $absent in $colors", absent !in colors)
            for (entry in (case["inline"] as List<*>?).orEmpty()) {
                val check = entry as Map<*, *>
                val style = document.selectFirst(check["selector"] as String)!!.attr("style")
                val found = declarationColors(style, check["property"] as String)
                val expected = check["colors"] as List<*>
                assertEquals("$name inline ${check["property"]}", expected.size, found.size)
                expected.zip(found).forEach { (want, have) -> assertColor("$name inline", want as String, have) }
            }
        }
    }
}
