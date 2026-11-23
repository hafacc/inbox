package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.mail.safeHref

// Adding, editing and removing a link in one line of the composer's runs, as web's Tiptap commands
// do (web/src/lib/compose/rich-doc.ts); both run the `links` cases of shared/testdata/rich-fixtures.json.

/** A link in a line: from [start] up to (not including) [end] of its text, its [text] and [href]. */
data class RunLink(
    /** first index */
    val start: Int,
    /** index after the last */
    val end: Int,
    /** its words */
    val text: String,
    /** where it leads */
    val href: String,
)

/** What the link dialog is for. */
enum class LinkMode(
    /** the name web and the fixtures use */
    val wire: String,
) {
    /** an existing link */
    EDIT("edit"),

    /** the selected text, to make a link */
    SELECTION("selection"),

    /** a new link at the caret */
    INSERT("insert"),
}

/** What the link dialog is for, where in the line, and the text and address it starts with. */
data class LinkRequest(
    /** what it is for */
    val mode: LinkMode,
    /** first index */
    val start: Int,
    /** index after the last */
    val end: Int,
    /** the words it starts with */
    val text: String,
    /** the address it starts with; empty for a new link */
    val href: String,
)

/** The links of [runs], in order: neighboring runs with the same address are one link. */
fun runLinks(runs: List<RichRun>): List<RunLink> {
    val links = mutableListOf<RunLink>()
    var offset = 0
    for (run in runs) {
        val end = offset + run.text.length
        val last = links.lastOrNull()
        if (run.href != null && last != null && last.end == offset && last.href == run.href) {
            links[links.lastIndex] = last.copy(end = end, text = last.text + run.text)
        } else if (run.href != null) {
            links += RunLink(offset, end, run.text, run.href)
        }
        offset = end
    }
    return links
}

/** The link of [runs] around [offset], from just before its first letter to just after its last; null if none. */
fun runLinkAround(runs: List<RichRun>, offset: Int): RunLink? {
    val links = runLinks(runs)
    return links.firstOrNull { offset >= it.start && offset < it.end } ?: links.firstOrNull { offset == it.end }
}

/** What the link button opens the dialog for, with [start]–[end] selected in the line of [runs]. */
fun linkRequest(runs: List<RichRun>, start: Int, end: Int): LinkRequest {
    val link = runLinkAround(runs, start)
    val text = runs.joinToString("") { it.text }
    return when {
        link != null -> LinkRequest(LinkMode.EDIT, link.start, link.end, link.text, link.href)
        end > start -> LinkRequest(LinkMode.SELECTION, start, end, text.substring(start, end), "")
        else -> LinkRequest(LinkMode.INSERT, start, start, "", "")
    }
}

// [runs] cut at [at] of their text, so a run starts there.
private fun splitAt(runs: List<RichRun>, at: Int): List<RichRun> {
    val out = mutableListOf<RichRun>()
    var offset = 0
    for (run in runs) {
        val end = offset + run.text.length
        if (at in (offset + 1) until end) {
            out += run.copy(text = run.text.substring(0, at - offset))
            out += run.copy(text = run.text.substring(at - offset))
        } else {
            out += run
        }
        offset = end
    }
    return out
}

// [runs] with each in [start]–[end] changed by [change], and those before and after kept.
private fun mapRange(runs: List<RichRun>, start: Int, end: Int, change: (RichRun) -> RichRun): List<RichRun> {
    var offset = 0
    return splitAt(splitAt(runs, start), end).map { run ->
        val at = offset
        offset += run.text.length
        if (at >= start && at < end) change(run) else run
    }
}

/**
 * The line of [runs] once the link dialog for [request] is saved with [text] and [typedHref]: the
 * selected text linked, a new link put in at the caret, or the edited link's words replaced (with
 * the styles its first letter had). A link to anything but an http, https or mailto address isn't
 * saved: [runs] come back as they were.
 */
fun saveLink(runs: List<RichRun>, request: LinkRequest, text: String, typedHref: String): List<RichRun> {
    val href = safeHref(typedHref) ?: return runs
    return saveSafeLink(runs, request, text, href)
}

private fun saveSafeLink(runs: List<RichRun>, request: LinkRequest, text: String, href: String): List<RichRun> = normalizeRuns(
    when (request.mode) {
        LinkMode.SELECTION -> mapRange(runs, request.start, request.end) { it.copy(href = href) }
        LinkMode.INSERT -> {
            val split = splitAt(runs, request.start)
            var offset = 0
            val index = split.indexOfFirst { run -> (offset >= request.start).also { offset += run.text.length } }
            val at = if (index < 0) split.size else index
            split.subList(0, at) + RichRun(text, href = href) + split.subList(at, split.size)
        }
        LinkMode.EDIT -> {
            val link = runLinkAround(runs, request.start) ?: return runs
            val split = splitAt(splitAt(runs, link.start), link.end)
            var offset = 0
            val before = mutableListOf<RichRun>()
            val after = mutableListOf<RichRun>()
            var marks: List<RichMark>? = null
            for (run in split) {
                val at = offset
                offset += run.text.length
                when {
                    at < link.start -> before += run
                    at >= link.end -> after += run
                    marks == null -> marks = run.marks
                }
            }
            before + RichRun(text, marks.orEmpty(), href) + after
        }
    },
)

/** The line of [runs] with [link] turned back into plain text, its words kept. */
fun removeLink(runs: List<RichRun>, link: RunLink): List<RichRun> =
    normalizeRuns(mapRange(runs, link.start, link.end) { it.copy(href = null) })
