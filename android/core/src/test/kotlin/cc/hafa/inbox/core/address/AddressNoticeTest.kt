package cc.hafa.inbox.core.address

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/address-notice-fixtures.json`, so the notice shows when web's does. */
class AddressNoticeTest {
    @Test
    fun fixtures() {
        val fixtures = Fixtures.load("address-notice-fixtures.json")
        for (case in Fixtures.cases(fixtures, "cases")) {
            val setup = case["setup"] as Map<*, *>?
            val trouble = addressTrouble(
                status = setup?.get("status") as String?,
                errorCode = setup?.get("errorCode") as String?,
                updatedAt = (setup?.get("updatedAt") as Number?)?.toLong(),
                now = (case["now"] as Number).toLong(),
            )
            assertEquals("${case["name"]}", case["expect"], trouble?.name?.lowercase())
            val notice = addressNoticeState(
                status = setup?.get("status") as String?,
                errorCode = setup?.get("errorCode") as String?,
                updatedAt = (setup?.get("updatedAt") as Number?)?.toLong(),
                now = (case["now"] as Number).toLong(),
            )
            assertEquals("${case["name"]} notice", case["notice"], notice?.name?.lowercase())
        }
    }
}
