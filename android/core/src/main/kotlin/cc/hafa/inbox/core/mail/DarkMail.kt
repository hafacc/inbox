package cc.hafa.inbox.core.mail

import org.htmlunit.cssparser.dom.AbstractCSSRuleImpl
import org.htmlunit.cssparser.dom.CSSMediaRuleImpl
import org.htmlunit.cssparser.dom.CSSStyleDeclarationImpl
import org.htmlunit.cssparser.dom.CSSStyleRuleImpl
import org.htmlunit.cssparser.dom.CSSStyleSheetImpl
import org.htmlunit.cssparser.dom.Property
import org.htmlunit.cssparser.parser.CSSErrorHandler
import org.htmlunit.cssparser.parser.CSSException
import org.htmlunit.cssparser.parser.CSSOMParser
import org.htmlunit.cssparser.parser.CSSParseException
import org.htmlunit.cssparser.parser.InputSource
import org.htmlunit.cssparser.parser.LexicalUnit
import org.htmlunit.cssparser.parser.LexicalUnit.LexicalUnitType
import org.htmlunit.cssparser.parser.condition.Condition
import org.htmlunit.cssparser.parser.condition.NotPseudoClassCondition
import org.htmlunit.cssparser.parser.javacc.CSS3Parser
import org.htmlunit.cssparser.parser.selector.ChildSelector
import org.htmlunit.cssparser.parser.selector.DescendantSelector
import org.htmlunit.cssparser.parser.selector.DirectAdjacentSelector
import org.htmlunit.cssparser.parser.selector.ElementSelector
import org.htmlunit.cssparser.parser.selector.GeneralAdjacentSelector
import org.htmlunit.cssparser.parser.selector.Selector
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Selector.SelectorParseException
import java.io.IOException
import java.io.StringReader
import java.util.Optional

private sealed interface CssValue {
    data class Color(val color: Rgba) : CssValue
    data object Inherit : CssValue
    data object Initial : CssValue
    data object Current : CssValue
}

private enum class Longhand { COLOR, BACKGROUND_COLOR }

private data class Declaration(val property: Longhand, val value: CssValue, val important: Boolean)

private data class Rule(val selector: String, val specificity: List<Int>, val order: Int, val declarations: List<Declaration>)

private data class Candidate(
    val declaration: Declaration,
    val inline: Boolean,
    val specificity: List<Int>,
    val order: Int,
)

private sealed interface TextValue {
    data class Author(val color: Rgba) : TextValue
    data object Default : TextValue
    data object Link : TextValue
}

private data class Resolved(val text: TextValue, val ownBackground: Rgba?, val behindOriginal: Rgba, val behindFinal: Rgba)

private data class Write(val background: Rgba?, val text: Rgba?)

private class Sheets {
    val rules = mutableListOf<Rule>()
    val media = mutableListOf<String>()
    val colorSchemes = mutableListOf<String>()
}

// Pseudo-classes that depend only on the document, so a rule using them can be matched as written.
private val STRUCTURAL = setOf(
    "first-child", "last-child", "only-child", "nth-child", "nth-last-child", "first-of-type", "last-of-type",
    "only-of-type", "nth-of-type", "nth-last-of-type", "root", "empty", "not",
)

// Elements whose `bgcolor` attribute browsers draw.
private val BGCOLOR_TAGS = setOf("body", "table", "thead", "tbody", "tfoot", "tr", "td", "th")
private val COLOR_UNITS = setOf(LexicalUnitType.RGBCOLOR, LexicalUnitType.HSLCOLOR)
private val FUNCTION_UNITS = setOf(LexicalUnitType.FUNCTION, LexicalUnitType.FUNCTION_CALC)
private val ANGLE_UNITS = mapOf(
    LexicalUnitType.DEGREE to "deg",
    LexicalUnitType.TURN to "turn",
    LexicalUnitType.RADIAN to "rad",
    LexicalUnitType.GRADIAN to "grad",
)
private val WHITE = Rgba(255, 255, 255)

