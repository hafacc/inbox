package cc.hafa.inbox.core.mail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.pow

/** A color: sRGB channels 0–255 and alpha 0–1. Matches web's `Rgba`. */
data class Rgba(
    /** red channel, 0–255 */
    val red: Int,
    /** green channel, 0–255 */
    val green: Int,
    /** blue channel, 0–255 */
    val blue: Int,
    /** opacity, 0–1 */
    val alpha: Double = 1.0,
) {
    /** Colors from their written forms. */
    companion object {
        /** An opaque color from `#rrggbb`. */
        fun hex(text: String): Rgba = requireNotNull(hexColor(text.removePrefix("#"))) { "not a color: $text" }
    }
}

/** A number in a CSS color function, as the CSS parser read it. Matches web's `ColorArgument`. */
data class ColorArgument(
    /** the number */
    val value: Double,
    /** its unit: none, `%` or an angle unit (`deg`, `turn`, `rad`, `grad`) */
    val unit: String = "",
)

/** The colors dark mail is drawn toward: the page behind it, and text and links it leaves unset. */
data class DarkPalette(
    /** what the mail sits on */
    val page: Rgba,
    /** text color for text the mail leaves unset; also the lightest text the mail's own turns into */
    val text: Rgba,
    /** link color for links the mail leaves unset */
    val link: Rgba,
)

/** Which way a CSS property's colors are turned in dark mail. */
enum class ColorRole {
    /** lightened when dark */
    TEXT,

    /** darkened when light */
    BACKGROUND,
}

/** One stretch of text in mail, for [isDarkMail]: the background behind it and how many characters it has. */
data class TextSample(
    /** the opaque background behind it, as the mail draws it */
    val background: Rgba,
    /** visible characters */
    val weight: Int,
)

/** Lowest contrast text may have against what is behind it (WCAG AA for body text). */
const val MIN_CONTRAST = 4.5

// Luminance below which white text reads better than black: a dark color.
private const val DARK_LUMINANCE = 0.179

// OKLab lightness of a gray at DARK_LUMINANCE; light colors flip to at most this, dark at least.
private val MID_LIGHTNESS = cbrt(DARK_LUMINANCE)

// Steps of halving when searching for a lightness or chroma.
private const val SEARCH_STEPS = 24

// Gamut slack for float error.
private const val GAMUT_EPSILON = 1e-6

private const val CHANNEL_MAX = 255.0
private const val HEX_RADIX = 16
private const val ALPHA_ROUNDING = 1000.0

private val TEXT_PROPERTIES = setOf(
    "color", "caret-color", "text-decoration", "text-decoration-color", "text-emphasis", "text-emphasis-color",
    "-webkit-text-fill-color", "-webkit-text-stroke", "-webkit-text-stroke-color",
)
private val BACKGROUND_PROPERTIES = setOf(
    "background", "background-color", "background-image", "box-shadow", "text-shadow", "column-rule", "column-rule-color",
)

// Whitespace in HTML text, plus the no-break space mail pads with.
private val BLANK = setOf(' ', '\t', '\n', '\r', '\u000c', ' ')
private val HEX_DIGITS_RE = Regex("^[0-9a-fA-F]+$")
private val SCHEME_SEPARATOR_RE = Regex("""[\s,]+""")
private val WHITESPACE_RE = Regex("""\s+""")

private val NAMED: Map<String, Rgba> by lazy {
    val text = requireNotNull(Rgba::class.java.classLoader?.getResourceAsStream("css-colors.json")) {
        "css-colors.json missing from resources"
    }.bufferedReader().use { it.readText() }
    Json.parseToJsonElement(text).jsonObject
        .filterValues { it is JsonArray }
        .mapValues { (_, value) ->
            val (red, green, blue) = (value as JsonArray).map { it.jsonPrimitive.int }
            Rgba(red, green, blue)
        }
}

/** A CSS named color or `transparent`, or null for any other word. Matches web's `namedColor`. */
fun namedColor(name: String): Rgba? {
    val key = name.lowercase()
    return if (key == "transparent") Rgba(0, 0, 0, 0.0) else NAMED[key]
}

