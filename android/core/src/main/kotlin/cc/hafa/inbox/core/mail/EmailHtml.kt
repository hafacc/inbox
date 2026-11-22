package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.theme.ColorMode
import cc.hafa.inbox.core.theme.ThemePalette
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** A message body made safe to show, and the remote images it asks for. */
data class CleanHtml(
    /** sanitized body HTML */
    val html: String,
    /** whether anything in it loads from the network */
    val hasRemoteImages: Boolean,
    /** distinct remote image URLs, as [remoteImageUrls] returns them */
    val remoteImageUrls: List<String> = emptyList(),
    /** contents of its `color-scheme` and `supported-color-schemes` meta tags, which cleaning drops */
    val colorSchemes: List<String> = emptyList(),
)

private val COLOR_SCHEME_METAS = setOf("color-scheme", "supported-color-schemes")
private val IMAGE_ATTRIBUTES = listOf("src", "srcset", "background", "poster")
private val REMOTE_URL_RE = Regex("""^\s*(https?:)?//""", RegexOption.IGNORE_CASE)
private val CID_RE = Regex("""^\s*cid:""", RegexOption.IGNORE_CASE)
private val HTTP_RE = Regex("""https?:""", RegexOption.IGNORE_CASE)

/** What an inline image or attachment that couldn't be fetched offline says, after its name and size. */
const val OPENS_ONLINE = "Opens when you're back online"

/** An unfetched part's name (when it has one), size and [OPENS_ONLINE], for its placeholder. */
fun missingPartLabel(filename: String, size: Long): String =
    listOf(filename, formatSize(size), OPENS_ONLINE).filter { it.isNotEmpty() }.joinToString(" · ")

// The box an unfetched inline image shows instead; colors follow the text so dark mail suits it.
private const val MISSING_IMAGE_STYLE =
    "display:inline-block;padding:6px 10px;border:1px dashed currentColor;border-radius:8px;font-size:12px;opacity:0.7"

/** Whether an image source loads from the network (rather than `data:`, `blob:` or `cid:`). */
fun isRemoteUrl(url: String): Boolean = REMOTE_URL_RE.containsMatchIn(url)

/** Remote `url(...)` references in inline CSS, which load images just like `<img>`. */
fun hasRemoteCssUrl(css: String): Boolean = CSS_URL_RE.findAll(css).any { isRemoteUrl(it.groupValues[2]) }

// Mail's layout vocabulary: tables, fonts, inline styles. No scripts, forms, frames, objects or <base>.
private val SAFELIST: Safelist = Safelist.relaxed()
    .addTags(
        "style", "font", "center", "hr", "abbr", "address", "bdi", "bdo", "del", "ins", "mark", "s",
        "small", "wbr", "article", "section", "header", "footer", "main", "nav", "aside", "figure",
        "figcaption", "picture", "time",
    )
    .addAttributes(
        ":all", "style", "class", "id", "dir", "lang", "title", "align", "valign", "width", "height",
        "bgcolor", "background", "border", "color",
    )
    .addAttributes("table", "cellpadding", "cellspacing", "frame", "rules")
    .addAttributes("td", "nowrap")
    .addAttributes("th", "nowrap")
    .addAttributes("font", "face", "size")
    .addAttributes("img", "srcset")
    .addAttributes("a", "name", "target", "rel")
    .addProtocols("img", "src", "http", "https", "data", "cid")
    .addProtocols("a", "href", "http", "https", "mailto", "tel")
    .removeProtocols("a", "href", "ftp")
    .preserveRelativeLinks(false)

/**
 * Sanitize message HTML with an allow-list: no scripts, forms, frames or event handlers; `cid:`
 * images swapped for the data URLs in [cidUrls], and an `<img>` whose part couldn't be fetched
 * (offline) swapped for a small box with its label from [cidMissing]; links opened without a
 * referrer and with their redirect wrappers taken off ([unwrapLink]). One pass also collects the
 * remote image URLs. A leading paragraph's top margin and a trailing one's bottom margin are dropped.
 */