// What mail assumes unset text and links look like: browsers' defaults on white.
private val DEFAULT_TEXT = Rgba(0, 0, 0)
private val DEFAULT_LINK = Rgba(0, 0, 238)

// Mail's CSS is often broken; what can't be parsed is skipped, as browsers do.
private object QuietErrors : CSSErrorHandler {
    override fun warning(exception: CSSParseException) = Unit

    override fun error(exception: CSSParseException) = Unit

    override fun fatalError(exception: CSSParseException) = Unit
}

private fun cssomParser(): CSSOMParser = CSSOMParser().apply { setErrorHandler(QuietErrors) }

private fun units(first: LexicalUnit?): List<LexicalUnit> = generateSequence(first) { it.nextLexicalUnit }.toList()

// Mail repeats the same few values over and over, and parsing them was most of darkening's time;
// the units are only read, so one parse serves every use.
private val lexicalKnown = object : LinkedHashMap<String, Optional<LexicalUnit>>(LEXICAL_KEPT, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional<LexicalUnit>>): Boolean =
        size > LEXICAL_KEPT
}
private const val LEXICAL_KEPT = 4096

private fun lexical(value: String): LexicalUnit? {
    synchronized(lexicalKnown) { lexicalKnown[value] }?.let { return it.orElse(null) }
    val parsed = try {
        CSS3Parser().apply { setErrorHandler(QuietErrors) }.parsePropertyValue(InputSource(StringReader(value)))
    } catch (_: IOException) {
        null
    } catch (_: CSSException) {
        null
    }
    synchronized(lexicalKnown) { lexicalKnown[value] = Optional.ofNullable(parsed) }
    return parsed
}

private fun unitColor(unit: LexicalUnit): Rgba? = when (unit.lexicalUnitType) {
    LexicalUnitType.IDENT -> namedColor(unit.stringValue)
    in COLOR_UNITS -> {
        val args = mutableListOf<ColorArgument>()
        var valid = true
        for (parameter in units(unit.parameters)) {
            when (val type = parameter.lexicalUnitType) {
                LexicalUnitType.OPERATOR_COMMA, LexicalUnitType.OPERATOR_SLASH -> Unit
                LexicalUnitType.INTEGER -> args += ColorArgument(parameter.integerValue.toDouble())
                LexicalUnitType.REAL -> args += ColorArgument(parameter.doubleValue)
                LexicalUnitType.PERCENTAGE -> args += ColorArgument(parameter.doubleValue, "%")
                in ANGLE_UNITS -> args += ColorArgument(parameter.doubleValue, ANGLE_UNITS.getValue(type))
                else -> valid = false
            }
        }
        if (valid) functionColor(unit.functionName, args) else null
    }
    else -> null
}

private fun keyword(unit: LexicalUnit): CssValue? = when {
    unit.lexicalUnitType == LexicalUnitType.INHERIT -> CssValue.Inherit
    unit.lexicalUnitType != LexicalUnitType.IDENT -> null
    else -> when (unit.stringValue.lowercase()) {
        "inherit" -> CssValue.Inherit
        "initial", "unset", "revert", "revert-layer" -> CssValue.Initial
        "currentcolor" -> CssValue.Current
        else -> null
    }
}

private fun singleValue(first: LexicalUnit?): CssValue? {
    val only = units(first).singleOrNull() ?: return null
    return unitColor(only)?.let { CssValue.Color(it) } ?: keyword(only)
}

// The background color a `background` shorthand sets: its color, or transparent when it names none.
private fun shorthandBackground(first: LexicalUnit?): CssValue? {
    val all = units(first)
    if (all.isEmpty()) return null
    if (all.size == 1) keyword(all[0])?.takeIf { it != CssValue.Current }?.let { return it }
    var found: CssValue = CssValue.Color(Rgba(0, 0, 0, 0.0))
    for (unit in all) {
        val color = unitColor(unit)
        if (color != null) found = CssValue.Color(color) else if (keyword(unit) == CssValue.Current) found = CssValue.Current
    }
    return found
}

