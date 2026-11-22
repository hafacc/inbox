package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/offline-set-fixtures.json`, as shared/src/offline-set.spec.ts does. */
class OfflineSetFixturesTest {
    private val fixtures = Fixtures.load("offline-set-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private fun thread(raw: Any?): OfflineThread {
        val thread = raw as Map<*, *>
        return OfflineThread(
            thread["id"] as String,
            strings(thread["labelIds"]),
            (thread["newestAt"] as Number).toLong(),
            (thread["openedAt"] as Number?)?.toLong(),
            thread["encrypted"] as Boolean,
        )
    }

    @Test
    fun days() {
        for (case in cases("days")) assertEquals(case["name"] as String, case["expect"], offlineDays(case["value"]))
    }

    @Test
    fun tier() {
        for (case in cases("tier")) {
            val tier = offlineTier(thread(case["thread"]), case["snoozed"] as Boolean, case["days"] as Int, (case["now"] as Number).toLong())
            assertEquals(case["name"] as String, case["expect"], tier?.name?.lowercase())
        }
    }

    @Test
    fun plan() {
        for (case in cases("plan")) {
            val expected = case["expect"] as Map<*, *>
            val plan = offlinePlan(
                (case["threads"] as List<*>).map(::thread),
                strings(case["snoozed"]).toSet(),
                case["days"] as Int,
                (case["now"] as Number).toLong(),
                case["cap"] as Int,
                case["encryptedCap"] as Int,
            )
            assertEquals(case["name"] as String, OfflinePlan(strings(expected["keep"]), strings(expected["drop"])), plan)
        }
    }

    @Test
    fun queries() {
        for (case in cases("queries")) {
            val expected = (case["expect"] as List<*>).map {
                val query = it as Map<*, *>
                OfflineQuery(query["query"] as String, query["cap"] as Int?)
            }
            assertEquals(case["name"] as String, expected, offlineQueries(case["days"] as Int))
        }
    }

    @Test
    fun eviction() {
        for (case in cases("eviction")) {
            val usage = (case["usage"] as List<*>).map {
                val pair = it as List<*>
                pair[0] as String to (pair[1] as Number).toLong()
            }
            assertEquals(case["name"] as String, case["expect"], evictionKeys(usage, case["cap"] as Int))
        }
    }

    @Test
    fun prune() {
        for (case in cases("prune")) {
            val usage = (case["usage"] as Map<*, *>).entries.associate { (key, value) -> key as String to (value as Number).toLong() }
            val prefix = case["expiredPrefix"] as String?
            val dropped = pruneKeys(
                strings(case["keys"]),
                usage,
                case["cap"] as Int,
                { key, _ -> prefix != null && key.startsWith(prefix) },
                strings(case["kept"]).toSet(),
            )
            assertEquals(case["name"] as String, case["expect"], dropped)
        }
    }
}
