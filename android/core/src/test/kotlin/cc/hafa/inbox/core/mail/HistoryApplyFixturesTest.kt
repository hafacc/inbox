package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailHistoryRecord
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/history-apply-fixtures.json`, as shared/src/history-apply.spec.ts does. */
class HistoryApplyFixturesTest {
    // History records decode the way Gmail's responses do.
    private val raw = GmailJson.parseToJsonElement(Fixtures.text("history-apply-fixtures.json")).jsonObject

    @Test
    fun apply() {
        for (case in raw.getValue("apply").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val saved = GmailJson.decodeFromJsonElement<Map<String, Map<String, List<String>>>>(case.getValue("saved"))
            val records = GmailJson.decodeFromJsonElement<List<GmailHistoryRecord>>(case.getValue("records"))
            val expected = case.getValue("expect").jsonObject
            val changes = applyHistory(records, saved::get, case.getValue("days").jsonPrimitive.int)
            assertEquals(
                name,
                GmailJson.decodeFromJsonElement<Map<String, Map<String, List<String>>>>(expected.getValue("relabeled")),
                changes.relabeled,
            )
            assertEquals(name, GmailJson.decodeFromJsonElement<Map<String, List<String>>>(expected.getValue("added")), changes.added)
            assertEquals(name, GmailJson.decodeFromJsonElement<List<String>>(expected.getValue("removed")), changes.removed)
            assertEquals(name, GmailJson.decodeFromJsonElement<List<String>>(expected.getValue("fetch")), changes.fetch)
            assertEquals(name, GmailJson.decodeFromJsonElement<List<String>>(expected.getValue("touched")), changes.touched)
            assertEquals(name, GmailJson.decodeFromJsonElement<List<String>>(expected.getValue("check")), changes.check)
        }
    }

    @Test
    fun order() {
        for (case in raw.getValue("order").jsonArray.map { it.jsonObject }) {
            val records = GmailJson.decodeFromJsonElement<List<GmailHistoryRecord>>(case.getValue("records"))
            val fetch = GmailJson.decodeFromJsonElement<List<String>>(case.getValue("fetch"))
            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                GmailJson.decodeFromJsonElement<List<String>>(case.getValue("expect")),
                readOrder(records, fetch),
            )
        }
    }

    @Test
    fun labels() {
        for (case in raw.getValue("labels").jsonArray.map { it.jsonObject }) {
            assertEquals(
                case.getValue("name").jsonPrimitive.content,
                GmailJson.decodeFromJsonElement<List<String>>(case.getValue("expect")),
                threadLabels(GmailJson.decodeFromJsonElement<Map<String, List<String>>>(case.getValue("messages"))),
            )
        }
    }
}
