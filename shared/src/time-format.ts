// Dates and sizes as lists, headers and wake times show them, 24-hour, in this device's zone. Android
// :core mail/TimeFormat.kt formats with java.time; both run shared/testdata/time-format-fixtures.json.

import { LABEL_LOCALE } from "./format";

const DAY_MS = 24 * 60 * 60 * 1000;

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** 24-hour `HH:mm` in local time. */
export function formatClock(epoch: number): string {
  const date = new Date(epoch);
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Local midnight starting the day of `date`. */
export function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

/** Whole calendar days from `from`'s day to `to`'s day (negative when `to` is earlier). */
export function dayDistance(from: Date, to: Date): number {
  return Math.round(
    (startOfDay(to).getTime() - startOfDay(from).getTime()) / DAY_MS,
  );
}

const SHORT_DATE = new Intl.DateTimeFormat(LABEL_LOCALE, {
  month: "short",
  day: "numeric",
});
const SHORT_DATE_YEAR = new Intl.DateTimeFormat(LABEL_LOCALE, {
  month: "short",
  day: "numeric",
  year: "numeric",
});
const WEEKDAY_SHORT = new Intl.DateTimeFormat(LABEL_LOCALE, {
  weekday: "short",
});
const WEEKDAY_LONG = new Intl.DateTimeFormat(LABEL_LOCALE, { weekday: "long" });
const LONG_DATE = new Intl.DateTimeFormat(LABEL_LOCALE, {
  weekday: "short",
  month: "short",
  day: "numeric",
});

/** A list row's date: `HH:mm` today, `Oct 28` this year, `Oct 28, 2025` before. */
export function formatListDate(epoch: number, now: number): string {
  const date = new Date(epoch);
  const today = new Date(now);
  if (dayDistance(date, today) === 0) return formatClock(epoch);
  if (date.getFullYear() === today.getFullYear())
    return SHORT_DATE.format(date);
  return SHORT_DATE_YEAR.format(date);
}

/** A message header's date: `Today 08:47`, `Yesterday 08:47`, `Oct 30, 08:47`, `Oct 30, 2025, 08:47`. */
export function formatMessageDate(epoch: number, now: number): string {
  const date = new Date(epoch);
  const distance = dayDistance(date, new Date(now));
  const clock = formatClock(epoch);
  if (distance === 0) return `Today ${clock}`;
  if (distance === 1) return `Yesterday ${clock}`;
  if (date.getFullYear() === new Date(now).getFullYear()) {
    return `${SHORT_DATE.format(date)}, ${clock}`;
  }
  return `${SHORT_DATE_YEAR.format(date)}, ${clock}`;
}

/** A wake time: `20:30` today, `Tue 09:00` within a week, `Nov 19 09:00` later. */
export function formatWakeTime(epoch: number, now: number): string {
  const distance = dayDistance(new Date(now), new Date(epoch));
  const clock = formatClock(epoch);
  if (distance === 0) return clock;
  if (distance > 0 && distance < 7) {
    return `${WEEKDAY_SHORT.format(epoch)} ${clock}`;
  }
  return `${SHORT_DATE.format(epoch)} ${clock}`;
}

/** The amber marker on a thread whose snooze ended: `Snoozed · back at 09:00`. */
export function lapsedMarker(snoozeUntil: number, now: number): string {
  const distance = dayDistance(new Date(snoozeUntil), new Date(now));
  if (distance === 0) return `Snoozed · back at ${formatClock(snoozeUntil)}`;
  return `Snoozed · back ${formatWakeTimePast(snoozeUntil, now)}`;
}

function formatWakeTimePast(epoch: number, now: number): string {
  const distance = dayDistance(new Date(epoch), new Date(now));
  const clock = formatClock(epoch);
  if (distance === 1) return `yesterday at ${clock}`;
  if (distance < 7) return `${WEEKDAY_LONG.format(epoch)} at ${clock}`;
  return `${SHORT_DATE.format(epoch)} at ${clock}`;
}

/** `Sat, Nov 7 at 10:00`, for the picker's summary line. */
export function formatPickedTime(epoch: number): string {
  return `${LONG_DATE.format(epoch)} at ${formatClock(epoch)}`;
}

/** Human file size: `412 B`, `412 KB` (rounded), `1.3 MB` (one decimal). */
export function formatSize(bytes: number): string {
  const kilobytes = Math.round(bytes / 1024);
  if (bytes < 1024) return `${bytes} B`;
  if (kilobytes < 1024) return `${kilobytes} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
