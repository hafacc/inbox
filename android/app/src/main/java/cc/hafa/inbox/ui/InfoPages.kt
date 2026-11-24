package cc.hafa.inbox.ui

// The privacy and support pages, read from the repo's docs/privacy.md and docs/support.md, which the
// build bundles as assets (the web prerenders the same files at /privacy and /support).

/** The web app's address, which a page's site links (`/privacy/`) are relative to. */
const val SITE_URL = "https://inbox.hafa.cc"

/** A page bundled with the app, and where its web copy lives. */
enum class InfoPage(
    /** its asset, copied from docs/ */
    val asset: String,
    /** its web copy */
    val url: String,
) {
    /** the privacy policy */
    PRIVACY("privacy.md", "$SITE_URL/privacy"),

    /** the support page */
    SUPPORT("support.md", "$SITE_URL/support"),
}

/** A run of a page's text: bold or not, and linked when [href] is set (a full address). */
data class InfoSpan(
    /** its text */
    val text: String,
    /** whether it is bold */
    val bold: Boolean = false,
    /** where it links, or null */
    val href: String? = null,
)

/** One block of a page. */
sealed interface InfoBlock {
    /** A heading; [level] 1 is the page's title, 2 a section's. */
    data class Heading(
        /** how deep, from 1 */
        val level: Int,
        /** its text */
        val spans: List<InfoSpan>,
    ) : InfoBlock

    /** A paragraph. */
    data class Paragraph(
        /** its text */
        val spans: List<InfoSpan>,
    ) : InfoBlock

    /** A bulleted list. */
    data class Bullets(
        /** each item's text */
        val items: List<List<InfoSpan>>,
    ) : InfoBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^[-*]\s+(.*)$""")

// Bold, a `[text](href)` link, and the bare web and email addresses GitHub-flavored Markdown links.
private val INLINE = Regex(
    """\*\*(.+?)\*\*""" +
        """|\[([^\]]+)]\(([^)\s]+)\)""" +
        """|(https?://[^\s<]*[^\s<.,:;"')\]])""" +
        """|([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)""",
)
private val SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""")

/**
 * The blocks of [markdown], as much of Markdown as the pages use: `#` headings, paragraphs (lines
 * joined), `-` lists whose items run on over the lines after them, `**bold**`, `[text](href)` links
 * and bare web and email addresses, which link as on the web.
 */
fun parseInfoPage(markdown: String): List<InfoBlock> {
    val blocks = mutableListOf<InfoBlock>()
    val paragraph = mutableListOf<String>()
    val items = mutableListOf<MutableList<String>>()
    fun flush() {
        if (paragraph.isNotEmpty()) blocks += InfoBlock.Paragraph(infoSpans(paragraph.joinToString(" ")))
        if (items.isNotEmpty()) blocks += InfoBlock.Bullets(items.map { infoSpans(it.joinToString(" ")) })
        paragraph.clear()
        items.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        val heading = HEADING.find(line)
        // An indented line runs on the item above it.
        val bullet = if (raw.firstOrNull()?.isWhitespace() == false) BULLET.find(line) else null
        when {
            line.isEmpty() -> flush()
            heading != null -> {
                flush()
                blocks += InfoBlock.Heading(heading.groupValues[1].length, infoSpans(heading.groupValues[2]))
            }
            bullet != null -> {
                if (paragraph.isNotEmpty()) flush()
                items += mutableListOf(bullet.groupValues[1])
            }
            items.isNotEmpty() -> items.last() += line
            else -> paragraph += line
        }
    }
    flush()
    return blocks
}

/** [text]'s runs: bold, links (a site path made a full address on [SITE_URL]) and plain text. */
fun infoSpans(text: String, bold: Boolean = false): List<InfoSpan> {
    val spans = mutableListOf<InfoSpan>()
    var end = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > end) spans += InfoSpan(text.substring(end, match.range.first), bold)
        val (strong, linkText, href, web, email) = match.destructured
        spans += when {
            strong.isNotEmpty() -> infoSpans(strong, bold = true)
            linkText.isNotEmpty() -> listOf(InfoSpan(linkText, bold, infoUrl(href)))
            web.isNotEmpty() -> listOf(InfoSpan(web, bold, web))
            else -> listOf(InfoSpan(email, bold, "mailto:$email"))
        }
        end = match.range.last + 1
    }
    if (end < text.length) spans += InfoSpan(text.substring(end), bold)
    return spans
}

/** [href] as a full address: a site path on [SITE_URL], any address with a scheme as it is. */
fun infoUrl(href: String): String = if (SCHEME.containsMatchIn(href)) href else SITE_URL + href

/** The bundled page [url] names: the site's own `/privacy` or `/support`, as a full address or a site path, a trailing slash or not. */
fun infoPageAt(url: String): InfoPage? {
    val address = infoUrl(url).trimEnd('/')
    return InfoPage.entries.firstOrNull { it.url == address }
}