private fun declarationsOf(style: CSSStyleDeclarationImpl, colorSchemes: MutableList<String>): List<Declaration> {
    val declarations = mutableListOf<Declaration>()
    for (property in style.properties) {
        val name = property.name.lowercase()
        val text = property.value?.cssText ?: continue
        if (name == "color-scheme") colorSchemes += text
        val declaration = when (name) {
            "color" -> singleValue(lexical(text))?.let { Declaration(Longhand.COLOR, it, property.isImportant) }
            "background-color" -> singleValue(lexical(text))?.let { Declaration(Longhand.BACKGROUND_COLOR, it, property.isImportant) }
            "background" -> shorthandBackground(lexical(text))?.let { Declaration(Longhand.BACKGROUND_COLOR, it, property.isImportant) }
            else -> null
        }
        if (declaration != null) declarations += declaration
    }
    return declarations
}

private fun pseudoName(condition: Condition): String = condition.value.substringBefore('(').lowercase()

private fun conditionsOf(selector: Selector): Pair<List<Condition>, Boolean> = when (selector) {
    is ElementSelector -> selector.conditions.orEmpty() to true
    is DescendantSelector -> combine(selector.ancestorSelector, selector.simpleSelector)
    is ChildSelector -> combine(selector.ancestorSelector, selector.simpleSelector)
    is DirectAdjacentSelector -> combine(selector.selector, selector.simpleSelector)
    is GeneralAdjacentSelector -> combine(selector.selector, selector.simpleSelector)
    else -> emptyList<Condition>() to false
}

private fun combine(left: Selector, right: Selector): Pair<List<Condition>, Boolean> {
    val (leftConditions, leftKnown) = conditionsOf(left)
    val (rightConditions, rightKnown) = conditionsOf(right)
    return (leftConditions + rightConditions) to (leftKnown && rightKnown)
}

private fun matchable(selector: Selector): Boolean {
    val (conditions, known) = conditionsOf(selector)
    return known && conditions.all { condition ->
        when (condition.conditionType) {
            Condition.ConditionType.PSEUDO_CLASS_CONDITION -> pseudoName(condition) in STRUCTURAL
            Condition.ConditionType.NOT_PSEUDO_CLASS_CONDITION -> (condition as NotPseudoClassCondition).selectors.all(::matchable)
            Condition.ConditionType.IS_PSEUDO_CLASS_CONDITION,
            Condition.ConditionType.WHERE_PSEUDO_CLASS_CONDITION,
            Condition.ConditionType.HAS_PSEUDO_CLASS_CONDITION,
            Condition.ConditionType.LANG_CONDITION,
            -> false
            else -> true
        }
    }
}

private fun elementsOf(selector: Selector): List<ElementSelector> = when (selector) {
    is ElementSelector -> listOf(selector)
    is DescendantSelector -> elementsOf(selector.ancestorSelector) + elementsOf(selector.simpleSelector)
    is ChildSelector -> elementsOf(selector.ancestorSelector) + elementsOf(selector.simpleSelector)
    is DirectAdjacentSelector -> elementsOf(selector.selector) + elementsOf(selector.simpleSelector)
    is GeneralAdjacentSelector -> elementsOf(selector.selector) + elementsOf(selector.simpleSelector)
    else -> emptyList()
}

private fun compareSpecificity(left: List<Int>, right: List<Int>): Int =
    left.zip(right).map { (one, two) -> one - two }.firstOrNull { it != 0 } ?: 0

/**
 * A selector's specificity as [ids, classes, types], as web's: attribute and pseudo-class
 * selectors count as classes, and `:not()` as its most specific argument.
 */
private fun specificityOf(selector: Selector): List<Int> {
    val total = mutableListOf(0, 0, 0)
    for (element in elementsOf(selector)) {
        if (element.localName != null && element.localName != "*") total[2]++
        for (condition in element.conditions.orEmpty()) {
            when (condition.conditionType) {
                Condition.ConditionType.ID_CONDITION -> total[0]++
                Condition.ConditionType.NOT_PSEUDO_CLASS_CONDITION -> {
                    val most = (condition as NotPseudoClassCondition).selectors.map(::specificityOf)
                        .fold(listOf(0, 0, 0)) { best, part -> if (compareSpecificity(part, best) > 0) part else best }
                    most.forEachIndexed { index, count -> total[index] += count }
                }
                else -> total[1]++
            }
        }
    }
    return total
}

