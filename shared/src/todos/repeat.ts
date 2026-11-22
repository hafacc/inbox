// Ported from latr's web/src/lib/utils/repeat.ts; Android :core todo/Repeat.kt is the same rule
// engine. Both run shared/testdata/repeat-fixtures.json.

/** How often a repeating todo comes back. */
export type RepeatUnit = "day" | "week" | "month";

/** A repeating todo's rule; times are local wall-clock, like `snoozeUntil`. */
export type Repeat = {
  /** 1..7: every N days, weeks or months */
  every: number;
  /** what N counts */
  unit: RepeatUnit;
  /** ISO weekday (Monday = 1); used by "week" */
  weekday: number;
  /** 1..31, or {@link LAST_DAY}; used by "month" */
  monthDay: number;
  /** `HH:mm` */
  at: string;
  /** `YYYY-MM-DD` the every-N count starts from: the first occurrence when the rule was saved */
  anchor: string;
};

/** A rule as the editor makes it, before it has an anchor. */
export type RepeatDraft = Omit<Repeat, "anchor">;

/** {@link Repeat.monthDay} for the last day of each month. */
export const LAST_DAY = -1;

const UNITS: readonly RepeatUnit[] = ["day", "week", "month"];
const WEEKDAY_NAMES = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];
const DAY_MS = 86_400_000;
// Longest gap between two occurrences is 7 months; a date search past this has gone wrong.
const SEARCH_DAYS = 400;

function isInt(value: unknown, min: number, max: number): value is number {
  return (
    typeof value === "number" &&
    Number.isInteger(value) &&
    value >= min &&
    value <= max
  );
}

function isUnit(value: unknown): value is RepeatUnit {
  return UNITS.includes(value as RepeatUnit);
}

function isClock(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{2}:\d{2}$/.test(value)) return false;
  return Number(value.slice(0, 2)) < 24 && Number(value.slice(3)) < 60;
}

function ymd(date: string): [number, number, number] {
  return [
    Number(date.slice(0, 4)),
    Number(date.slice(5, 7)),
    Number(date.slice(8, 10)),
  ];
}

function isDate(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const [year, month, day] = ymd(value);
  return (
    month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month)
  );
}

/** A saved rule, or null when it is missing or malformed. */
export function readRepeat(value: unknown): Repeat | null {
  if (typeof value !== "object" || value === null) return null;
  const { every, unit, weekday, monthDay, at, anchor } = value as Record<
    string,
    unknown
  >;
  if (
    !isInt(every, 1, 7) ||
    !isUnit(unit) ||
    !isInt(weekday, 1, 7) ||
    !(isInt(monthDay, 1, 31) || monthDay === LAST_DAY) ||
    !isClock(at) ||
    !isDate(anchor)
  ) {
    return null;
  }
  return { every, unit, weekday, monthDay, at, anchor };
}

function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function dayNumber(date: string): number {
  const [year, month, day] = ymd(date);
  return Math.floor(Date.UTC(year, month - 1, day) / DAY_MS);
}

function fromDayNumber(dayNum: number): string {
  return new Date(dayNum * DAY_MS).toISOString().slice(0, 10);
}

// ISO weekday of a day number; day 0 (1970-01-01) was a Thursday.
function isoWeekday(dayNum: number): number {
  return mod(dayNum + 3, 7) + 1;
}

function mod(value: number, divisor: number): number {
  return ((value % divisor) + divisor) % divisor;
}

function matches(repeat: Repeat, dayNum: number): boolean {
  const anchorNum = dayNumber(repeat.anchor);
  if (repeat.unit === "day") {
    return mod(dayNum - anchorNum, repeat.every) === 0;
  } else if (repeat.unit === "week") {
    const monday = dayNum - (isoWeekday(dayNum) - 1);
    const anchorMonday = anchorNum - (isoWeekday(anchorNum) - 1);
    return (
      isoWeekday(dayNum) === repeat.weekday &&
      mod((monday - anchorMonday) / 7, repeat.every) === 0
    );
  } else {
    const [year, month, day] = ymd(fromDayNumber(dayNum));
    const [anchorYear, anchorMonth] = ymd(repeat.anchor);
    const length = daysInMonth(year, month);
    const target =
      repeat.monthDay === LAST_DAY ? length : Math.min(repeat.monthDay, length);
    return (
      day === target &&
      mod(year * 12 + month - (anchorYear * 12 + anchorMonth), repeat.every) ===
        0
    );
  }
}

/** The first occurrence strictly after `after`, both local `YYYY-MM-DDTHH:mm:ss`. */
export function nextOccurrence(repeat: Repeat, after: string): string {
  const start = dayNumber(after.slice(0, 10));
  for (let dayNum = start; dayNum < start + SEARCH_DAYS; dayNum++) {
    if (!matches(repeat, dayNum)) continue;
    const occurrence = `${fromDayNumber(dayNum)}T${repeat.at}:00`;
    if (occurrence > after) return occurrence;
  }
  throw new Error(`no occurrence of ${JSON.stringify(repeat)} after ${after}`);
}

/** The rule saved at `now` (local `YYYY-MM-DDTHH:mm:ss`), anchored on its first occurrence. */
export function anchorRepeat(draft: RepeatDraft, now: string): Repeat {
  const first = nextOccurrence(
    { ...draft, every: 1, anchor: now.slice(0, 10) },
    now,
  );
  return { ...draft, anchor: first.slice(0, 10) };
}

function ordinal(day: number): string {
  const tens = day % 100;
  if (tens >= 11 && tens <= 13) return `${day}th`;
  else if (day % 10 === 1) return `${day}st`;
  else if (day % 10 === 2) return `${day}nd`;
  else if (day % 10 === 3) return `${day}rd`;
  else return `${day}th`;
}

/** The repeating row's second line, e.g. "Repeats every Monday at 07:00". */
export function repeatText(repeat: Repeat): string {
  const plural = repeat.every > 1;
  let when: string;
  if (repeat.unit === "day") {
    when = plural ? `every ${repeat.every} days` : "every day";
  } else if (repeat.unit === "week") {
    const name = WEEKDAY_NAMES[repeat.weekday - 1];
    when = plural ? `every ${repeat.every} weeks on ${name}` : `every ${name}`;
  } else {
    const day =
      repeat.monthDay === LAST_DAY
        ? "the last day"
        : `the ${ordinal(repeat.monthDay)}`;
    when = plural
      ? `every ${repeat.every} months on ${day}`
      : `every month on ${day}`;
  }
  return `Repeats ${when} at ${repeat.at}`;
}