fun cleanMessageHtml(html: String, cidUrls: Map<String, String>, cidMissing: Map<String, String> = emptyMap()): CleanHtml {
    val dirty = Jsoup.parseBodyFragment(html)
    val colorSchemes = dirty.getElementsByTag("meta")
        .filter { it.attr("name").trim().lowercase() in COLOR_SCHEME_METAS && it.hasAttr("content") }
        .map { it.attr("content") }
    // The safelist drops scheme-less URLs, so protocol-relative images would vanish rather than be blocked.
    for (element in dirty.body().allElements) {
        for (attribute in listOf("src", "background", "poster")) {
            val value = element.attr(attribute).trim()
            if (value.startsWith("//")) element.attr(attribute, "https:$value")
        }
    }
    val clean: Document = Cleaner(SAFELIST).clean(dirty)
    var hasRemoteImages = false
    val remoteUrls = LinkedHashSet<String>()
    for (element in clean.body().allElements) {
        val missing = cidOf(element.attr("src"))?.takeIf { cidUrls[it] == null }?.let { cidMissing[it] }
        if (element.normalName() == "img" && missing != null) {
            element.replaceWith(Element("span").attr("data-cid-missing", "").attr("style", MISSING_IMAGE_STYLE).text(missing))
            continue
        }
        for (attribute in IMAGE_ATTRIBUTES) {
            if (!element.hasAttr(attribute)) continue
            val value = element.attr(attribute)
            val contentId = cidOf(value)
            if (contentId != null) {
                val url = cidUrls[contentId]
                if (url != null) element.attr(attribute, url) else element.removeAttr(attribute)
            } else if (isRemoteUrl(value) || (attribute == "srcset" && HTTP_RE.containsMatchIn(value))) {
                hasRemoteImages = true
            }
        }
        if (element.hasAttr("style") && hasRemoteCssUrl(element.attr("style"))) hasRemoteImages = true
        if (element.normalName() == "style" && hasRemoteCssUrl(styleText(element))) hasRemoteImages = true
        if (element.normalName() == "a") {
            element.attr("rel", "noopener noreferrer")
            unwrapHref(element)
        }
        collectRemoteImageUrls(element, remoteUrls)
    }
    trimEdgeMargins(clean.body())
    // Pretty printing would add whitespace that shows in `white-space: pre` mail.
    clean.outputSettings().prettyPrint(false)
    return CleanHtml(
        clean.body().html(),
        hasRemoteImages,
        if (hasRemoteImages) remoteUrls.toList() else emptyList(),
        colorSchemes,
    )
}

// Wrappers looked through for a body's first or last paragraph; they have no margins of their own.
private val MARGINLESS_WRAPPERS = setOf("div", "center", "section", "article", "main", "header", "footer")
// Elements that show nothing, passed over when looking for the first or last paragraph.
private val UNSHOWN = setOf("style", "meta", "link", "title")

// The element at one edge of [parent] (its first or last shown child), or null when text or nothing is there.
private fun edgeElement(parent: Element, last: Boolean): Element? {
    val children = if (last) parent.childNodes().asReversed() else parent.childNodes()
    for (child in children) {
        if (child is TextNode) {
            if (!child.isBlank) return null
        } else if (child is Element && child.normalName() !in UNSHOWN) {
            return child
        }
    }
    return null
}

/**
 * Drop the top margin of a body's leading paragraph and the bottom margin of its trailing one
 * (looking through plain wrappers), so mail written as paragraphs (Thunderbird's) gets no extra
 * space above or below; `shared/testdata/sanitize-fixtures.json` pins the rule, as on web.
 */
private fun trimEdgeMargins(body: Element) {
    for ((last, property) in listOf(false to "margin-top", true to "margin-bottom")) {
        var element = edgeElement(body, last)
        while (element != null && element.normalName() in MARGINLESS_WRAPPERS) element = edgeElement(element, last)
        if (element?.normalName() == "p") {
            val style = element.attr("style").trim()
            val separator = if (style.isEmpty() || style.endsWith(";")) "" else ";"
            element.attr("style", "$style$separator$property:0")
        }
    }
}

// The content id a `cid:` URL names, without angle brackets; null for any other URL.
private fun cidOf(value: String): String? =
    if (CID_RE.containsMatchIn(value)) value.trim().drop("cid:".length).removePrefix("<").removeSuffix(">") else null