/** A hex color from its digits (3, 4, 6 or 8 of them, no `#`), or null. Matches web's `hexColor`. */
fun hexColor(digits: String): Rgba? {
    if (!HEX_DIGITS_RE.matches(digits)) return null
    val full = if (digits.length == 3 || digits.length == 4) digits.map { "$it$it" }.joinToString("") else digits
    if (full.length != 6 && full.length != 8) return null
    fun channel(index: Int): Int = full.substring(index * 2, index * 2 + 2).toInt(HEX_RADIX)
    return Rgba(channel(0), channel(1), channel(2), if (full.length == 8) channel(3) / CHANNEL_MAX else 1.0)
}

private fun clamp(value: Double, low: Double, high: Double): Double = minOf(high, maxOf(low, value))

private fun alphaFrom(argument: ColorArgument?): Double? = when {
    argument == null -> 1.0
    argument.unit == "%" -> clamp(argument.value / 100, 0.0, 1.0)
    argument.unit == "" -> clamp(argument.value, 0.0, 1.0)
    else -> null
}

private fun degreesFrom(argument: ColorArgument): Double? = when (argument.unit) {
    "", "deg" -> argument.value
    "turn" -> argument.value * 360
    "rad" -> argument.value * 180 / PI
    "grad" -> argument.value * 0.9
    else -> null
}

private fun hslChannels(hue: Double, saturation: Double, lightness: Double): List<Int> {
    val amount = saturation * minOf(lightness, 1 - lightness)
    fun part(offset: Int): Double {
        val position = (((offset + hue / 30) % 12) + 12) % 12
        return lightness - amount * maxOf(-1.0, minOf(position - 3, 9 - position, 1.0))
    }
    return listOf(part(0), part(8), part(4)).map { jsRound(clamp(it * CHANNEL_MAX, 0.0, CHANNEL_MAX)) }
}

// JavaScript's Math.round: halves go up, so both platforms round alike.
private fun jsRound(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()

/**
 * A color from a CSS color function (`rgb`, `rgba`, `hsl`, `hsla`) and its numbers in order, or
 * null for other functions and arguments that aren't valid. Matches web's `functionColor`.
 */
fun functionColor(name: String, args: List<ColorArgument>): Rgba? {
    val kind = name.lowercase()
    if (args.size != 3 && args.size != 4) return null
    val alpha = alphaFrom(args.getOrNull(3)) ?: return null
    val (first, second, third) = args
    return when (kind) {
        "rgb", "rgba" -> {
            val channels = listOf(first, second, third).map {
                when (it.unit) {
                    "%" -> clamp(it.value * 2.55, 0.0, CHANNEL_MAX)
                    "" -> clamp(it.value, 0.0, CHANNEL_MAX)
                    else -> null
                }
            }
            if (channels.any { it == null }) null else channels.map { jsRound(it!!) }.let { (red, green, blue) -> Rgba(red, green, blue, alpha) }
        }
        "hsl", "hsla" -> {
            val hue = degreesFrom(first)
            if (hue == null || second.unit != "%" || third.unit != "%") {
                null
            } else {
                val (red, green, blue) = hslChannels(hue, clamp(second.value / 100, 0.0, 1.0), clamp(third.value / 100, 0.0, 1.0))
                Rgba(red, green, blue, alpha)
            }
        }
        else -> null
    }
}

/**
 * An HTML color attribute (`bgcolor`, `<font color>`): a named color, or 6 hex digits with or
 * without `#`, or 3 with it. Null for anything else. Matches web's `attributeColor`.
 */
fun attributeColor(value: String): Rgba? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.lowercase() == "transparent") return null
    namedColor(trimmed)?.let { return it }
    val hashed = trimmed.startsWith("#")
    val digits = if (hashed) trimmed.drop(1) else trimmed
    return if (digits.length == 6 || (hashed && digits.length == 3)) hexColor(digits) else null
}

private fun hex2(value: Int): String = value.coerceIn(0, 255).toString(HEX_RADIX).padStart(2, '0')

