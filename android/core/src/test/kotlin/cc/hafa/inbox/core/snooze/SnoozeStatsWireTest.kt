package cc.hafa.inbox.core.snooze

import org.junit.Assert.assertEquals
import org.junit.Test

class SnoozeStatsWireTest {

    @Test
    fun `shared counts kept as a device's own are added back to an account unchanged`() {
        val votes = mapOf(
            SnoozeStatsWire.SHARED_SETS_FIELD to mapOf("D0@0900" to 5000.25, "Dom1@0900" to 3.5),
            "tod" to mapOf("0900" to 12.75),
        )
        val kept = SnoozeStatsWire.sharedFromWire(votes)
        val pushed = SnoozeStatsWire.voteDeltas(SnoozeStatsSnapshot(), kept)
        assertEquals(VoteDeltas(sets = mapOf("D0@0900" to 5000.25, "Dom1@0900" to 3.5), tod = mapOf("0900" to 12.75)), pushed)
    }

    @Test
    fun `global pick counts put none first, then ranks, missing as 0`() {
        val counts = SnoozeStatsWire.globalPickCounts(mapOf("none" to 4L, "1" to 7L, "20" to 2L, "key" to "1"))
        assertEquals(21, counts.size)
        assertEquals(listOf(4L, 7L, 0L), counts.take(3))
        assertEquals(2L, counts[20])
        assertEquals(List(21) { 0L }, SnoozeStatsWire.globalPickCounts(null))
    }

    private val zone = java.time.ZoneId.of("America/New_York")
    private val at = java.time.LocalDateTime.of(2026, 9, 22, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    private fun block(): SnoozeStatsSnapshot {
        var stats = SnoozeStatsSnapshot()
        stats = cc.hafa.inbox.core.snooze.SnoozeSuggestions.commit(stats, at, at + day, zone, "custom").next
        stats = cc.hafa.inbox.core.snooze.SnoozeSuggestions.commit(stats, at + day, at + 3 * day, zone, "custom").next
        return cc.hafa.inbox.core.snooze.SnoozeSuggestions.commit(stats, at + 2 * day, at + 40 * day, zone, "custom").next
    }

    @Test
    fun `counts saved scaled rank the same as the block they came from`() {
        val block = block()
        val deltas = SnoozeStatsWire.voteDeltas(SnoozeStatsSnapshot(), block)
        val shared = SnoozeStatsWire.sharedFromWire(mapOf(SnoozeStatsWire.SHARED_SETS_FIELD to deltas.sets, "tod" to deltas.tod))
        val now = at + 30 * day
        fun rows(stats: SnoozeStatsSnapshot) =
            cc.hafa.inbox.core.snooze.SnoozeSuggestions.rankDeep(listOf(stats), java.time.Instant.ofEpochMilli(now), zone).map { Triple(it.key, it.epochMillis, "%.9f".format(it.score)) }
        assertEquals(rows(block), rows(shared))
        assertEquals(false, rows(block).isEmpty())
    }

    @Test
    fun `a vote adds two to the power of half-lives since the epoch`() {
        val one = cc.hafa.inbox.core.snooze.SnoozeSuggestions.commit(SnoozeStatsSnapshot(), at, at + day, zone, "custom").next
        val added = SnoozeStatsWire.voteDeltas(SnoozeStatsSnapshot(), one).sets.values.single()
        // A snooze to the same time tomorrow points one day ahead, so its half-life is 23 days.
        assertEquals(Math.pow(2.0, (at - SnoozeStatsWire.VOTE_EPOCH) / (23.0 * day)), added, 1e-6)
    }

    @Test
    fun `set counts under sets are not read`() {
        assertEquals(emptyMap<String, SetStat>(), SnoozeStatsWire.sharedFromWire(mapOf("sets" to mapOf("D0@0900" to 2.0))).sets)
    }

    @Test
    fun `shared counts at or below zero and malformed ids are dropped`() {
        val shared = SnoozeStatsWire.sharedFromWire(
            mapOf(
                SnoozeStatsWire.SHARED_SETS_FIELD to mapOf("D0@0900" to 2.0, "D0@1000" to 0.0, "D0@1100" to -1.0, "bogus" to 3.0),
                "tod" to mapOf("0900" to 1L, "0901" to 1L),
            ),
        )
        assertEquals(setOf("D0@0900"), shared.sets.keys)
        assertEquals(setOf("0900"), shared.tod.keys)
    }
}
