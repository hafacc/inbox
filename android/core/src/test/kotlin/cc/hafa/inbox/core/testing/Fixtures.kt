package cc.hafa.inbox.core.testing

import org.json.JSONArray
import org.json.JSONObject

/** Cross-platform fixtures in `testdata/`, shared with web's specs. */
object Fixtures {
    fun load(name: String): Map<String, Any?> = parse(text(name))

    /** A JSON object's text as plain maps, lists and values. */
    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): Map<String, Any?> = plain(JSONObject(json)) as Map<String, Any?>

    /** A fixture file's raw text. */
    fun text(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
        "testdata/$name missing from test resources"
    }.bufferedReader().use { it.readText() }

    val snooze: Map<String, Any?> by lazy { load("snooze-fixtures.json") }

    /** `compose-fixtures.json`: message building. */
    val compose: Map<String, Any?> by lazy { load("compose-fixtures.json") }

    /** `rich-fixtures.json`: the composer's rich text, its HTML and its markdown. */
    val rich: Map<String, Any?> by lazy { load("rich-fixtures.json") }

    /** `send-fixtures.json`: schedule-send suggestions. */
    val send: Map<String, Any?> by lazy { load("send-fixtures.json") }

    /** `calendar-fixtures.json`: invites, RSVP replies and their MIME. */
    val calendar: Map<String, Any?> by lazy { load("calendar-fixtures.json") }

    /** `sanitize-fixtures.json`: message HTML cleaning. */
    val sanitize: Map<String, Any?> by lazy { load("sanitize-fixtures.json") }

    /** `search-fixtures.json`: search chips and completion. */
    val search: Map<String, Any?> by lazy { load("search-fixtures.json") }

    /** `filter-fixtures.json`: the Gmail filter editor. */
    val filters: Map<String, Any?> by lazy { load("filter-fixtures.json") }

    /** `unsubscribe-fixtures.json`: List-Unsubscribe headers. */
    val unsubscribe: Map<String, Any?> by lazy { load("unsubscribe-fixtures.json") }

    /** `section-fixtures.json`: list section headings. */
    val sections: Map<String, Any?> by lazy { load("section-fixtures.json") }

    /** `label-group-fixtures.json`: inbox bundles across time sections. */
    val labelGroups: Map<String, Any?> by lazy { load("label-group-fixtures.json") }

    /** `held-place-fixtures.json`: places open threads and bundles keep until they close. */
    val heldPlaces: Map<String, Any?> by lazy { load("held-place-fixtures.json") }

    /** `bundle-fixtures.json`: bundles' own inbox lists and their counts. */
    val bundles: Map<String, Any?> by lazy { load("bundle-fixtures.json") }

    /** `label-prefs-fixtures.json`: per-label settings and their defaults. */
    val labelPrefs: Map<String, Any?> by lazy { load("label-prefs-fixtures.json") }

    /** `category-fixtures.json`: moving threads between Gmail's categories. */
    val categories: Map<String, Any?> by lazy { load("category-fixtures.json") }

    /** `selection-fixtures.json`: selecting threads and acting on them together. */
    val selection: Map<String, Any?> by lazy { load("selection-fixtures.json") }
    /** `focus-fixtures.json`: the important mark and focus mode. */
    val focus: Map<String, Any?> by lazy { load("focus-fixtures.json") }

    /** `thread-open-fixtures.json`: which messages start expanded and where an opened thread scrolls. */
    val threadOpen: Map<String, Any?> by lazy { load("thread-open-fixtures.json") }

    /** `thread-draft-fixtures.json`: drafts and messages being sent in an open thread. */
    val threadDrafts: Map<String, Any?> by lazy { load("thread-draft-fixtures.json") }

    /** `outbox-draft-fixtures.json`: the drafts of scheduled sends, which show only in the Outbox. */
    val outboxDrafts: Map<String, Any?> by lazy { load("outbox-draft-fixtures.json") }

    /** `offline-draft-fixtures.json`: drafts listed and opened from the saved copy. */
    val offlineDrafts: Map<String, Any?> by lazy { load("offline-draft-fixtures.json") }

    /** `outbox-held-fixtures.json`: sends held on the device with no outbox doc yet. */
    val outboxHeld: Map<String, Any?> by lazy { load("outbox-held-fixtures.json") }

    /** `direct-send-fixtures.json`: a send now sent from the device itself. */
    val directSend: Map<String, Any?> by lazy { load("direct-send-fixtures.json") }

    /** `link-fixtures.json`: link unwrapping. */
    val links: Map<String, Any?> by lazy { load("link-fixtures.json") }

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    fun cases(section: String): List<Map<String, Any?>> = cases(snooze, section)

    /** One section of a loaded fixture file. */
    @Suppress("UNCHECKED_CAST")
    fun cases(file: Map<String, Any?>, section: String): List<Map<String, Any?>> = file[section] as List<Map<String, Any?>>

    /** Numbers compared by value, so a JSON `2` equals a Kotlin `2.0`. */
    fun numbersAsDouble(value: Any?): Any? = when (value) {
        is Number -> value.toDouble()
        is Map<*, *> -> value.entries.associate { (k, v) -> k to numbersAsDouble(v) }
        is List<*> -> value.map { numbersAsDouble(it) }
        else -> value
    }
}
