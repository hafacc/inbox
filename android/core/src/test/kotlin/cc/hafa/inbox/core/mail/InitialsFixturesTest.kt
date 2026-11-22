package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/initials-fixtures.json`, as shared/src/initials.spec.ts does. */
class InitialsFixturesTest {
    @Test
    fun initials() {
        for (case in Fixtures.cases(Fixtures.load("initials-fixtures.json"), "initials")) {
            val name = case["name"] as String
            val raw = case["person"] as Map<*, *>
            val person = Person(raw["name"] as String, raw["email"] as String)
            assertEquals(name, case["expect"], initials(person))
            assertEquals(name, case["single"], initials(person, single = true))
        }
    }
}
