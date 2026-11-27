package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/bundle-fixtures.json`, so bundles' own lists match web's. */
class BundlesFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    @Test
    fun ids() {
        for (case in Fixtures.cases(Fixtures.bundles, "ids")) {
            val bundles = (case["bundles"] as List<*>).map {
                val bundle = it as Map<*, *>
                BundleList(bundle["labelId"] as String, strings(bundle["ids"]), bundle["more"] as Boolean)
            }
            val labels = case["labels"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                case["expect"],
                inboxThreadIds(strings(case["inboxIds"]), bundles) { id -> labels[id]?.let(::strings) },
            )
        }
    }

    @Test
    fun fetch() {
        for (case in Fixtures.cases(Fixtures.bundles, "fetch")) {
            val inboxLabels = (case["inboxLabels"] as List<*>).map(::strings)
            assertEquals(
                case["name"] as String,
                case["expect"],
                bundlesToFetch(
                    strings(case["bundleLabels"]),
                    inboxLabels,
                    strings(case["fetched"]).toSet(),
                    strings(case["remembered"]).toSet(),
                ),
            )
        }
    }

    @Test
    fun remember() {
        for (case in Fixtures.cases(Fixtures.bundles, "remember")) {
            val bundles = (case["bundles"] as List<*>).map {
                val bundle = it as Map<*, *>
                BundleList(bundle["labelId"] as String, strings(bundle["ids"]), bundle["more"] as Boolean)
            }
            assertEquals(case["name"] as String, case["expect"], bundlesToRemember(bundles))
        }
    }

    @Test
    fun inbox() {
        for (case in Fixtures.cases(Fixtures.bundles, "inbox")) {
            assertEquals(case["name"] as String, case["expect"], inInbox(strings(case["labels"])))
        }
    }

    @Test
    fun sweep() {
        for (case in Fixtures.cases(Fixtures.bundles, "sweep")) {
            val shown = (case["shown"] as List<*>).map {
                val thread = it as Map<*, *>
                SweptThread(thread["id"] as String, thread["pinned"] as Boolean)
            }
            val sweep = bundleSweepIds(shown, strings(case["listed"]), strings(case["pinned"]))
            assertEquals(case["name"] as String, case["expect"], sweep)
        }
    }

    @Test
    fun unpinAll() {
        for (case in Fixtures.cases(Fixtures.bundles, "unpinAll")) {
            val shown = (case["shown"] as List<*>).map {
                val thread = it as Map<*, *>
                SweptThread(thread["id"] as String, thread["pinned"] as Boolean)
            }
            val todosPinned = (case["todosPinned"] as List<*>).map { it as Boolean }
            assertEquals(case["name"] as String, case["expect"], bundleUnpinsAll(shown, todosPinned, case["more"] as Boolean))
        }
    }

    @Test
    fun topUp() {
        for (case in Fixtures.cases(Fixtures.bundles, "topUp")) {
            val bundles = (case["bundles"] as List<*>).map {
                val bundle = it as Map<*, *>
                BundleSupply(
                    labelId = bundle["labelId"] as String,
                    shown = bundle["shown"] as Int,
                    more = bundle["more"] as Boolean,
                    loading = bundle["loading"] as Boolean,
                    failed = bundle["failed"] as Boolean,
                    open = bundle["open"] as Boolean,
                    endShown = bundle["endShown"] as Boolean,
                    afterOpen = bundle["afterOpen"] as Int?,
                )
            }
            assertEquals(case["name"] as String, case["expect"], bundlesToTopUp(bundles))
        }
    }

    @Test
    fun line() {
        for (case in Fixtures.cases(Fixtures.bundles, "line")) {
            assertEquals(case["name"] as String, case["expect"], bundleLine(strings(case["texts"])))
        }
    }

    @Test
    fun counts() {
        for (case in Fixtures.cases(Fixtures.bundles, "counts")) {
            val expected = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                BundleCounts(expected["threads"] as String, expected["unread"] as String?),
                bundleCounts(case["threads"] as Int, case["unread"] as Int, case["more"] as Boolean, case["todos"] as Int? ?: 0),
            )
        }
    }

    @Test
    fun exclusion() {
        for (case in Fixtures.cases(Fixtures.bundles, "exclusion")) {
            val labels = queryLabels(case["labels"])
            val known = case["known"]?.let(::queryLabels) ?: labels
            assertEquals(case["name"] as String, case["expect"], inboxExclusion(labels, known))
        }
    }

    private fun queryLabels(value: Any?): List<QueryLabel> = (value as List<*>).map {
        val label = it as Map<*, *>
        QueryLabel(label["id"] as String, label["name"] as String)
    }

    @Test
    fun merge() {
        for (case in Fixtures.cases(Fixtures.bundles, "merge")) {
            assertEquals(
                case["name"] as String,
                case["expect"],
                mergeFirstPage(strings(case["existing"]), strings(case["page"])),
            )
        }
    }

    @Test
    fun nextPage() {
        for (case in Fixtures.cases(Fixtures.bundles, "nextPage")) {
            val expected = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                InboxPageRequest(expected["q"] as String, expected["pageToken"] as String?),
                inboxNextPage(case["query"] as String, case["pageToken"] as String?, case["tokenQuery"] as String),
            )
        }
    }

    @Test
    fun touched() {
        // History records decode the way Gmail's responses do.
        val raw = GmailJson.parseToJsonElement(Fixtures.text("bundle-fixtures.json")).jsonObject
        for (case in raw.getValue("touched").jsonArray.map { it.jsonObject }) {
            val records = GmailJson.decodeFromJsonElement<List<GmailHistoryRecord>>(case.getValue("records") as JsonArray)
            val labels = GmailJson.decodeFromJsonElement<List<String>>(case.getValue("bundleLabels"))
            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                GmailJson.decodeFromJsonElement<List<String>>(case.getValue("expect")),
                touchedBundles(records, labels),
            )
        }
    }
}
