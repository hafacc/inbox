package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/chip-or-fixtures.json`, so typing around chips in Android's search field matches web's. */
class ChipOrFixturesTest {
    private val fixtures by lazy { Fixtures.load("chip-or-fixtures.json") }

    private fun chip(raw: Any?): Chip {
        val map = raw as Map<*, *>
        return Chip(map["operator"] as String, map["value"] as String, map["negated"] as Boolean)
    }

    private fun chips(raw: Any?): List<Chip> = (raw as List<*>?).orEmpty().map(::chip)

    private fun spot(raw: Any?): ChipSpot? {
        val map = raw as? Map<*, *> ?: return null
        return ChipSpot(chip(map["chip"]), (map["index"] as Number).toInt(), map["before"] as String)
    }

    @Test
    fun type() {
        for (case in Fixtures.cases(fixtures, "type")) {
            var chips = chips(case["chips"])
            var spot = spot(case["spot"])
            for (step in case["steps"] as List<*>) {
                val map = step as Map<*, *>
                val expect = map["expect"] as Map<*, *>
                val typed = typeFree(chips, spot, map["text"] as String, (map["cursor"] as Number?)?.toInt(), case["tags"] == true)
                val name = "${case["name"]}: ${map["text"]}"
                assertEquals(name, chips(expect["chips"]), typed.chips)
                assertEquals(name, expect["free"], typed.parsed.free)
                assertEquals(name, (expect["cursor"] as Number).toInt(), typed.parsed.cursor)
                assertEquals(name, spot(expect["spot"]), typed.spot)
                chips = typed.chips
                spot = typed.spot
            }
        }
    }
}
