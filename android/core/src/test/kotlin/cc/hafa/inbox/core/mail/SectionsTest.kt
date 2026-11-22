package cc.hafa.inbox.core.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsTest {
    @Test
    fun `sectionize keeps order and merges consecutive items only`() {
        val sections = sectionize(listOf(1, 2, 5, 6, 2)) { value ->
            if (value < 4) SectionHead("low", "Low") else SectionHead("high", "High")
        }
        assertEquals(
            listOf("low" to listOf(1, 2), "high" to listOf(5, 6), "low" to listOf(2)),
            sections.map { it.key to it.items },
        )
    }
}