/** A color as CSS: `#rrggbb` when opaque, else `rgba(…)`. Matches web's `cssColor`. */
fun cssColor(color: Rgba): String {
    if (color.alpha >= 1) return "#${hex2(color.red)}${hex2(color.green)}${hex2(color.blue)}"
    val alpha = jsRound(color.alpha * ALPHA_ROUNDING) / ALPHA_ROUNDING
    val written = if (alpha == kotlin.math.floor(alpha)) alpha.toInt().toString() else alpha.toString()
    return "rgba(${color.red}, ${color.green}, ${color.blue}, $written)"
}

/** A color drawn over an opaque one: the opaque color that shows. */
fun composite(top: Rgba, bottom: Rgba): Rgba {
    if (top.alpha >= 1) return top
    fun mix(over: Int, under: Int): Int = jsRound(over * top.alpha + under * (1 - top.alpha))
    return Rgba(mix(top.red, bottom.red), mix(top.green, bottom.green), mix(top.blue, bottom.blue))
}

private fun linear(channel: Int): Double {
    val value = channel / CHANNEL_MAX
    return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

private fun encoded(value: Double): Double = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1 / 2.4) - 0.055

/** WCAG relative luminance. */
fun relativeLuminance(color: Rgba): Double =
    0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

/** WCAG contrast ratio between two opaque colors. */
fun contrast(first: Rgba, second: Rgba): Double {
    val one = relativeLuminance(first)
    val two = relativeLuminance(second)
    return (maxOf(one, two) + 0.05) / (minOf(one, two) + 0.05)
}

private fun isDark(color: Rgba): Boolean = relativeLuminance(color) < DARK_LUMINANCE

private fun toOklab(color: Rgba): DoubleArray {
    val red = linear(color.red)
    val green = linear(color.green)
    val blue = linear(color.blue)
    val long = cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
    val medium = cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
    val short = cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
    return doubleArrayOf(
        0.2104542553 * long + 0.793617785 * medium - 0.0040720468 * short,
        1.9779984951 * long - 2.428592205 * medium + 0.4505937099 * short,
        0.0259040371 * long + 0.7827717662 * medium - 0.808675766 * short,
    )
}

private fun linearFromOklab(lightness: Double, greenRed: Double, blueYellow: Double): DoubleArray {
    val long = (lightness + 0.3963377774 * greenRed + 0.2158037573 * blueYellow).pow(3)
    val medium = (lightness - 0.1055613458 * greenRed - 0.0638541728 * blueYellow).pow(3)
    val short = (lightness - 0.0894841775 * greenRed - 1.291485548 * blueYellow).pow(3)
    return doubleArrayOf(
        4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short,
        -1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short,
        -0.0041960863 * long - 0.7034186147 * medium + 1.707614701 * short,
    )
}

private fun inGamut(channels: DoubleArray): Boolean = channels.all { it >= -GAMUT_EPSILON && it <= 1 + GAMUT_EPSILON }

// The sRGB color at an OKLab lightness and hue, with as much of the chroma as fits.
private fun fit(lightness: Double, greenRed: Double, blueYellow: Double): Rgba {
    var scale = 1.0
    if (!inGamut(linearFromOklab(lightness, greenRed, blueYellow))) {
        var low = 0.0
        var high = 1.0
        repeat(SEARCH_STEPS) {
            val middle = (low + high) / 2
            if (inGamut(linearFromOklab(lightness, greenRed * middle, blueYellow * middle))) low = middle else high = middle
        }
        scale = low
    }
    val (red, green, blue) = linearFromOklab(lightness, greenRed * scale, blueYellow * scale)
        .map { jsRound(encoded(clamp(it, 0.0, 1.0)) * CHANNEL_MAX) }
    return Rgba(red, green, blue)
}

/**
 * A background color for dark mail: a light one flipped to dark, keeping its hue (white becomes
 * the palette's page color); a dark one as it is. Matches web's `darkBackground`.
 */
