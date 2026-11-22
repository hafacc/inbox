package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Runs `shared/testdata/account-queue-fixtures.json`, as `shared/src/account-queue.spec.ts` does on web. */
class AccountQueueFixturesTest {
    // The fixtures name each change's kind in `kind`, as web stores it.
    private val json = Json(GmailJson) { classDiscriminator = "kind" }
    private val fixtures = json.parseToJsonElement(Fixtures.text("account-queue-fixtures.json")).jsonObject
    private val changes = ListSerializer(AccountChange.serializer())
    private val threadChanges = ListSerializer(LabelChange.serializer())

    private fun cases(section: String): List<JsonObject> = fixtures.getValue(section).jsonArray.map { it.jsonObject }

    private fun <Value> JsonObject.decode(key: String, serializer: KSerializer<Value>): Value =
        json.decodeFromJsonElement(serializer, getValue(key))

    private fun JsonObject.queue(key: String = "queue"): List<AccountChange> = decode(key, changes)

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private val JsonObject.name: String get() = text("name")

    private val JsonObject.seq: Long get() = getValue("seq").jsonPrimitive.long

    @Test
    fun localId() {
        for (case in cases("localId")) assertEquals(case.text("id"), case.getValue("expect").jsonPrimitive.boolean, isLocalId(case.text("id")))
        assertEquals(true, isLocalId(newLocalId()))
    }

    @Test
    fun enqueue() {
        for (case in cases("enqueue")) {
            val added = case.getValue("add").jsonArray.map { change -> numbered(change) }
            val queue = added.fold(case.queue()) { queue, change -> enqueueAccount(queue) { seq -> change.renumbered(seq) } }
            assertEquals(case.name, case.queue("expect"), queue)
        }
    }

    // A change as the fixtures list it before it is numbered.
    private fun numbered(change: JsonElement): AccountChange =
        json.decodeFromJsonElement(AccountChange.serializer(), buildJsonObject {
            put("seq", JsonPrimitive(0))
            for ((key, value) in change.jsonObject) put(key, value)
        })

    @Test
    fun next() {
        for (case in cases("next")) assertEquals(case.name, case.getValue("expect").jsonPrimitive.longOrNull, nextAccountChange(case.queue())?.seq)
    }

    @Test
    fun join() {
        for (case in cases("join")) assertEquals(case.name, case.queue("expect"), joinAccountQueues(case.queue("stored"), case.queue("early")))
    }

    @Test
    fun resolve() {
        for (case in cases("resolve")) {
            assertEquals(case.name, case.queue("expect"), resolveLocalId(case.queue(), case.text("localId"), case.text("gmailId")))
        }
    }

    @Test
    fun without() {
        for (case in cases("without")) assertEquals(case.name, case.queue("expect"), withoutLocalId(case.queue(), case.text("localId")))
    }

    @Test
    fun labelMade() {
        for (case in cases("labelMade")) assertEquals(case.name, case.queue("expect"), labelMade(case.queue(), case.seq, case.text("gmailId")))
    }

    @Test
    fun filterMade() {
        for (case in cases("filterMade")) assertEquals(case.name, case.queue("expect"), filterMade(case.queue(), case.seq, case.text("gmailId")))
    }

    @Test
    fun refused() {
        for (case in cases("refused")) assertEquals(case.name, case.queue("expect"), changeRefused(case.queue(), case.seq))
    }

    @Test
    fun relabel() {
        for (case in cases("relabel")) {
            val before = case.decode("changes", threadChanges)
            val to = case.getValue("to").jsonPrimitive.takeIf { it.isString }?.content
            val relabeled = relabelChanges(before, case.text("from"), to)
            assertEquals(case.name, case.decode("expect", threadChanges), relabeled)
            for (index in case.getValue("same").jsonArray.map { it.jsonPrimitive.int }) assertSame(case.name, before[index], relabeled[index])
        }
    }

    @Test
    fun sendable() {
        for (case in cases("sendable")) {
            val expected = case.getValue("expect").jsonArray.map { it.jsonPrimitive.long }
            assertEquals(case.name, expected, sendableChanges(case.decode("changes", threadChanges)).map { it.seq })
        }
    }

    @Test
    fun labels() {
        val labels = ListSerializer(GmailLabel.serializer())
        for (case in cases("labels")) {
            assertEquals(case.name, case.decode("expect", labels), labelsWithPending(case.decode("labels", labels), case.queue()))
        }
    }

    @Test
    fun filters() {
        val filters = ListSerializer(GmailFilter.serializer())
        for (case in cases("filters")) {
            assertEquals(case.name, case.decode("expect", filters), filtersWithPending(case.decode("filters", filters), case.queue()))
        }
    }

    @Test
    fun unsynced() {
        for (case in cases("unsynced")) {
            val expected = case.getValue("expect").jsonPrimitive.int
            assertEquals(case.name, expected, unsyncedChanges(case.decode("threadChanges", threadChanges), case.queue()))
        }
    }
}
