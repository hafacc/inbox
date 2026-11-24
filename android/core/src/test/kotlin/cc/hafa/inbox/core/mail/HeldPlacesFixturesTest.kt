package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/held-place-fixtures.json`, so open threads and bundles, and the keyboard's focused row, hold their places as on web. */
class HeldPlacesFixturesTest {
    private fun places(fixture: Any?): HeldPlaces {
        val json = fixture as Map<*, *>
        val threads = (json["threads"] as Map<*, *>).entries.associate { (id, place) ->
            place as Map<*, *>
            id as String to ThreadPlace((place["date"] as Number).toLong(), place["pinned"] as Boolean, place["single"] as Boolean)
        }
        val bundles = (json["bundles"] as Map<*, *>).entries.associate { (labelId, date) -> labelId as String to (date as Number).toLong() }
        val pins = (json["pins"] as Map<*, *>?).orEmpty().entries.associate { (id, pinned) -> id as String to pinned as Boolean }
        return HeldPlaces(threads, bundles, pins)
    }

    @Test
    fun hold() {
        for (case in Fixtures.cases(Fixtures.heldPlaces, "hold")) {
            val name = case["name"] as String
            val held = places(case["held"])
            val next = holdPlaces(held, places(case["open"]), case["release"] as Boolean)
            assertEquals(name, places(case["expect"]), next)
            assertEquals(name, case["same"], next === held)
        }
    }

    @Test
    fun pinHeld() {
        for (case in Fixtures.cases(Fixtures.heldPlaces, "pinHeld")) {
            val focusBy = (case["focusBy"] as String?)?.let { FocusSource.valueOf(it.uppercase()) }
            assertEquals(case["name"] as String, case["expect"], pinHeldFocus(case["focus"] as String?, focusBy))
        }
    }
}
