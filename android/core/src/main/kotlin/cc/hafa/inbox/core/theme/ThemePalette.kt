package cc.hafa.inbox.core.theme

import cc.hafa.inbox.core.mail.DarkPalette
import cc.hafa.inbox.core.mail.Rgba
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/** Light or dark. */
enum class ColorMode {
    /** light colors */
    LIGHT,

    /** dark colors */
    DARK,
}

/** A color theme: its stored id (`users/{uid}.settings.theme`) and the name shown. */
data class ThemeInfo(
    /** stored id */
    val id: String,
    /** name shown */
    val name: String,
)

/**
 * The color themes in `shared/themes/themes.json`, the same file web reads, so both apps draw the
 * same colors. A theme's colors in a mode are the neutral colors overlaid by the theme's own, plus
 * `avatar-<n>`/`on-avatar-<n>` and `label-<tone>`.
 */
object ThemePalette {
    private val root: JsonObject by lazy {
        val text = requireNotNull(ThemePalette::class.java.classLoader?.getResourceAsStream("themes.json")) {
            "themes.json missing from resources"
        }.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private val themeObjects: List<JsonObject> by lazy { root.getValue("themes").jsonArray.map { it.jsonObject } }

    /** The theme used until one is chosen. */
    val defaultTheme: String by lazy { root.getValue("default").jsonPrimitive.content }

    /** Every theme, in the order the picker shows them. */
    val themes: List<ThemeInfo> by lazy {
        themeObjects.map { ThemeInfo(it.getValue("id").jsonPrimitive.content, it.getValue("name").jsonPrimitive.content) }
    }

    /** How many avatar color pairs a theme has. */
    val avatarCount: Int by lazy { root.getValue("avatars").jsonObject.getValue("light").jsonArray.size }

    private val toneOrder: List<String> by lazy {
        root.getValue("labelTones").jsonObject.getValue("order").jsonArray.map { it.jsonPrimitive.content }
    }

    /** A stored theme id, or the default when it is missing or unknown. */
    fun themeIdFrom(value: Any?): String = if (themes.any { it.id == value }) value as String else defaultTheme

    /** Every color of a theme in a mode as ARGB, by token name (`bg`, `accent`, `avatar-0`, `label-red`, …). */
    fun colors(id: String, mode: ColorMode): Map<String, Long> {
        val key = if (mode == ColorMode.DARK) "dark" else "light"
        val theme = themeObjects.firstOrNull { it.getValue("id").jsonPrimitive.content == id }
            ?: themeObjects.first { it.getValue("id").jsonPrimitive.content == defaultTheme }
        val out = linkedMapOf<String, Long>()
        root.getValue("neutral").jsonObject.getValue(key).jsonObject.forEach { (name, value) -> out[name] = argb(value.jsonPrimitive.content) }
        theme.getValue(key).jsonObject.forEach { (name, value) -> out[name] = argb(value.jsonPrimitive.content) }
        root.getValue("avatars").jsonObject.getValue(key).jsonArray.forEachIndexed { index, pair ->
            val (background, foreground) = (pair as JsonArray).map { it.jsonPrimitive.content }
            out["avatar-$index"] = argb(background)
            out["on-avatar-$index"] = argb(foreground)
        }
        val tones = root.getValue("labelTones").jsonObject.getValue(key).jsonObject
        for (tone in toneOrder) out["label-$tone"] = argb(tones.getValue(tone).jsonPrimitive.content)
        return out
    }

    /**
     * The colors of a message body as CSS for its document, as web's `emailStyle`: the page, and
     * text, link and quote colors for whatever the mail leaves unset. Light draws the mail as sent
     * on white. Dark goes with mail whose own colors [cc.hafa.inbox.core.mail.darkenMessageHtml] has
     * turned dark, and dims pictures a little.
     */
    fun emailStyle(id: String, mode: ColorMode): String {
        val key = if (mode == ColorMode.DARK) "dark" else "light"
        val email = root.getValue("email").jsonObject
        val colors = email.getValue(key).jsonObject
        val base = "html{background:${text(colors, "background")}}body{color:${text(colors, "text")}}" +
            "a{color:${emailLink(id, key)}}blockquote{border-left-color:${text(colors, "quote")}}"
        return if (mode == ColorMode.LIGHT) base else "${base}img,video{filter:${text(email, "dim")}}"
    }

    /** The colors dark mail is turned toward for a theme, as web's `darkPalette`: its page, unset text and unset links. */
    fun darkPalette(id: String): DarkPalette {
        val dark = root.getValue("email").jsonObject.getValue("dark").jsonObject
        return DarkPalette(
            page = Rgba.hex(text(dark, "background")),
            text = Rgba.hex(text(dark, "text")),
            link = Rgba.hex(emailLink(id, "dark")),
        )
    }

    private fun text(obj: JsonObject, name: String): String = obj.getValue(name).jsonPrimitive.content

    private fun emailLink(id: String, key: String): String {
        val theme = themeObjects.firstOrNull { it.getValue("id").jsonPrimitive.content == id }
            ?: themeObjects.first { it.getValue("id").jsonPrimitive.content == defaultTheme }
        return text(theme.getValue("emailLink").jsonObject, key)
    }

    /** The colors as `#rrggbb` strings, for comparing with web's fixtures. */
    fun hexColors(id: String, mode: ColorMode): Map<String, String> =
        colors(id, mode).mapValues { (_, value) -> "#%06x".format(value and 0xFFFFFF) }

    private fun argb(hex: String): Long = 0xFF000000 or hex.removePrefix("#").toLong(HEX_RADIX)
}

private const val HEX_RADIX = 16
private const val GRAY_SATURATION = 0.15
private val HEX_RE = Regex("^#?([0-9a-fA-F]{6})$")
private val TONE_LIMITS = listOf(
    15.0 to "red",
    40.0 to "orange",
    70.0 to "yellow",
    160.0 to "green",
    195.0 to "teal",
    255.0 to "blue",
    290.0 to "purple",
    345.0 to "pink",
)

/** The theme tone a Gmail label color folds into: by hue, or gray when it is nearly gray. Matches web's `labelTone`. */
fun labelTone(hex: String?): String {
    val digits = hex?.trim()?.let { HEX_RE.find(it)?.groupValues?.get(1) } ?: return "gray"
    val value = digits.toLong(HEX_RADIX)
    val red = ((value shr 16) and 255) / 255.0
    val green = ((value shr 8) and 255) / 255.0
    val blue = (value and 255) / 255.0
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val lightness = (max + min) / 2
    val chroma = max - min
    val saturation = if (chroma == 0.0) 0.0 else chroma / (1 - abs(2 * lightness - 1))
    if (saturation < GRAY_SATURATION) return "gray"
    val sector = when (max) {
        red -> ((green - blue) / chroma) % 6
        green -> (blue - red) / chroma + 2
        else -> (red - green) / chroma + 4
    }
    val hue = (sector * 60 + 360) % 360
    return TONE_LIMITS.firstOrNull { (limit, _) -> hue < limit }?.second ?: "red"
}

/** Which avatar color pair an address gets: stable, the same on every device. Matches web's `avatarIndex`. */
fun avatarIndex(email: String, count: Int = ThemePalette.avatarCount): Int {
    var hash = 0L
    email.lowercase().codePoints().forEach { codePoint ->
        hash = (hash * 31 + codePoint) and 0xFFFFFFFFL
    }
    return (hash % count).toInt()
}
