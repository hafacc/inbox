package cc.hafa.inbox.core.label

import cc.hafa.inbox.core.gmail.GmailLabelColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An icon a label or bundle can show in place of its colored circle. */
data class LabelIcon(
    /** stored id */
    val id: String,
    /** name shown in the picker */
    val name: String,
    /** the Material Icons Outlined icon drawn for it */
    val material: String,
)

/** One label's settings, `users/{uid}/labelPrefs/{labelId}`, with defaults filled in. */
data class LabelPrefs(
    /** listed in the drawer */
    val sidebar: Boolean,
    /** folded into inbox bundles */
    val bundle: Boolean,
    /** icon id, or null for the colored circle */
    val icon: String?,
)

/** Stored in place of an icon id to show the colored circle instead. */
const val NO_ICON: String = "none"

/**
 * The label colors offered, by theme tone, each a text and background pair from Gmail's label
 * palette (the API refuses other colors); the same choices as web's.
 */
val LABEL_SWATCHES: Map<String, GmailLabelColor> = linkedMapOf(
    "red" to GmailLabelColor("#ffffff", "#fb4c2f"),
    "orange" to GmailLabelColor("#ffffff", "#ffad47"),
    "yellow" to GmailLabelColor("#000000", "#fad165"),
    "green" to GmailLabelColor("#ffffff", "#16a766"),
    "teal" to GmailLabelColor("#ffffff", "#2da2bb"),
    "blue" to GmailLabelColor("#ffffff", "#4a86e8"),
    "purple" to GmailLabelColor("#ffffff", "#a479e2"),
    "pink" to GmailLabelColor("#ffffff", "#f691b3"),
    "gray" to GmailLabelColor("#ffffff", "#999999"),
)

/** The icon list in `shared/themes/label-icons.json`, the same file web reads. */
object LabelIcons {
    private val root: JsonObject by lazy {
        val text = requireNotNull(LabelIcons::class.java.classLoader?.getResourceAsStream("label-icons.json")) {
            "label-icons.json missing from resources"
        }.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    /** Every icon the picker offers, in picker order. */
    val icons: List<LabelIcon> by lazy {
        root.getValue("icons").jsonArray.map { entry ->
            val icon = entry.jsonObject
            LabelIcon(
                icon.getValue("id").jsonPrimitive.content,
                icon.getValue("name").jsonPrimitive.content,
                icon.getValue("material").jsonPrimitive.content,
            )
        }
    }

    /** Each Gmail category's fixed icon. */
    val categories: Map<String, String> by lazy {
        root.getValue("categories").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    /** Each Gmail category's fixed color tone. */
    val categoryTones: Map<String, String> by lazy {
        root.getValue("categoryTones").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    /** Gmail's Primary category: its label id, fixed icon and color tone. */
    val primary: Triple<String, String, String> by lazy {
        val entry = root.getValue("primary").jsonObject
        Triple(
            entry.getValue("id").jsonPrimitive.content,
            entry.getValue("icon").jsonPrimitive.content,
            entry.getValue("tone").jsonPrimitive.content,
        )
    }

    /** Whether a label is one of Gmail's categories, whose name, color and icon are fixed. */
    fun isCategory(labelId: String): Boolean = labelId in categories

    private val ids: Set<String> by lazy { icons.map { it.id }.toSet() }

    /** Whether [id] is an icon the picker offers. */
    fun known(id: String): Boolean = id in ids
}

/**
 * A label's settings from its stored doc (any map, possibly missing), with defaults for the rest:
 * Gmail's `labelListVisibility` decides the drawer, bundling is on, and a category's icon is fixed.
 */
fun labelPrefsFrom(stored: Map<String, Any?>?, labelId: String, labelListVisibility: String?): LabelPrefs {
    val sidebar = stored?.get("sidebar") as? Boolean ?: (labelListVisibility != "labelHide")
    val bundle = stored?.get("bundle") as? Boolean ?: true
    val icon = LabelIcons.categories[labelId] ?: (stored?.get("icon") as? String)?.takeIf { LabelIcons.known(it) }
    return LabelPrefs(sidebar, bundle, icon)
}
