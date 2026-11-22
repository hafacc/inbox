package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/back-stack-fixtures.json`, as `shared/src/back-stack.spec.ts` does on web. */
class BackStackFixturesTest {
    private val fixtures = Fixtures.load("back-stack-fixtures.json")

    private fun layer(key: Any?): BackLayer = BackLayer.entries.first { it.key == key }

    private fun event(json: Map<*, *>): BackEvent = when (json["type"]) {
        "open" -> BackEvent.Open(layer(json["layer"]))
        "close" -> BackEvent.Close(layer(json["layer"]))
        "view" -> BackEvent.ShowView(json["view"] as String)
        "back" -> BackEvent.Back
        else -> error("unknown event ${json["type"]}")
    }

    private fun effect(json: Map<*, *>?): BackEffect? = when (json?.get("kind")) {
        null -> null
        "close" -> BackEffect.Close(layer(json["layer"]))
        "view" -> BackEffect.ShowView(json["view"] as String)
        "leave" -> BackEffect.Leave
        else -> error("unknown effect ${json["kind"]}")
    }

    @Test
    fun backStep() {
        for (case in Fixtures.cases(fixtures, "backStep")) {
            val name = case["name"] as String
            var stack = startBack(case["start"] as String)
            for (step in case["steps"] as List<*>) {
                val fields = step as Map<*, *>
                val (next, effect) = backStep(stack, event(fields["event"] as Map<*, *>))
                assertEquals(name, effect(fields["effect"] as Map<*, *>?), effect)
                stack = next
            }
            val expected = case["stack"] as Map<*, *>
            assertEquals(name, expected["views"], stack.views)
            assertEquals(name, (expected["layers"] as List<*>).map(::layer), stack.layers)
        }
    }

    @Test
    fun viewStep() {
        for (case in Fixtures.cases(fixtures, "viewStep")) {
            val step = viewStep(case["previous"] as String?, case["current"] as String, case["next"] as String)
            assertEquals(case["name"] as String, case["expect"], step.name.lowercase())
        }
    }

    @Test
    fun viewKeysNameTheirViews() {
        val views = Mailbox.entries.map(View::Fixed) + listOf(View.Todos, View.Label("Label_7"), View.Search("from:ann label:a"))
        for (view in views) assertEquals(view, viewFromKey(view.key))
        assertEquals(null, viewFromKey("outbox"))
    }
}
