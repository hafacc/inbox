package cc.hafa.inbox.core.filters

import cc.hafa.inbox.core.label.LabelDelta
import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/filter-fixtures.json`, so Android's filter editor matches web's. */
class FilterFixturesTest {
    private val json = Json { explicitNulls = false }

    private fun cases(section: String) = Fixtures.cases(Fixtures.filters, section)

    private fun criteria(raw: Any?): GmailFilterCriteria =
        json.decodeFromString(GmailFilterCriteria.serializer(), JSONObject(raw as Map<*, *>).toString())

    private fun action(raw: Any?): GmailFilterAction =
        json.decodeFromString(GmailFilterAction.serializer(), JSONObject(raw as Map<*, *>).toString())

    private fun row(raw: Any?): FilterRow {
        val map = raw as Map<*, *>
        return FilterRow(
            FilterField.entries.first { it.wire == map["field"] },
            FilterOperator.entries.first { it.wire == map["operator"] },
            map["value"] as String,
        )
    }

    private fun conditions(raw: Any?): FilterConditions {
        val map = raw as Map<*, *>
        return if (map["kind"] == "raw") {
            FilterConditions.Raw(map["query"] as String)
        } else {
            FilterConditions.Rows(
                (map["groups"] as List<*>).map { group ->
                    val groupMap = group as Map<*, *>
                    ConditionGroup(GroupMatch.entries.first { it.wire == groupMap["match"] }, (groupMap["rows"] as List<*>).map(::row))
                },
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun actions(raw: Any?): FilterActions {
        val map = raw as Map<*, *>
        return FilterActions(
            labelId = map["labelId"] as String?,
            archive = map["archive"] as Boolean,
            markRead = map["markRead"] as Boolean,
            star = map["star"] as Boolean,
            neverSpam = map["neverSpam"] as Boolean,
            important = Importance.entries.firstOrNull { it.wire == map["important"] },
            trash = map["trash"] as Boolean,
            forward = map["forward"] as String?,
            otherAddLabelIds = map["otherAddLabelIds"] as List<String>,
            otherRemoveLabelIds = map["otherRemoveLabelIds"] as List<String>,
        )
    }

    @Test
    fun rowError() {
        for (case in cases("rowError")) assertEquals(case["expect"], rowError(row(case["row"])))
    }

    @Test
    fun size() {
        for (case in cases("size")) assertEquals(case["value"] as String, (case["bytes"] as Number?)?.toInt(), parseSize(case["value"] as String))
    }

    @Test
    fun build() {
        for (case in cases("build")) {
            val built = conditionsToCriteria(conditions(case["conditions"]))
            assertEquals(case["name"] as String, criteria(case["expect"]), built)
            assertEquals(
                case["name"] as String,
                JSONObject(case["expect"] as Map<*, *>).keySet(),
                JSONObject(json.encodeToString(GmailFilterCriteria.serializer(), built)).keySet(),
            )
        }
    }

    @Test
    fun parse() {
        for (case in cases("parse")) {
            assertEquals(case["name"] as String, conditions(case["expect"]), criteriaToConditions(criteria(case["criteria"])))
        }
    }

    @Test
    fun search() {
        for (case in cases("search")) assertEquals(case["expect"], criteriaToSearch(criteria(case["criteria"])))
    }

    @Test
    fun whole() {
        for (case in cases("whole")) {
            assertEquals(case["name"] as String, case["expect"], filterIsWhole(FilterEditor(conditions(case["conditions"]), actions(case["actions"]))))
        }
    }

    @Test
    fun actions() {
        for (case in cases("actions")) {
            assertEquals(case["name"] as String, actions(case["actions"]), actionFromGmail(action(case["gmail"])))
            assertEquals(case["name"] as String, action(case["toGmail"]), actionToGmail(actions(case["actions"])))
            val existing = case["existing"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                LabelDelta((existing["add"] as List<*>).map { it as String }, (existing["remove"] as List<*>).map { it as String }),
                labelChanges(actions(case["actions"])),
            )
        }
    }

    @Test
    fun builtFiltersLoadBackAsRows() {
        for (case in cases("build")) {
            val loaded = criteriaToConditions(conditionsToCriteria(conditions(case["conditions"])))
            assertEquals(case["name"] as String, true, loaded is FilterConditions.Rows)
            assertEquals(case["name"] as String, loaded, criteriaToConditions(conditionsToCriteria(loaded)))
        }
    }

    @Test
    fun wholeFilterRoundTrips() {
        val filter = NewFilter(
            GmailFilterCriteria(from = "news@example.com", query = "weekly"),
            GmailFilterAction(addLabelIds = listOf("Label_1"), removeLabelIds = listOf("INBOX")),
        )
        assertEquals(filter, editorToFilter(filterToEditor(GmailFilter(null, filter.criteria, filter.action))))
    }
}
