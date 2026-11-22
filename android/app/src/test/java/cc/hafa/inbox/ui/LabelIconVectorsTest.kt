package cc.hafa.inbox.ui

import cc.hafa.inbox.core.label.LabelIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every shared label icon id draws, as the Material icon the shared list names. */
class LabelIconVectorsTest {
    @Test
    fun everyIconMaps() {
        assertEquals(LabelIcons.icons.map { it.id }.toSet(), LABEL_ICON_VECTORS.keys)
        for (icon in LabelIcons.icons) {
            val vector = LABEL_ICON_VECTORS.getValue(icon.id)
            assertTrue("${icon.id}: ${vector.name}", vector.name.endsWith(".${icon.material}"))
        }
    }
}
