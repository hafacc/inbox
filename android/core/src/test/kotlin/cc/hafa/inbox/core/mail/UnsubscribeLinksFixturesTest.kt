package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/unsubscribe-fixtures.json` and `link-fixtures.json`, as web does. */
class UnsubscribeLinksFixturesTest {
    private fun mail(raw: Any?): UnsubscribeMail? {
        val map = raw as? Map<*, *> ?: return null
        return UnsubscribeMail(map["address"] as String, map["subject"] as String, map["body"] as String)
    }

    @Test
    fun unsubscribe() {
        for (case in Fixtures.cases(Fixtures.unsubscribe, "parse")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                Unsubscribe(expect["oneClickUrl"] as String?, mail(expect["mailto"]), expect["url"] as String?, UnsubscribeMethod.entries.firstOrNull { it.wire == expect["preferred"] }),
                parseUnsubscribe(case["listUnsubscribe"] as String?, case["listUnsubscribePost"] as String?),
            )
        }
    }

    @Test
    fun unwrap() {
        for (case in Fixtures.cases(Fixtures.links, "unwrap")) {
            assertEquals(case["name"] as String, case["expect"], unwrapLink(case["link"] as String))
        }
    }
}
