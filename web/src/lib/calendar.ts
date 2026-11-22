import { LABEL_LOCALE } from "@inbox/shared/format";
/** One cell of a month grid. */
export type CalendarDay = {
  ymd: string;
  day: number;
  inMonth: boolean;
  past: boolean;
  today: boolean;
};

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

/** Local date as `YYYY-MM-DD`. */
export function toYmd(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Local midnight of a `YYYY-MM-DD` date. */
export function parseYmd(ymd: string): Date {
  const [year, month, day] = ymd
    .split("-")
    .map((part) => Number.parseInt(part, 10));
  return new Date(year ?? 1970, (month ?? 1) - 1, day ?? 1);
}

/** Sunday-first weeks covering `month` (0-based) of `year`, padded with neighboring days. */
export function monthGrid(
  year: number,
  month: number,
  today: Date,
): CalendarDay[] {
  const first = new Date(year, month, 1);
  const lead = first.getDay();
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const cells = Math.ceil((lead + daysInMonth) / 7) * 7;
  const todayYmd = toYmd(today);
  const out: CalendarDay[] = [];
  for (let index = 0; index < cells; index++) {
    const date = new Date(year, month, 1 - lead + index);
    const ymd = toYmd(date);
    out.push({
      ymd,
      day: date.getDate(),
      inMonth: date.getMonth() === month,
      past: ymd < todayYmd,
      today: ymd === todayYmd,
    });
  }
  return out;
}

/** `November 2026`. */
export function monthLabel(year: number, month: number): string {
  return new Intl.DateTimeFormat(LABEL_LOCALE, {
    month: "long",
    year: "numeric",
  }).format(new Date(year, month, 1));
}

/** Column headings of a Sunday-first week. */
export const WEEKDAY_SHORT = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];

/** Parses a 24-hour time typed as "9", "930", "09:30" or "21:05" into minutes after midnight. */
export function parseClockInput(raw: string): number | null {
  const match = /^(\d{1,2})(?::?(\d{2}))?$/.exec(raw.trim());
  if (!match) return null;
  const hours = Number.parseInt(match[1]!, 10);
  const minutes = match[2] ? Number.parseInt(match[2], 10) : 0;
  if (hours > 23 || minutes > 59) return null;
  return hours * 60 + minutes;
}

/** Minutes after midnight as `HH:mm`. */
export function formatClockMinutes(minutes: number): string {
  return `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`;
}

/** The first :00/:15/:30/:45 strictly after `now`, rolling onto the next day past 23:45. */
export function nextQuarterHour(now: Date): { ymd: string; minutes: number } {
  const next = new Date(now);
  next.setSeconds(0, 0);
  next.setMinutes(Math.floor(next.getMinutes() / 15) * 15 + 15);
  return {
    ymd: toYmd(next),
    minutes: next.getHours() * 60 + next.getMinutes(),
  };
}