private fun collectRules(rules: List<AbstractCSSRuleImpl>, applies: Boolean, sheets: Sheets) {
    for (rule in rules) {
        when (rule) {
            is CSSStyleRuleImpl -> {
                val declarations = declarationsOf(rule.style, sheets.colorSchemes)
                if (!applies) continue
                for (selector in rule.selectors) {
                    if (!matchable(selector)) continue
                    sheets.rules += Rule(selector.toString(), specificityOf(selector), sheets.rules.size, declarations)
                }
            }
            is CSSMediaRuleImpl -> {
                val list = rule.mediaList
                val queries = (0 until list.length).map { list.mediaQuery(it).toString() }
                sheets.media += queries
                collectRules(rule.cssRules.rules, applies && queries.any(::mediaApplies), sheets)
            }
            else -> Unit
        }
    }
}

// The candidate the cascade picks: important first, then inline, then specificity, then order.
private fun winner(candidates: List<Candidate>): CssValue? {
    var best: Candidate? = null
    for (candidate in candidates) {
        val current = best
        if (current == null) {
            best = candidate
            continue
        }
        val byImportance = candidate.declaration.important.compareTo(current.declaration.important)
        val byInline = candidate.inline.compareTo(current.inline)
        val bySpecificity = if (candidate.inline) 0 else compareSpecificity(candidate.specificity, current.specificity)
        val later = byImportance > 0 ||
            (byImportance == 0 && (byInline > 0 || (byInline == 0 && (bySpecificity > 0 || (bySpecificity == 0 && candidate.order >= current.order)))))
        if (later) best = candidate
    }
    return best?.declaration?.value
}

private fun textOriginal(text: TextValue): Rgba = when (text) {
    is TextValue.Author -> text.color
    TextValue.Link -> DEFAULT_LINK
    TextValue.Default -> DEFAULT_TEXT
}

private fun ownTextLength(element: Element): Int =
    element.childNodes().filterIsInstance<TextNode>().sumOf { visibleLength(it.wholeText) }

// A value's text with every color its role covers turned; null when nothing changes.
private fun turnedValue(first: LexicalUnit?, role: ColorRole, palette: DarkPalette): String? {
    var changed = false
    fun serialize(start: LexicalUnit?): String {
        val pieces = StringBuilder()
        for (unit in units(start)) {
            val color = unitColor(unit)
            val text = when {
                color != null -> {
                    val turned = darkColor(color, role, palette)
                    if (cssColor(turned) != cssColor(color)) {
                        changed = true
                        cssColor(turned)
                    } else {
                        unit.toString()
                    }
                }
                unit.lexicalUnitType in FUNCTION_UNITS -> "${unit.functionName}(${serialize(unit.parameters)})"
                else -> unit.toString()
            }
            val comma = unit.lexicalUnitType == LexicalUnitType.OPERATOR_COMMA
            if (pieces.isNotEmpty() && !comma) pieces.append(' ')
            pieces.append(text)
        }
        return pieces.toString()
    }
    val text = serialize(first)
    return if (changed) text else null
}

private fun turnDeclarations(style: CSSStyleDeclarationImpl, parser: CSSOMParser, palette: DarkPalette) {
    for (property: Property in style.properties) {
        val role = colorRole(property.name) ?: continue
        val text = property.value?.cssText ?: continue
        val turned = turnedValue(lexical(text), role, palette) ?: continue
        val value = try {
            parser.parsePropertyValue(turned)
        } catch (_: IOException) {
            null
        }
        if (value != null) property.value = value
    }
}