// Layout elements a fixed width can push past the view, as web's frame narrows them.
private val FITTED_TAGS = setOf("table", "td", "th", "div", "center")
private val WIDTH_ATTRIBUTE_RE = Regex("""^\s*(\d+(?:\.\d+)?)\s*(px)?\s*$""", RegexOption.IGNORE_CASE)
private val STYLE_WIDTH_RE = Regex("""(?:^|[;\s])(?:min-)?width\s*:\s*(\d+(?:\.\d+)?)px""", RegexOption.IGNORE_CASE)

// The widest fixed width, in CSS pixels, that [element]'s `width` attribute or inline style asks for; 0 for none.
private fun ownWidth(element: Element): Double {
    val attribute = WIDTH_ATTRIBUTE_RE.matchEntire(element.attr("width"))?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val styled = STYLE_WIDTH_RE.findAll(element.attr("style")).maxOfOrNull { it.groupValues[1].toDouble() } ?: 0.0
    return maxOf(attribute, styled)
}

// How wide [element] asks to be; a table is also as wide as the fixed cells of its first row together.
private fun declaredWidth(element: Element): Double {
    if (element.normalName() != "table") return ownWidth(element)
    val firstRow = element.selectFirst("> tr, > tbody > tr, > thead > tr")
    val cells = firstRow?.children()?.filter { it.normalName() == "td" || it.normalName() == "th" }.orEmpty()
    return maxOf(ownWidth(element), cells.sumOf(::ownWidth))
}

/**
 * Narrow fixed-width layouts (a 600px newsletter table on a phone) to a view [viewWidth] CSS pixels
 * wide, so the mail wraps instead of running past the view's edge, as web's frame does with the
 * widths it measures. The message view runs no script, so the widths are the ones the HTML states:
 * each table, cell, div or center whose `width` attribute or inline `width` / `min-width` is wider
 * than the view is set to fill it instead. Mail that already fits comes back unchanged.
 */
fun fitWideLayouts(cleanHtml: String, viewWidth: Int): String {
    if (viewWidth <= 0) return cleanHtml
    val document = Jsoup.parseBodyFragment(cleanHtml)
    val wide = document.body().allElements.filter { it.normalName() in FITTED_TAGS && declaredWidth(it) > viewWidth }
    if (wide.isEmpty()) return cleanHtml
    for (element in wide) {
        val fill = if (element.normalName() == "table") "100%" else "auto"
        val style = element.attr("style").trim()
        val separator = if (style.isEmpty() || style.endsWith(";")) "" else ";"
        element.attr("style", "$style${separator}width:$fill!important;min-width:0!important;max-width:100%!important")
    }
    document.outputSettings().prettyPrint(false)
    return document.body().html()
}

/** A plain-text body as HTML: escaped, with its line breaks kept but its trailing blank lines dropped, as on web. */
fun plainTextHtml(text: String): String =
    "<div style=\"white-space:pre-wrap\">${Entities.escape(text.trimEnd())}</div>"

/** Content-Security-Policy for a message view; remote images only once the reader allows them. */
fun messageCsp(showImages: Boolean): String {
    val images = if (showImages) "https: http: data: blob:" else "data: blob:"
    return listOf("default-src 'none'", "img-src $images", "style-src 'unsafe-inline'", "font-src data:")
        .joinToString("; ")
}

/**
 * The document a sanitized message body is shown in: CSP, base styles, and the colors from
 * [ThemePalette.emailStyle] for the theme shown, as on web. In dark mode the body should first go
 * through [darkenMessageHtml]. With [codeBackground], inline code the mail doesn't style itself is a
 * monospace chip on that color, as in the composer. Mail with a background of its own gets room above
 * and below inside its box and none at the sides, as web gives it on a phone.
 */
