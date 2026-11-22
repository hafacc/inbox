package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Runs `shared/testdata/section-fixtures.json`, so list headings match web's. */
class SectionFixturesTest {
    private fun epoch(local: Any?): Long = LocalDateTime.parse(local as String).atZone(NEW_YORK).toInstant().toEpochMilli()

    private fun check(section: String, head: (Long, Long) -> SectionHead) {
        for (case in Fixtures.cases(Fixtures.sections, section)) {
            assertEquals(
                "$section: ${case["name"] ?: "${case["date"]} at ${case["now"]}"}",
                SectionHead(case["key"] as String, case["title"] as String),
                head(epoch(case["date"]), epoch(case["now"])),
            )
        }
    }

    @Test
    fun past() = check("past") { epoch, now -> pastSection(epoch, now, NEW_YORK) }

    @Test
    fun future() = check("future") { epoch, now -> futureSection(epoch, now, NEW_YORK) }
}
