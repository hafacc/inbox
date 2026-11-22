package cc.hafa.inbox.core.mail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

private val fixtureJson = Json { ignoreUnknownKeys = true }

/** Runs shared/testdata/local-time-fixtures.json, as shared/src/local-time.spec.ts does. */
class LocalTimeTest {
    @Serializable
    private data class Conversion(val name: String, val epoch: Long, val iso: String)

    @Serializable
    private data class Validity(val value: String, val valid: Boolean)

    @Serializable
    private data class LocalTimeFixtures(
        val zone: String,
        val toIso: List<Conversion>,
        val toEpoch: List<Conversion>,
        val isLocalIso: List<Validity>,
    )

    private val fixtures: LocalTimeFixtures by lazy {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("local-time-fixtures.json")) {
            "testdata/local-time-fixtures.json missing from test resources"
        }.bufferedReader().use { it.readText() }
        fixtureJson.decodeFromString<LocalTimeFixtures>(text)
    }

    @Test
    fun `instants become wall times`() {
        val zone = ZoneId.of(fixtures.zone)
        for (case in fixtures.toIso) assertEquals(case.name, case.iso, epochToIso(case.epoch, zone))
    }

    @Test
    fun `wall times become instants`() {
        val zone = ZoneId.of(fixtures.zone)
        for (case in fixtures.toEpoch) assertEquals(case.name, case.epoch, isoToEpoch(case.iso, zone))
    }

    @Test
    fun `only full local times count`() {
        for (case in fixtures.isLocalIso) assertEquals(case.value, case.valid, isLocalIso(case.value))
        assertEquals(false, isLocalIso(null))
    }
}
