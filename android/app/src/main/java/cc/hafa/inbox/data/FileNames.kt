package cc.hafa.inbox.data

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")

/** [name] with every character but letters, digits, `_` and `-` replaced by `_`, for use in a file name. */
fun safeFileName(name: String): String = name.replace(UNSAFE_FILE_CHARS, "_")
