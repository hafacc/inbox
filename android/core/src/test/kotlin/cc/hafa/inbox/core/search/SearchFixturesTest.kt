package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Runs `shared/testdata/search-fixtures.json`, so Android's search field matches web's. */
class SearchFixturesTest {
    private fun cases(section: String) = Fixtures.cases(Fixtures.search, section)

    private fun int(raw: Any?): Int = (raw as Number).toInt()

    private fun chip(raw: Any?): Chip {
        val map = raw as Map<*, *>
        return Chip(map["operator"] as String, map["value"] as String, map["negated"] as Boolean)
    }

    private fun chips(raw: Any?): List<Chip> = (raw as List<*>).map(::chip)

    private fun context(raw: Any?): CompletionContext? {
        val map = raw as? Map<*, *> ?: return null
        val prefix = map["prefix"] as String
        val negated = map["negated"] as Boolean
        return if (map["kind"] == "operator") {
            OperatorContext(prefix, negated, int(map["start"]), int(map["end"]))
        } else {
            ValueContext(map["operator"] as String, prefix, map["value"] as String? ?: prefix, negated, int(map["start"]), int(map["end"]))
        }
    }

    private fun candidate(raw: Any?): Candidate {
        val map = raw as Map<*, *>
        return Candidate(map["label"] as String, map["value"] as String, map["detail"] as String?)
    }

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = (raw as List<String>?).orEmpty()

    private fun people(raw: Any?): List<Person> =
        (raw as List<*>?).orEmpty().map { val map = it as Map<*, *>; Person(map["name"] as String, map["email"] as String) }

    @Test
    fun parse() {
        for (case in cases("parse")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                ParsedQuery(chips(expect["chips"]), expect["free"] as String, int(expect["cursor"]), context(expect["context"])),
                parseQuery(case["text"] as String, (case["cursor"] as Number?)?.toInt(), case["tags"] == true),
            )
        }
    }

    @Test
    fun format() {
        for (case in cases("format")) assertEquals(case["expect"], formatChip(chip(case["chip"])))
    }

    @Test
    fun query() {
        for (case in cases("query")) assertEquals(case["expect"], queryText(chips(case["chips"]), case["free"] as String))
    }

    @Test
    fun gmailQuery() {
        for (case in cases("gmailQuery")) assertEquals(case["expect"], gmailQueryText(chips(case["chips"]), case["free"] as String))
    }

    @Test
    fun completions() {
        for (case in cases("completions")) {
            val options = CompletionOptions(
                now = Instant.parse(case["now"] as String).toEpochMilli(),
                timeZone = case["timeZone"] as String,
                labels = strings(case["labels"]),
                contacts = people(case["contacts"]),
            )
            assertEquals(
                case["name"] as String,
                (case["expect"] as List<*>).map(::candidate),
                completions(context(case["context"])!!, options),
            )
        }
    }

    @Test
    fun apply() {
        for (case in cases("apply")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                Edit(expect["text"] as String, int(expect["cursor"])),
                applyCompletion(case["text"] as String, context(case["context"])!!, candidate(case["candidate"])),
            )
        }
    }

    @Test
    fun unchip() {
        for (case in cases("unchip")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                Unchipped(chips(expect["chips"]), expect["free"] as String, int(expect["cursor"])),
                unchip(chips(case["chips"]), int(case["index"]), case["free"] as String),
            )
        }
    }

    @Test
    fun local() {
        for (case in cases("local")) assertEquals(case["expect"], isLocalChip(chip(case["chip"])))
    }

    @Test
    fun withoutOperator() {
        for (case in cases("withoutOperator")) {
            val parsed = parseQuery(case["text"] as String, (case["cursor"] as Number).toInt())
            assertEquals(case["name"] as String, case["expect"], parsed.context?.let { withoutOperator(parsed.free, it) })
        }
    }

    @Test
    fun awaitsValue() {
        for (case in cases("awaitsValue")) {
            assertEquals(
                case["name"] as String,
                case["expect"],
                awaitsValue(parseQuery(case["text"] as String, (case["cursor"] as Number?)?.toInt()).context),
            )
        }
    }

    @Test
    fun chipsRoundTripThroughQueryText() {
        val parsed = parseQuery("from:\"Maya Chen\" -is:unread subject:(a b) larger:5M report", null)
        assertEquals(
            "from:\"Maya Chen\" -is:unread larger:5M subject:(a b) report",
            queryText(parsed.chips, parsed.free),
        )
        val again = parseQuery(queryText(parsed.chips, parsed.free), null)
        assertEquals(parsed.chips, again.chips)
        assertEquals(parsed.free, again.free)
    }

    @Test
    fun completedValueBecomesChip() {
        val parsed = parseQuery("in:sp", 5)
        val context = parsed.context!!
        val spam = completions(context, CompletionOptions(0, "UTC")).first()
        val edit = applyCompletion(parsed.free, context, spam)
        assertEquals(listOf(Chip("in", "spam", false)), parseQuery(edit.text, edit.cursor).chips)
    }

    @Test
    fun unchippedChipStaysTextUntilCursorLeaves() {
        val back = unchip(listOf(Chip("from", "a", false)), 0, "")
        assertEquals(emptyList<Chip>(), parseQuery(back.free, back.cursor).chips)
        assertEquals(1, parseQuery("${back.free} ", back.cursor + 1).chips.size)
    }

    @Test
    fun help() {
        val help = Fixtures.search["help"] as Map<*, *>
        val expected = (help["operators"] as List<*>).map { (it as Map<*, *>).let { row -> listOf(row["operator"], row["detail"], row["example"]) } }
        assertEquals(expected, OPERATORS.map { listOf(it, OPERATOR_DETAILS[it], OPERATOR_EXAMPLES[it]) })
        assertEquals(help["common"], COMMON_OPERATORS)
        val own = (help["own"] as List<*>).map { (it as Map<*, *>).let { row -> row["term"] to row["detail"] } }
        assertEquals(own, OWN_TERMS)
    }
}
