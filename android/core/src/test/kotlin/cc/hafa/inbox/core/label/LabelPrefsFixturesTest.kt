package cc.hafa.inbox.core.label

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/label-prefs-fixtures.json`, so label settings default as on web. */
class LabelPrefsFixturesTest {
    @Test
    fun prefs() {
        for (case in Fixtures.cases(Fixtures.labelPrefs, "prefs")) {
            @Suppress("UNCHECKED_CAST")
            val label = case["label"] as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val expect = case["expect"] as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val stored = case["stored"] as? Map<String, Any?>
            assertEquals(
                case["name"] as String,
                LabelPrefs(expect["sidebar"] as Boolean, expect["bundle"] as Boolean, expect["icon"] as String?),
                labelPrefsFrom(stored, label["id"] as String, label["labelListVisibility"] as String?),
            )
        }
    }

    @Test
    fun categoryIconsAreKnown() {
        assertTrue(LabelIcons.icons.size in 30..40)
        for (icon in LabelIcons.categories.values) assertTrue(icon, LabelIcons.known(icon))
    }

    @Test
    fun categoriesHaveTheirOwnColors() {
        val tones = LabelIcons.categoryTones
        assertEquals(LabelIcons.categories.keys, tones.keys)
        assertEquals(tones.size, tones.values.toSet().size)
        for (tone in tones.values) assertTrue(tone, tone in LABEL_SWATCHES)
    }
}