private fun turnRules(rules: List<AbstractCSSRuleImpl>, parser: CSSOMParser, palette: DarkPalette) {
    for (rule in rules) {
        when (rule) {
            is CSSStyleRuleImpl -> turnDeclarations(rule.style, parser, palette)
            is CSSMediaRuleImpl -> turnRules(rule.cssRules.rules, parser, palette)
            else -> Unit
        }
    }
}

private fun parseSheet(parser: CSSOMParser, text: String): CSSStyleSheetImpl? = try {
    parser.parseStyleSheet(InputSource(StringReader(text)), null)
} catch (_: IOException) {
    null
}

private fun parseStyle(parser: CSSOMParser, text: String): CSSStyleDeclarationImpl? = try {
    parser.parseStyleDeclaration(text)
} catch (_: IOException) {
    null
}

private fun matching(document: Element, selector: String): List<Element> = try {
    document.select(selector)
} catch (_: SelectorParseException) {
    emptyList()
}

/**
 * Sanitized message HTML with its colors turned for dark mode, or as it is when it is already
 * dark or ships its own dark design (see [hasOwnDarkDesign], [isDarkMail]). Matches web's
 * `darkenMessageHtml`; both run `shared/testdata/dark-mail-fixtures.json`.
 *
 * Every color the mail sets (`bgcolor` and `<font color>` attributes, inline styles, `<style>`
 * rules, `background` shorthands) is worked out per element as the cascade and inheritance give
 * it. Light backgrounds turn dark and dark text light, keeping hue ([darkBackground],
 * [lightText]), and text is then made to reach [MIN_CONTRAST] on the background actually behind
 * it ([readableText]); both are written on the element as `!important` inline styles. Colors the
 * mail sets that this can't place (borders, shadows, gradients, `:hover` rules) are turned by
 * their role alone. [colorSchemes] are the mail's `color-scheme` meta contents from
 * [cleanMessageHtml]; when [plain], the mail's backgrounds give way to the page, so text is
 * judged on the page.
 *
 * ```
 * val shown = darkenMessageHtml(clean.html, ThemePalette.darkPalette(themeId), clean.colorSchemes)
 * ```
 */
fun darkenMessageHtml(
    cleanHtml: String,
    palette: DarkPalette,
    colorSchemes: List<String> = emptyList(),
    plain: Boolean = false,
): String {
    val key = DarkenKey(cleanHtml, palette, colorSchemes.toList(), plain)
    synchronized(darkened) { darkened[key] }?.let { return it }
    val result = darken(cleanHtml, palette, colorSchemes, plain)
    synchronized(darkened) { darkened[key] = result }
    return result
}

private data class DarkenKey(val html: String, val palette: DarkPalette, val colorSchemes: List<String>, val plain: Boolean)

// Mail darkened lately, by HTML, palette and options: switching themes and opening threads again reuse it.
private val darkened = object : LinkedHashMap<DarkenKey, String>(DARKENED_KEPT, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DarkenKey, String>): Boolean = size > DARKENED_KEPT
}
private const val DARKENED_KEPT = 32
private const val LOAD_FACTOR = 0.75f

// Elements past which a message isn't resolved element by element: it shows as it is.
private const val MAX_DARKENED_ELEMENTS = 20000

