package cc.hafa.inbox.core.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal val CSS_URL_RE = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""", RegexOption.IGNORE_CASE)
private val ABSOLUTE_HTTP_RE = Regex("""^https?://""", RegexOption.IGNORE_CASE)
private val SCHEME_RE = Regex("""^[a-z][a-z0-9+.-]*:""", RegexOption.IGNORE_CASE)
private val LOCAL_SCHEME_RE = Regex("""^(data|blob|cid):""", RegexOption.IGNORE_CASE)
private val WHITESPACE_RE = Regex("""\s+""")

/** What an image reference becomes: the URL it loads from, or a reason it never loads. */
private sealed class ImageRef {
    /** an absolute `http(s)` URL */
    class Remote(val url: String) : ImageRef()

    /** `data:`, `blob:`, `cid:` or a relative reference; left alone */
    object Local : ImageRef()

    /** another scheme; dropped */
    object Dropped : ImageRef()
}

private fun classify(raw: String): ImageRef {
    val value = raw.trim()
    return when {
        value.startsWith("//") -> ImageRef.Remote("https:$value")
        ABSOLUTE_HTTP_RE.containsMatchIn(value) -> ImageRef.Remote(value)
        LOCAL_SCHEME_RE.containsMatchIn(value) || !SCHEME_RE.containsMatchIn(value) -> ImageRef.Local
        else -> ImageRef.Dropped
    }
}

private fun srcsetUrls(srcset: String): List<String> =
    srcset.split(",").mapNotNull { it.trim().split(WHITESPACE_RE).firstOrNull()?.ifEmpty { null } }

/** The text of a `<style>` element, as its CSS. */
internal fun styleText(element: Element): String =
    element.childNodes().joinToString("") { (it as? DataNode)?.wholeData ?: it.outerHtml() }

private fun parse(html: String): Document = Jsoup.parseBodyFragment(html).also { it.outputSettings().prettyPrint(false) }

/** Add the remote image URLs [element] itself references to [urls], `//` sources as `https:`. */
internal fun collectRemoteImageUrls(element: Element, urls: MutableSet<String>) {
    fun visit(reference: String) {
        (classify(reference) as? ImageRef.Remote)?.let { urls += it.url }
    }
    if (element.normalName() == "img" && element.hasAttr("src")) visit(element.attr("src"))
    if (element.hasAttr("srcset")) srcsetUrls(element.attr("srcset")).forEach(::visit)
    if (element.hasAttr("background")) visit(element.attr("background"))
    if (element.hasAttr("style")) CSS_URL_RE.findAll(element.attr("style")).forEach { visit(it.groupValues[2]) }
    if (element.normalName() == "style") CSS_URL_RE.findAll(styleText(element)).forEach { visit(it.groupValues[2]) }
}

/** The distinct remote image URLs of a cleaned message; `//` sources as `https:`. */
fun remoteImageUrls(cleanHtml: String): List<String> {
    val urls = LinkedHashSet<String>()
    for (element in parse(cleanHtml).body().allElements) collectRemoteImageUrls(element, urls)
    return urls.toList()
}

/** The absolute URL for [reference], null when it must not load, or [reference] itself when it is local. */
private fun rewrite(reference: String): String? = when (val ref = classify(reference)) {
    is ImageRef.Remote -> ref.url
    ImageRef.Local -> reference
    ImageRef.Dropped -> null
}

private fun rewriteCss(css: String): String =
    CSS_URL_RE.replace(css) { match ->
        val url = rewrite(match.groupValues[2])
        if (url == null) "none" else "url('${url.replace("'", "%27")}')"
    }

/**
 * A cleaned message ready to load its images from the sender: `//` sources made `https:` (the view
 * has no base URL to resolve them against) and references of any other scheme taken out.
 */
fun absoluteImages(cleanHtml: String): String {
    val document = parse(cleanHtml)
    val removals = mutableListOf<Element>()
    for (element in document.body().allElements) {
        val isImage = element.normalName() == "img"
        if (element.hasAttr("srcset")) {
            val kept = element.attr("srcset").split(",").mapNotNull { entry ->
                val parts = entry.trim().split(WHITESPACE_RE)
                val url = parts.firstOrNull()?.ifEmpty { null }?.let { rewrite(it) }
                url?.let { (listOf(it) + parts.drop(1)).joinToString(" ") }
            }
            if (kept.isEmpty()) element.removeAttr("srcset") else element.attr("srcset", kept.joinToString(", "))
        }
        if (isImage && element.hasAttr("src")) {
            val url = rewrite(element.attr("src"))
            if (url != null) {
                element.attr("src", url)
            } else if (element.hasAttr("srcset")) {
                element.removeAttr("src")
            } else {
                removals.add(element)
            }
        }
        if (element.hasAttr("background")) {
            val url = rewrite(element.attr("background"))
            if (url == null) element.removeAttr("background") else element.attr("background", url)
        }
        if (element.hasAttr("style")) element.attr("style", rewriteCss(element.attr("style")))
        if (element.normalName() == "style") {
            val css = rewriteCss(styleText(element))
            element.empty()
            element.appendChild(DataNode(css))
        }
    }
    removals.forEach(Element::remove)
    return document.body().html()
}
