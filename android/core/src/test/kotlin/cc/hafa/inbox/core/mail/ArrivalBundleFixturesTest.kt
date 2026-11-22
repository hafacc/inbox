package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the `wake` cases of `shared/testdata/arrival-bundle-fixtures.json`, as web's bundles.spec.ts does. */
class ArrivalBundleFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    @Test
    fun wake() {
        for (case in Fixtures.cases(Fixtures.load("arrival-bundle-fixtures.json"), "wake")) {
            assertEquals(
                case["name"] as String,
                case["expect"],
                bundledOnWake(strings(case["labels"]), case["pinned"] as Boolean, strings(case["bundleLabels"])),
            )
        }
    }
}