private fun darken(cleanHtml: String, palette: DarkPalette, colorSchemes: List<String>, plain: Boolean): String {
    val document = Jsoup.parseBodyFragment(cleanHtml)
    document.outputSettings().prettyPrint(false)
    val body = document.body()
    val parser = cssomParser()
    val sheets = Sheets().apply { this.colorSchemes += colorSchemes }
    val styleSheets = body.getElementsByTag("style").mapNotNull { style ->
        parseSheet(parser, style.data())?.let { sheet ->
            collectRules(sheet.cssRules.rules, true, sheets)
            style to sheet
        }
    }
    // A style sheet's text isn't text the mail shows.
    val elements = body.allElements.filter { it.normalName() != "style" }
    if (elements.size > MAX_DARKENED_ELEMENTS) return cleanHtml
    val inlineStyles = mutableMapOf<Element, CSSStyleDeclarationImpl>()
    val candidates = elements.associateWith { mutableListOf<Candidate>() }
    // One query per rule, rather than every element against every rule.
    for (rule in sheets.rules) {
        for (element in matching(document, rule.selector)) {
            val list = candidates[element] ?: continue
            for (declaration in rule.declarations) list += Candidate(declaration, false, rule.specificity, rule.order)
        }
    }
    for ((element, list) in candidates) {
        val style = element.attr("style")
        if (style.isNotEmpty()) {
            parseStyle(parser, style)?.let { declarations ->
                inlineStyles[element] = declarations
                declarationsOf(declarations, sheets.colorSchemes).forEachIndexed { index, declaration ->
                    list += Candidate(declaration, true, listOf(0, 0, 0), index)
                }
            }
        }
    }
    if (hasOwnDarkDesign(sheets.media, sheets.colorSchemes)) return cleanHtml

    val page = Resolved(TextValue.Default, null, WHITE, palette.page)
    val resolved = mutableMapOf<Element, Resolved>()
    val samples = mutableListOf<TextSample>()
    val writes = mutableMapOf<Element, Write>()
    for (element in elements) {
        val parent = if (element == body) page else resolved[element.parent()] ?: page
        val list = candidates.getValue(element)
        val text = when (val value = winner(list.filter { it.declaration.property == Longhand.COLOR })) {
            is CssValue.Color -> TextValue.Author(value.color)
            CssValue.Initial -> TextValue.Default
            CssValue.Inherit, CssValue.Current -> parent.text
            null -> {
                val fontColor = if (element.normalName() == "font") attributeColor(element.attr("color")) else null
                when {
                    fontColor != null -> TextValue.Author(fontColor)
                    element.normalName() == "a" && element.hasAttr("href") -> TextValue.Link
                    else -> parent.text
                }
            }
        }
        val background = when (val value = winner(list.filter { it.declaration.property == Longhand.BACKGROUND_COLOR })) {
            is CssValue.Color -> value.color
            CssValue.Current -> textOriginal(text)
            CssValue.Inherit -> parent.ownBackground
            CssValue.Initial -> null
            null -> if (element.normalName() in BGCOLOR_TAGS) attributeColor(element.attr("bgcolor")) else null
        }
        val own = background?.takeIf { it.alpha > 0 }
        val behindOriginal = own?.let { composite(it, parent.behindOriginal) } ?: parent.behindOriginal
        val behindFinal = if (own != null && !plain) darkBackground(behindOriginal, palette) else parent.behindFinal
        resolved[element] = Resolved(text, own, behindOriginal, behindFinal)
        val weight = ownTextLength(element)
        var textFinal: Rgba? = null
        if (weight > 0) {
            samples += TextSample(behindOriginal, weight)
            val base = when (text) {
                TextValue.Default -> palette.text
                TextValue.Link -> palette.link
                is TextValue.Author -> lightText(composite(text.color, behindOriginal), palette)
            }
            textFinal = readableText(base, behindFinal)
        }
        val backgroundFinal = if (own != null && !plain) behindFinal else null
        if (backgroundFinal != null || textFinal != null) writes[element] = Write(backgroundFinal, textFinal)
    }
    if (isDarkMail(samples)) return cleanHtml

    for ((style, sheet) in styleSheets) {
        turnRules(sheet.cssRules.rules, parser, palette)
        style.empty()
        style.appendChild(DataNode(sheet.cssRules.rules.joinToString("\n") { it.cssText }))
    }
    for (element in elements) {
        val inline = inlineStyles[element]
        inline?.let { turnDeclarations(it, parser, palette) }
        val write = writes[element]
        val parts = mutableListOf(inline?.cssText.orEmpty())
        write?.background?.let { parts += "background-color:${cssColor(it)}!important" }
        write?.text?.let { parts += "color:${cssColor(it)}!important" }
        val style = parts.filter { it.isNotEmpty() }.joinToString(";")
        if (style.isNotEmpty()) element.attr("style", style)
    }
    return body.html()
}
