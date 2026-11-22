package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.SnoozedRule
import cc.hafa.inbox.core.mail.ViewFilter
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/label-tag-fixtures.json`, as shared/src/todos/label-tags.spec.ts does. */
class LabelTagFixturesTest {
    private val fixtures = Fixtures.load("label-tag-fixtures.json")
    private val index = LabelTags(
        (fixtures["labels"] as List<*>).map { entry ->
            val label = entry as Map<*, *>
            GmailLabel(id = label["id"] as String, name = label["name"] as String, type = label["type"] as String)
        },
    )

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun todos(value: Any?): List<Todo> = (value as List<*>).mapIndexed { position, entry ->
        val fields = entry as Map<*, *>
        Todo.read(fields["id"] as String? ?: "todo$position", fields.mapKeys { it.key as String }, 0L)
    }

    private fun filter(value: Any?): ViewFilter {
        val fields = value as Map<*, *>
        return ViewFilter(strings(fields["requires"]), strings(fields["excludes"]), SnoozedRule.valueOf((fields["snoozed"] as String).uppercase()))
    }

    @Test
    fun keys() {
        for (case in cases("keys")) {
            val name = case["in"] as String
            assertEquals(case["name"] as String, listOf(case["key"], case["tag"]), listOf(Tags.key(name), LabelTags.labelTag(name)))
        }
    }

    @Test
    fun carried() {
        for (case in cases("carried")) {
            assertEquals(case["name"] as String, case["ids"], index.labelIds(case["text"] as String))
        }
    }

    @Test
    fun color() {
        for (case in cases("color")) {
            assertEquals(case["name"] as String, case["id"], index.labelOf(case["tag"] as String)?.id)
        }
    }

    @Test
    fun suggest() {
        for (case in cases("suggest")) {
            val counts = Tags.count(strings(case["texts"]))
            val limit = (case["limit"] as Number?)?.toInt() ?: 5
            assertEquals(
                case["name"] as String,
                case["out"],
                Tags.suggest(counts, case["own"] as String, case["prefix"] as String, limit, index.names),
            )
        }
    }

    @Test
    fun rename() {
        for (case in cases("rename")) {
            assertEquals(case["name"] as String, case["out"], LabelTags.rename(case["in"] as String, case["from"] as String, case["to"] as String))
        }
    }

    @Test
    fun list() {
        for (case in cases("list")) {
            assertEquals(case["name"] as String, case["ids"], index.todosOf(todos(case["todos"]), case["labelId"] as String).map { it.id })
        }
    }

    @Test
    fun threads() {
        for (case in cases("threads")) {
            assertEquals(case["name"] as String, case["ids"], LabelTags.taggedThreadIds(strings(case["threadIds"]), strings(case["listed"])))
        }
    }

    @Test
    fun filter() {
        for (case in cases("filter")) {
            assertEquals(case["name"] as String, filter(case["out"]), index.taggedFilter(filter(case["filter"])))
        }
    }

    @Test
    fun unlabeled() {
        for (case in cases("unlabeled")) {
            val expected = (case["out"] as List<*>).map { entry ->
                val tag = entry as Map<*, *>
                UnlabeledTag(tag["tag"] as String, tag["label"] as String)
            }
            assertEquals(case["name"] as String, expected, LabelTags.unlabeledTags(strings(case["texts"]), index.labels))
        }
    }
}
