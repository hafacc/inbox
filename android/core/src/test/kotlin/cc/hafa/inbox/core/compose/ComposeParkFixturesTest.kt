package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/compose-park-fixtures.json`, so parking a composer matches web's. */
class ComposeParkFixturesTest {
    private val fixtures = Fixtures.load("compose-park-fixtures.json")

    private fun kind(name: String) = when (name) {
        "reply" -> ComposeKind.REPLY
        "forward" -> ComposeKind.FORWARD
        else -> ComposeKind.NEW
    }

    @Test
    fun title() {
        for (case in Fixtures.cases(fixtures, "title")) {
            assertEquals(case["name"] as String, case["expect"], ComposePark.title(case["subject"] as String, kind(case["kind"] as String)))
        }
    }

    @Test
    fun release() {
        for (case in Fixtures.cases(fixtures, "release")) {
            val dragged = (case["dragged"] as Number).toFloat()
            val velocity = (case["velocity"] as Number).toFloat()
            assertEquals(case["name"] as String, case["expect"], ComposePark.parksOnRelease(dragged, velocity))
        }
    }
}
