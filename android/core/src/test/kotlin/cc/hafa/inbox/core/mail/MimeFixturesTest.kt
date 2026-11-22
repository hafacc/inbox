package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.gmail.GmailMessagePart
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/mime-fixtures.json`, as shared/src/mime.spec.ts does. */
class MimeFixturesTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val fixtures = json.parseToJsonElement(Fixtures.text("mime-fixtures.json")).jsonObject

    private fun cases(section: String): List<JsonObject> = fixtures.getValue(section).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.nullableString(key: String): String? = getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun charset() {
        for (case in cases("charset")) {
            assertEquals(case.string("name"), case.string("text"), decodeBase64UrlText(case.string("data"), case.string("charset")))
        }
    }

    @Test
    fun contentType() {
        for (case in cases("contentType")) {
            assertEquals(case.string("name"), case.string("charset"), charsetOf(case.nullableString("contentType")))
        }
    }

    @Test
    fun body() {
        for (case in cases("body")) {
            val payload = json.decodeFromJsonElement(GmailMessagePart.serializer(), case.getValue("payload"))
            val actual = json.encodeToJsonElement(MessageBody.serializer(), extractBody(payload))
            assertEquals(case.string("name"), case.getValue("expect"), actual)
        }
    }

    @Test
    fun address() {
        for (case in cases("address")) {
            val expected = case.getValue("expect").jsonArray.map { Person(it.jsonObject.string("name"), it.jsonObject.string("email")) }
            assertEquals(case.string("name"), expected, parseAddressList(case.string("value")))
        }
    }

    @Test
    fun entities() {
        for (case in cases("entities")) {
            assertEquals(case.string("name"), case.string("expect"), decodeEntities(case.string("value")))
        }
    }
}
