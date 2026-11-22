// Wall-clock times with no zone, as latr stores `snoozeUntil` (latr web/src/lib/utils/todo.ts).
// Each device reads them in its own zone. Pinned by shared/testdata/local-time-fixtures.json.

/** The instant a local `YYYY-MM-DDTHH:mm:ss` names in this device's zone, epoch ms. */
export function isoToEpoch(iso: string): number {
  return new Date(iso).getTime();
}

/** An instant as a local `YYYY-MM-DDTHH:mm:ss` in this device's zone, seconds kept, no zone. */
export function epochToIso(epoch: number): string {
  const date = new Date(epoch);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

const LOCAL_ISO_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})$/;

function daysInMonth(year: number, month: number): number {
  if (month === 2) {
    const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
    return leap ? 29 : 28;
  } else {
    return [4, 6, 9, 11].includes(month) ? 30 : 31;
  }
}

/** Whether a value is a local `YYYY-MM-DDTHH:mm:ss` naming a real date and time of day. */
export function isLocalIso(value: unknown): value is string {
  const match =
    typeof value === "string" ? LOCAL_ISO_PATTERN.exec(value) : null;
  if (match === null) {
    return false;
  } else {
    const [year, month, day, hour, minute, second] = match
      .slice(1)
      .map(Number) as [number, number, number, number, number, number];
    return (
      month >= 1 &&
      month <= 12 &&
      day >= 1 &&
      day <= daysInMonth(year, month) &&
      hour <= 23 &&
      minute <= 59 &&
      second <= 59
    );
  }
}
