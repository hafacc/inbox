package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/swipe-fixtures.json`, as `shared/src/swipe.spec.ts` does on web. */
class SwipeFixturesTest {
    private val fixtures = Fixtures.load("swipe-fixtures.json")

    private fun side(name: Any?): SwipeSide? = when (name) {
        "start" -> SwipeSide.START
        "end" -> SwipeSide.END
        else -> null
    }

    @Test
    fun threads() {
        for (case in Fixtures.cases(fixtures, "threads")) {
            val row = case["row"] as Map<*, *>
            val status = SnoozeStatus.valueOf((row["status"] as String).uppercase())
            val expect = case["expect"] as Map<*, *>
            for (sideName in listOf("start", "end")) {
                val action = threadSwipeAction(
                    side(sideName)!!,
                    status,
                    inInbox = row["inInbox"] as Boolean,
                    spamOrTrash = row["spamOrTrash"] as Boolean,
                    inSnoozedView = row["inSnoozedView"] as Boolean,
                )
                assertEquals("${case["name"]} $sideName", expect[sideName], action?.name?.lowercase())
            }
        }
    }

    @Test
    fun todos() {
        for (case in Fixtures.cases(fixtures, "todos")) {
            val row = case["row"] as Map<*, *>
            val expect = case["expect"] as Map<*, *>
            for (sideName in listOf("start", "end")) {
                val action = todoSwipeAction(
                    side(sideName)!!,
                    done = row["done"] as Boolean,
                    snoozed = row["snoozed"] as Boolean,
                )
                assertEquals("${case["name"]} $sideName", expect[sideName], action.name.lowercase())
            }
        }
    }

    @Test
    fun commit() {
        for (case in Fixtures.cases(fixtures, "commit")) {
            val committed = committedSide(
                (case["dx"] as Number).toFloat(),
                (case["width"] as Number).toFloat(),
                (case["velocity"] as Number).toFloat(),
            )
            assertEquals(case["name"] as String, side(case["expect"]), committed)
        }
    }

    @Test
    fun progress() {
        for (case in Fixtures.cases(fixtures, "progress")) {
            val progress = swipeProgress((case["dx"] as Number).toFloat(), (case["width"] as Number).toFloat())
            assertEquals(case["name"] as String, (case["expect"] as Number).toFloat(), progress, 0f)
        }
    }

    @Test
    fun bundles() {
        for (case in Fixtures.cases(fixtures, "bundles")) {
            val expect = case["expect"] as Map<*, *>
            for (sideName in listOf("start", "end")) {
                val action = bundleSwipeAction(side(sideName)!!, open = case["open"] as Boolean)
                assertEquals("${case["name"]} $sideName", expect[sideName], action?.name?.lowercase())
            }
            val labels = case["labels"] as Map<*, *>
            for (action in BundleSwipeAction.entries) {
                assertEquals("${case["name"]} ${action.name}", labels[action.name.lowercase()], bundleSwipeLabel(action, case["threads"] as String))
            }
        }
    }
}
