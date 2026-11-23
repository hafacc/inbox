package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/todo-list-fixtures.json`, as shared/src/todos/list.spec.ts does. */
class TodoListFixturesTest {
    private val fixtures = Fixtures.load("todo-list-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val merged = base + (fields as Map<String, Any?>)
        return Todo.read(merged["id"] as String, merged, 0L)
    }

    private fun long(value: Any?): Long = (value as Number).toLong()

    private fun shown(case: Map<String, Any?>): List<Todo> = (case["todos"] as List<*>).map(::todo).sortedFor(TodoFilter.ACTIVE, zone)

    @Test
    fun group() {
        for (case in cases("group")) {
            val buckets = groupActive(shown(case), long(case["now"]), zone).map { bucket ->
                mapOf(
                    "label" to bucket.label,
                    "ids" to bucket.todos.map { it.id },
                    "pinned" to bucket.pinned,
                    "floor" to bucket.floor,
                    "ceiling" to bucket.ceiling,
                )
            }
            val expected = (case["buckets"] as List<*>).map { entry ->
                (entry as Map<*, *>).mapValues { (key, value) -> if (key == "floor" || key == "ceiling") (value as Number?)?.toLong() else value }
            }
            assertEquals(case["name"] as String, expected, buckets)
        }
    }

    @Test
    fun move() {
        for (case in cases("move")) {
            val name = case["name"] as String
            val list = shown(case)
            val now = long(case["now"])
            val buckets = groupActive(list, now, zone)
            val moving = list.first { it.id == case["id"] }
            val slot = (case["slot"] as Map<*, *>).let { DropSlot((it["bucket"] as Number).toInt(), (it["index"] as Number).toInt()) }
            val moved = planMove(moving, buckets, slot, now, zone)
            when (val out = case["out"]) {
                "same" -> assertSame(name, moving, moved)
                null -> assertNull(name, moved)
                else -> assertEquals(name, todo(out), moved)
            }
        }
    }

    @Test
    fun held() {
        for (case in cases("held")) {
            val result = heldInPlace(todo(case["held"]), todo(case["previous"]), todo(case["live"]))
            assertEquals(case["name"] as String, todo(case["out"]), result)
        }
    }

    @Test
    fun search() {
        for (case in cases("search")) {
            val todos = (case["todos"] as List<*>).map(::todo)
            assertEquals(case["name"] as String, case["ids"], rankBySearch(todos, case["query"] as String).map { it.id })
        }
    }

    private fun plain(node: StyledNode): Map<String, Any?> = when (node) {
        is StyledNode.Text -> mapOf("kind" to "text", "text" to node.text)
        is StyledNode.Marker -> mapOf("kind" to "marker", "text" to node.text)
        is StyledNode.Tag -> mapOf("kind" to "tag", "text" to node.text)
        is StyledNode.Link -> mapOf("kind" to "link", "text" to node.text, "href" to node.href)
        is StyledNode.Span -> mapOf("kind" to "span", "style" to node.style.name.lowercase(), "children" to node.children.map(::plain))
    }

    @Test
    fun styled() {
        for (case in cases("styled")) {
            val text = case["in"] as String
            assertEquals(text, case["tree"], styledTree(text).map(::plain))
        }
    }

    @Test
    fun plain() {
        for (case in cases("plain")) {
            val text = case["in"] as String
            assertEquals(text, case["out"], styledPlain(text))
        }
    }
}
