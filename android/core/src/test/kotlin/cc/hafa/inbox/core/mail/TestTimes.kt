package cc.hafa.inbox.core.mail

import java.time.LocalDateTime
import java.time.ZoneId

/** The zone web's specs run in (shared/test-setup.ts). */
val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/** Epoch milliseconds of a New York wall-clock time; [month] is 1-based. */
fun at(year: Int, month: Int, day: Int, hour: Int = 9, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).atZone(NEW_YORK).toInstant().toEpochMilli()

/** Monday, November 2, 2026, 11:50 in New York, the time web's specs use. */
val NOW: Long = at(2026, 11, 2, 11, 50)

/** One hour in milliseconds. */
const val HOUR = 60L * 60 * 1000
