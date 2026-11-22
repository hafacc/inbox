package cc.hafa.inbox.core.compose

/** The inline image reference for [filename], its name without the extension as the alt text. */
fun imageMarkdown(filename: String, contentId: String): String {
    val alt = filename.filterNot { it == '[' || it == ']' || it == '\\' }.replace(Regex("""\.[^.]+$"""), "")
    return "![$alt](cid:$contentId)"
}
