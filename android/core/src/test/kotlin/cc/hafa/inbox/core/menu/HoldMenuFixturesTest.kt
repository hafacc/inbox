package cc.hafa.inbox.core.menu

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/hold-menu-fixtures.json`, as `shared/src/hold-menu.spec.ts` does on web. */
class HoldMenuFixturesTest {
    private val fixtures = Fixtures.load("hold-menu-fixtures.json")

    private fun number(value: Any?): Float = (value as Number).toFloat()

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun side(value: Any?): CornerSide = CornerSide.valueOf((value as String).uppercase())

    private fun arcs(value: Any?): List<Triple<String, Float, Float>> =
        (value as List<*>).map { arc ->
            val fields = arc as Map<*, *>
            Triple(fields["id"] as String, number(fields["from"]), number(fields["to"]))
        }

    private fun plain(wedges: List<HoldArc>): List<Triple<String, Float, Float>> = wedges.map { Triple(it.id, it.from, it.to) }

    private fun rowWedges(row: Map<*, *>): List<HoldArc> =
        rowHoldWedges((row["slots"] as List<*>).map { it as String? }, number(row["x"]), number(row["y"]), number(row["width"]), number(row["height"]))

    private fun wedgesOf(menu: Map<*, *>): List<HoldArc> {
        val row = menu["row"] as Map<*, *>?
        val view = menu["view"] as Map<*, *>?
        return if (row != null) {
            rowWedges(row)
        } else if (view != null) {
            viewHoldWedges(strings(view["ids"]))
        } else {
            val corner = menu["corner"] as Map<*, *>
            cornerHoldWedges(strings(corner["inner"]), strings(corner["outer"]), side(corner["side"]))
        }
    }

    @Test
    fun link() {
        for (case in Fixtures.cases(fixtures, "link")) {
            val wedges = linkHoldWedges(strings(case["ids"]), number(case["x"]), number(case["y"]), number(case["width"]))
            assertEquals(case["name"] as String, arcs(case["expect"]), plain(wedges))
        }
    }

    @Test
    fun corner() {
        for (case in Fixtures.cases(fixtures, "corner")) {
            val wedges = cornerHoldWedges(strings(case["inner"]), strings(case["outer"]), side(case["side"]))
            assertEquals(case["name"] as String, arcs(case["expect"]), plain(wedges))
            val rings = (case["expect"] as List<*>).map { if ((it as Map<*, *>)["ring"] == "inner") CORNER_INNER_RING else CORNER_OUTER_RING }
            assertEquals(case["name"] as String, rings, wedges.map { it.ring })
        }
    }

    @Test
    fun row() {
        for (case in Fixtures.cases(fixtures, "row")) {
            val wedges = rowWedges(case)
            assertEquals(case["name"] as String, arcs(case["expect"]), plain(wedges))
            assertEquals(case["name"] as String, true, wedges.all { it.ring == ROW_RING })
        }
    }

    @Test
    fun view() {
        for (case in Fixtures.cases(fixtures, "view")) {
            val wedges = viewHoldWedges(strings(case["ids"]))
            assertEquals(case["name"] as String, arcs(case["expect"]), plain(wedges))
            assertEquals(case["name"] as String, true, wedges.all { it.ring == VIEW_RING })
        }
    }

    @Test
    fun drag() {
        for (case in Fixtures.cases(fixtures, "drag")) {
            val press = case["press"] as List<*>
            val at = case["at"] as List<*>
            val starts = cornerDragStarts(number(press[1]), number(at[1]), case["reachedWedge"] as Boolean)
            assertEquals(case["name"] as String, case["expect"] as Boolean, starts)
        }
    }

    @Test
    fun hit() {
        for (case in Fixtures.cases(fixtures, "hit")) {
            val wedges = wedgesOf(case["menu"] as Map<*, *>)
            val center = case["center"] as List<*>
            for (point in case["points"] as List<*>) {
                val fields = point as Map<*, *>
                val at = fields["at"] as List<*>
                val hit = hitWedge(wedges, number(center[0]), number(center[1]), number(at[0]), number(at[1]))
                assertEquals("${case["name"]} at $at", (fields["expect"] as Number).toInt(), hit)
            }
        }
    }

    @Test
    fun sides() {
        for (case in Fixtures.cases(fixtures, "side")) {
            assertEquals(case["name"] as String, side(case["expect"]), cornerSide(number(case["x"]), number(case["width"])))
        }
    }

    @Test
    fun pill() {
        for (case in Fixtures.cases(fixtures, "pillTop")) {
            val center = case["center"] as List<*>
            val top = pillTop(number(center[0]), number(center[1]), wedgesOf(case["menu"] as Map<*, *>), number(case["topLimit"]))
            assertEquals(case["name"] as String, number(case["expect"]), top, 0.01f)
        }
    }
}
