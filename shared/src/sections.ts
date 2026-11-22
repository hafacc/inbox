// List section headings, looking back for mail and ahead for wake times. Android :core mail/Sections.kt
// is the same with java.time; both run shared/testdata/section-fixtures.json.

import { LABEL_LOCALE } from "./format";
import { dayDistance, startOfDay } from "./time-format";

/** A heading in a list and the key that identifies it. */
export type SectionHead = {
  /** key that identifies the heading */
  key: string;
  /** heading text */
  title: string;
};

const MONTH_LONG = new Intl.DateTimeFormat(LABEL_LOCALE, { month: "long" });
const MONTH_YEAR = new Intl.DateTimeFormat(LABEL_LOCALE, {
  month: "long",
  year: "numeric",
});
const WEEKDAY_LONG = new Intl.DateTimeFormat(LABEL_LOCALE, { weekday: "long" });

function monthHead(date: Date, now: Date): SectionHead {
  const key = `month:${date.getFullYear()}-${date.getMonth() + 1}`;
  const title =
    date.getFullYear() === now.getFullYear()
      ? MONTH_LONG.format(date)
      : MONTH_YEAR.format(date);
  return { key, title };
}

/** Sunday starting the week of `date`, at local midnight. */
function startOfWeek(date: Date): Date {
  const day = startOfDay(date);
  day.setDate(day.getDate() - day.getDay());
  return day;
}

/**
 * The section a message date falls in, looking back from now: Today, Yesterday, This week
 * (weeks start Sunday), This month, then one section per month for this year and last year
 * ("October", "December 2025"), then one per year before that ("2024"). Future dates count as today.
 */
export function pastSection(epoch: number, now: number): SectionHead {
  const date = new Date(epoch);
  const today = new Date(now);
  const distance = dayDistance(date, today);
  if (distance <= 0) return { key: "today", title: "Today" };
  if (distance === 1) return { key: "yesterday", title: "Yesterday" };
  if (date >= startOfWeek(today)) return { key: "week", title: "This week" };
  if (
    date.getFullYear() === today.getFullYear() &&
    date.getMonth() === today.getMonth()
  ) {
    return { key: "month", title: "This month" };
  }
  if (date.getFullYear() < today.getFullYear() - 1) {
    const year = String(date.getFullYear());
    return { key: `year:${year}`, title: year };
  }
  return monthHead(date, today);
}

/**
 * The section a wake time falls in, looking ahead from now: Today (Tonight from 18:00), Tomorrow,
 * "This Saturday" for the rest of this week, Next week, then one section per month. A time already
 * past is due now, so it goes where now does: Today, or Tonight from 18:00.
 */
export function futureSection(epoch: number, now: number): SectionHead {
  const date = new Date(epoch);
  const today = new Date(now);
  const distance = dayDistance(today, date);
  if (epoch <= now || distance <= 0) {
    return (epoch <= now ? today : date).getHours() >= 18
      ? { key: "tonight", title: "Tonight" }
      : { key: "today", title: "Today" };
  }
  if (distance === 1) return { key: "tomorrow", title: "Tomorrow" };
  const nextWeek = startOfWeek(today);
  nextWeek.setDate(nextWeek.getDate() + 7);
  if (date < nextWeek) {
    const weekday = WEEKDAY_LONG.format(date);
    return { key: `day:${weekday}`, title: `This ${weekday}` };
  }
  const weekAfter = new Date(nextWeek);
  weekAfter.setDate(weekAfter.getDate() + 7);
  if (date < weekAfter) return { key: "next-week", title: "Next week" };
  return monthHead(date, today);
}

/** A run of items under one heading. */
export type Section<Item> = SectionHead & {
  /** the items under it, in order */
  items: Item[];
};

/** Group already-sorted items into consecutive sections, keeping the input order. */
export function sectionize<Item>(
  items: readonly Item[],
  headOf: (item: Item) => SectionHead,
): Section<Item>[] {
  const out: Section<Item>[] = [];
  for (const item of items) {
    const head = headOf(item);
    const last = out.at(-1);
    if (last && last.key === head.key) last.items.push(item);
    else out.push({ ...head, items: [item] });
  }
  return out;
}
