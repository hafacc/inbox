package cc.hafa.inbox.core.send

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/outbox-copy-fixtures.json`, so Bcc copies' docs are named and grouped as web's. */
class OutboxCopiesTest {
    private val fixtures = Fixtures.load("outbox-copy-fixtures.json")

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    @Test
    fun copyIds() {
        for (case in Fixtures.cases(fixtures, "copyIds")) {
            assertEquals(case["expect"], copyOutboxId(case["base"] as String, (case["copy"] as Number).toInt()))
        }
    }

    @Test
    fun ids() {
        for (case in Fixtures.cases(fixtures, "ids")) {
            val id = case["id"] as String
            val copy = (case["copy"] as Number?)?.toInt()
            assertEquals(id, copy, copyNumber(id))
            assertEquals(id, copy != null, isCopyId(id))
            assertEquals(id, case["base"], baseOutboxId(id))
        }
    }

    @Test
    fun groups() {
        for (case in Fixtures.cases(fixtures, "groups")) {
            val ids = strings(case["ids"])
            val base = case["base"] as String
            val name = case["name"] as String
            assertEquals(name, strings(case["group"]), sendGroup(ids, base))
            val next = (case["next"] as List<*>).map { (it as Number).toInt() }
            assertEquals(name, next, nextCopyNumbers(ids, base, (case["count"] as Number).toInt()))
        }
    }
}
