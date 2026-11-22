package cc.hafa.inbox.core.label

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/category-fixtures.json`, so moving between categories matches web. */
class CategoryFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    @Test
    fun current() {
        for (case in Fixtures.cases(Fixtures.categories, "current")) {
            assertEquals(case["name"] as String, case["expect"], threadCategory(strings(case["labelIds"])))
        }
    }

    @Test
    fun change() {
        for (case in Fixtures.cases(Fixtures.categories, "change")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                LabelDelta(strings(expect["add"]), strings(expect["remove"])),
                categoryChange(strings(case["labelIds"]), case["chosen"] as String),
            )
        }
    }

    @Test
    fun keepListed() {
        for (case in Fixtures.cases(Fixtures.categories, "keepListed")) {
            val dates = case["dates"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                strings(case["expect"]),
                keepListed(strings(case["ids"]), case["id"] as String) { (dates[it] as Number?)?.toLong() },
            )
        }
    }

    @Test
    fun choicesHaveIconsAndTones() {
        for (id in CATEGORY_IDS) {
            assertTrue(id, LabelIcons.known(CATEGORY_CHOICE_ICONS.getValue(id)))
            assertNotNull(id, CATEGORY_CHOICE_TONES[id])
        }
    }
}
