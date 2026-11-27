package cc.hafa.inbox.core.snooze

import kotlin.math.pow

/** Amounts to add to the shared counts, in their scaled units. */
data class VoteDeltas(val sets: Map<String, Double> = emptyMap(), val tod: Map<String, Double> = emptyMap()) {
    fun negated() = VoteDeltas(sets.mapValues { -it.value }, tod.mapValues { -it.value })
}

/** The learned snooze counts' wire format (`users/{uid}/meta/snoozeStats`); shared with web. */
object SnoozeStatsWire {

    // A user's shared counts are saved scaled up by how late each vote was cast, doubling every half-life from this instant (2026-01-01 UTC), so a vote is a plain addition and no time is saved. Doubles overflow about 59 years on.
    const val VOTE_EPOCH = 1_767_225_600_000L

    // The field of the shared counts doc holding set counts. `sets` holds counts scaled by an earlier half-life rule, which latr's apps still write and nothing here reads.
    const val SHARED_SETS_FIELD = "setsV2"

    private const val MS_PER_DAY = 24.0 * 60 * 60 * 1000

    private fun scaled(stat: SetStat?, halfLifeDays: Long): Double =
        if (stat == null) 0.0 else stat.c * 2.0.pow((stat.t - VOTE_EPOCH) / MS_PER_DAY / halfLifeDays)

    private fun sharedCounters(raw: Any?, validKey: (String) -> Boolean): Map<String, SetStat> =
        entries(raw).mapNotNull { (k, v) ->
            val count = finite(v) ?: return@mapNotNull null
            if (validKey(k) && count > 0) k to SetStat(count, VOTE_EPOCH) else null
        }.toMap()

    /** Each scaled count reads as that count at [VOTE_EPOCH]; counts at or below 0 are dropped. */
    fun sharedFromWire(raw: Map<*, *>?): SnoozeStatsSnapshot {
        if (raw == null) return SnoozeStatsSnapshot()
        return SnoozeStatsSnapshot(
            sets = sharedCounters(raw[SHARED_SETS_FIELD]) { SnoozeSuggestions.isValidSetId(it) },
            tod = sharedCounters(raw["tod"]) { SnoozeSuggestions.SLOT_RE.matches(it) },
            lastCustom = lastCustom(raw["lastCustom"]),
            picks = picks(raw["picks"]),
        )
    }

    private fun counterDeltas(
        before: Map<String, SetStat>,
        after: Map<String, SetStat>,
        halfLifeOf: (String) -> Long,
    ): Map<String, Double> =
        (before.keys + after.keys).mapNotNull { id ->
            if (before[id] == after[id]) return@mapNotNull null
            val halfLife = halfLifeOf(id)
            val delta = scaled(after[id], halfLife) - scaled(before[id], halfLife)
            if (delta != 0.0) id to delta else null
        }.toMap()

    /** What to add to the shared counts to take them from [before] to [after]. */
    fun voteDeltas(before: SnoozeStatsSnapshot, after: SnoozeStatsSnapshot) = VoteDeltas(
        sets = counterDeltas(before.sets, after.sets) { SnoozeSuggestions.setHalfLifeDays(it) },
        tod = counterDeltas(before.tod, after.tod) { SnoozeSuggestions.TOD_HALF_LIFE_DAYS },
    )

    private fun lastCustom(raw: Any?): LastCustom? = (raw as? Map<*, *>)?.let {
        val target = finite(it["target"])
        val at = finite(it["at"])
        if (target != null && at != null) LastCustom(target.toLong(), at.toLong()) else null
    }

    /** `snoozePickLog/global` as 21 counts: index 0 is `none`, 1..20 the ranks; missing or non-integer fields read 0. */
    fun globalPickCounts(raw: Map<*, *>?): List<Long> =
        (0..20).map { position ->
            val count = finite(raw?.get(if (position == 0) "none" else "$position"))
            if (count != null && count % 1.0 == 0.0) count.toLong() else 0L
        }

    /** Positive whole counts under valid pick log keys only. */
    private fun picks(raw: Any?): Map<String, Long> =
        entries(raw).mapNotNull { (k, v) ->
            val count = finite(v) ?: return@mapNotNull null
            if (!SnoozeSuggestions.PICK_RE.matches(k) || count < 1 || count > MAX_SAFE_INTEGER || count % 1.0 != 0.0) {
                null
            } else {
                k to count.toLong()
            }
        }.toMap()

    private fun entries(raw: Any?): List<Pair<String, Any?>> =
        (raw as? Map<*, *>)?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } } ?: emptyList()

    private fun finite(raw: Any?): Double? = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }

    // Matches web's Number.isSafeInteger.
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
}
