package cc.hafa.inbox.core.address

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `shared/testdata/address-name-fixtures.json`, so names and refusals match web and the server. */
class AddressNameTest {
    @Test
    fun checks() {
        val fixtures = Fixtures.load("address-name-fixtures.json")
        for (case in Fixtures.cases(fixtures, "checks")) {
            val expected = case["expect"] as Map<*, *>
            val input = case["input"] as String
            val problem = addressNameProblem(input)
            if (expected["ok"] == true) {
                assertEquals("${case["name"]}", null, problem)
                assertEquals("${case["name"]}", expected["name"], normalizeAddressName(input))
            } else {
                assertEquals("${case["name"]}", expected["problem"], problem)
            }
        }
    }

    @Test
    fun refusals() {
        val fixtures = Fixtures.load("address-name-fixtures.json")
        for (case in Fixtures.cases(fixtures, "refusals")) {
            val reason = case["reason"] as String?
            assertEquals("$reason", case["text"], addressRefusalText(reason, case["address"] as String?))
        }
    }

    @Test
    fun readsTheServersReservedList() {
        assertTrue(ReservedAddressNames.names.size > 90)
        for (name in ReservedAddressNames.names) {
            assertEquals(name, "reserved", addressNameProblem(name))
        }
    }
}
