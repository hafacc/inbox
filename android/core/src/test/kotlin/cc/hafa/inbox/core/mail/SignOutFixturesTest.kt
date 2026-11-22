package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/sign-out-fixtures.json`, as `shared/src/sign-out.spec.ts` does on web. */
class SignOutFixturesTest {
    @Test
    fun warning() {
        for (case in Fixtures.cases(Fixtures.load("sign-out-fixtures.json"), "warning")) {
            val warning = signOutWarning((case["changes"] as Number).toInt(), (case["drafts"] as Number).toInt())
            assertEquals(case["name"] as String, case["expect"], warning)
        }
    }
}
