package cc.hafa.inbox.core.label

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/label-name-fixtures.json`, so Android names, sorts and renames labels as web does. */
class LabelNameFixturesTest {
    private val fixtures by lazy { Fixtures.load("label-name-fixtures.json") }

    private val labels by lazy {
        (fixtures["labels"] as List<*>).map {
            val map = it as Map<*, *>
            GmailLabel(map["id"] as String, map["name"] as String, map["type"] as String)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    @Test
    fun sort() {
        val sort = fixtures["sort"] as Map<*, *>
        assertEquals(strings(sort["expect"]), strings(sort["names"]).sortedWith(LABEL_NAME_ORDER))
    }

    @Test
    fun parts() {
        for (case in Fixtures.cases(fixtures, "parts")) {
            val name = case["name"] as String
            assertEquals(name, case["parent"], parentName(name))
            assertEquals(name, case["leaf"], leafName(name))
        }
    }

    @Test
    fun nested() {
        for (case in Fixtures.cases(fixtures, "nested")) {
            assertEquals(case["expect"], nestedName(case["parent"] as String?, case["leaf"] as String))
        }
    }

    @Test
    fun renames() {
        for (case in Fixtures.cases(fixtures, "renames")) {
            val expect = (case["expect"] as List<*>).map { val map = it as Map<*, *>; LabelRename(map["id"] as String, map["name"] as String) }
            assertEquals(case["name"] as String, expect, renames(labels, case["id"] as String, case["newName"] as String))
        }
    }

    @Test
    fun errors() {
        for (case in Fixtures.cases(fixtures, "errors")) {
            assertEquals(case["name"] as String, case["expect"], labelNameError(labels, case["text"] as String, case["exceptId"] as String?))
        }
    }

    @Test
    fun named() {
        for (case in Fixtures.cases(fixtures, "named")) {
            assertEquals(case["name"] as String, case["expect"], labelNamed(labels, case["text"] as String)?.id)
        }
    }
}