fun darkBackground(color: Rgba, palette: DarkPalette): Rgba {
    if (color.alpha == 0.0 || isDark(color)) return color
    val (lightness, greenRed, blueYellow) = toOklab(color)
    val floor = toOklab(palette.page)[0]
    val flipped = minOf(lightness, floor + (1 - lightness) * (MID_LIGHTNESS - floor) / (1 - MID_LIGHTNESS))
    val scale = if (lightness > 0) flipped / lightness else 0.0
    return fit(flipped, greenRed * scale, blueYellow * scale).copy(alpha = color.alpha)
}

/**
 * A text color for dark mail: a dark one flipped to light, keeping its hue (black becomes as light
 * as the palette's text); a light one as it is. Matches web's `lightText`.
 */
fun lightText(color: Rgba, palette: DarkPalette): Rgba {
    if (color.alpha == 0.0 || !isDark(color)) return color
    val (lightness, greenRed, blueYellow) = toOklab(color)
    val ceiling = toOklab(palette.text)[0]
    val flipped = maxOf(lightness, ceiling - lightness * (ceiling - MID_LIGHTNESS) / MID_LIGHTNESS)
    return fit(flipped, greenRed, blueYellow).copy(alpha = color.alpha)
}

/**
 * Opaque text made readable on an opaque background: as it is when it has [MIN_CONTRAST], else
 * the least lightness change, keeping its hue, that reaches it. Matches web's `readableText`.
 */
fun readableText(text: Rgba, background: Rgba): Rgba {
    if (contrast(text, background) >= MIN_CONTRAST) return text
    val (lightness, greenRed, blueYellow) = toOklab(text)
    var near = lightness
    var far = if (isDark(background)) 1.0 else 0.0
    repeat(SEARCH_STEPS) {
        val middle = (near + far) / 2
        if (contrast(fit(middle, greenRed, blueYellow), background) >= MIN_CONTRAST) far = middle else near = middle
    }
    return fit(far, greenRed, blueYellow)
}

/** Whether a CSS property's colors are text or background, or neither. Matches web's `colorRole`. */
fun colorRole(property: String): ColorRole? {
    val name = property.lowercase()
    return when {
        name in TEXT_PROPERTIES -> ColorRole.TEXT
        name in BACKGROUND_PROPERTIES || name.startsWith("border") || name.startsWith("outline") -> ColorRole.BACKGROUND
        else -> null
    }
}

/** A color turned for dark mail by its role, without looking at what is behind it. Matches web's `darkColor`. */
fun darkColor(color: Rgba, role: ColorRole, palette: DarkPalette): Rgba =
    if (role == ColorRole.TEXT) lightText(color, palette) else darkBackground(color, palette)

private fun squashed(text: String): String = text.lowercase().replace(WHITESPACE_RE, "")

/**
 * Whether a media query counts when working out mail's colors: screen and size queries do; print,
 * speech, negated and color-scheme queries don't. Matches web's `mediaApplies`.
 */
fun mediaApplies(query: String): Boolean {
    val words = query.trim().lowercase()
    if (words.startsWith("not ") || words.startsWith("not(")) return false
    val flat = squashed(words)
    return !(flat.contains("print") || flat.contains("speech") || flat.contains("prefers-color-scheme"))
}

/**
 * Whether mail ships its own dark design: one of its `@media` queries ([media]) asks for
 * `(prefers-color-scheme: dark)`, or one of its color schemes ([colorSchemes]) names `dark`.
 * Matches web's `hasOwnDarkDesign`.
 */
fun hasOwnDarkDesign(media: List<String>, colorSchemes: List<String>): Boolean =
    colorSchemes.any { "dark" in it.lowercase().split(SCHEME_SEPARATOR_RE) } ||
        media.any { "(prefers-color-scheme:dark)" in squashed(it) }

/** Whether mail is already dark: more of its text sits on dark backgrounds than light. Matches web's `isDarkMail`. */
fun isDarkMail(samples: List<TextSample>): Boolean {
    var dark = 0
    var light = 0
    for (sample in samples) if (isDark(sample.background)) dark += sample.weight else light += sample.weight
    return dark > light
}

/** How many characters of a text node show: all but whitespace. Matches web's `visibleLength`. */
fun visibleLength(text: String): Int = text.codePoints().filter { it > Char.MAX_VALUE.code || it.toChar() !in BLANK }.count().toInt()