fun messageDocument(
    cleanHtml: String,
    showImages: Boolean,
    colors: String = ThemePalette.emailStyle(ThemePalette.defaultTheme, ColorMode.LIGHT),
    plain: Boolean = false,
    codeBackground: String? = null,
): String {
    // Blocked images keep their space but don't show a broken-image icon.
    val hideBlocked = if (showImages) "" else "img[src^=\"http\"],img[src^=\"//\"]{visibility:hidden}"
    // Plain mail has every background cleared, so its chips need theirs back.
    val code = codeBackground?.let {
        ":not(pre)>code{font-family:monospace;font-size:13px;padding:1px 4px;border-radius:4px;background:$it}" +
            "html[data-plain] :not(pre)>code{background-color:$it!important}"
    }.orEmpty()
    return "<!doctype html><html${if (plain) " data-plain" else ""}><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"${messageCsp(showImages)}\">" +
        "<style>$hideBlocked" +
        "html,body{margin:0;padding:0;" +
        "font:14px/22px sans-serif;overflow-wrap:anywhere}" +
        "body{padding:12px 0}" +
        "img{max-width:100%;height:auto}table{max-width:100%}" +
        "blockquote{margin:0 0 0 8px;padding-left:8px;border-left:2px solid}" +
        colors +
        "html[data-plain],html[data-plain] *{background-color:transparent!important}" +
        "html[data-plain] body{padding:2px 0}" +
        code +
        "</style></head><body>$cleanHtml</body></html>"
}

private val BACKGROUND_DECLARATION_RE = Regex("""background(?:-color|-image)?\s*:\s*([^;]+)""", RegexOption.IGNORE_CASE)
private val HEX_COLOR_RE = Regex("""^#([0-9a-f]{3}|[0-9a-f]{6})$""", RegexOption.IGNORE_CASE)
private val RGB_COLOR_RE = Regex("""^rgba?\(([^)]*)\)$""", RegexOption.IGNORE_CASE)
private val PLAIN_COLOR_WORDS = setOf("white", "transparent", "none", "inherit", "initial", "unset", "")
// Channels above this read as white.
private const val NEAR_WHITE = 235

// Whether a CSS background value is white, near white or nothing; anything unreadable counts as a color.
private fun plainBackground(value: String): Boolean {
    val color = value.trim().removeSuffix("!important").trim().lowercase()
    if (color.contains("url(") || color.contains("gradient(")) return false
    if (color in PLAIN_COLOR_WORDS) return true
    HEX_COLOR_RE.matchEntire(color)?.let { match ->
        val digits = match.groupValues[1].let { if (it.length == 3) it.flatMap { digit -> listOf(digit, digit) }.joinToString("") else it }
        return digits.chunked(2).all { it.toInt(16) > NEAR_WHITE }
    }
    RGB_COLOR_RE.matchEntire(color)?.let { match ->
        val parts = match.groupValues[1].split(',', ' ', '/').filter { it.isNotBlank() }.map { it.trim().toDoubleOrNull() ?: 0.0 }
        if (parts.size >= 4 && parts[3] == 0.0) return true
        return parts.take(3).all { it > NEAR_WHITE }
    }
    return false
}

/**
 * Whether sanitized message HTML draws a background of its own (a colored or pictured `bgcolor`,
 * `background` or CSS background), as a designed message does. Plain mail, whose backgrounds are
 * all white or none, is drawn straight on the page instead (see [messageDocument]'s `plain`).
 */
fun hasOwnBackground(cleanHtml: String): Boolean {
    val document = Jsoup.parseBodyFragment(cleanHtml)
    for (element in document.body().getAllElements()) {
        if (element.hasAttr("background")) return true
        if (element.hasAttr("bgcolor") && !plainBackground(element.attr("bgcolor"))) return true
        val style = element.attr("style")
        if (BACKGROUND_DECLARATION_RE.findAll(style).any { !plainBackground(it.groupValues[1]) }) return true
    }
    return document.getElementsByTag("style").any { sheet ->
        BACKGROUND_DECLARATION_RE.findAll(sheet.data()).any { !plainBackground(it.groupValues[1]) }
    }
}

private val SAFE_LINK_RE = Regex("""^(https?:|mailto:|tel:)""", RegexOption.IGNORE_CASE)

private fun unwrapHref(link: Element) {
    if (!link.hasAttr("href")) return
    val href = link.attr("href")
    val real = unwrapLink(href)
    if (real != href && SAFE_LINK_RE.containsMatchIn(real)) link.attr("href", real)
}
