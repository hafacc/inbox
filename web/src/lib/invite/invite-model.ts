// The invite card: an iCalendar event in the device's zone, 24-hour, with conflicts from the
// user's Google Calendar, and how an RSVP goes (Calendar API or an iCalendar REPLY by mail).
import type {
  Attendee,
  CalendarEvent,
  EventTime,
  ReplyPartstat,
} from "@inbox/shared/calendar";
import { LABEL_LOCALE } from "@inbox/shared/format";
import type {
  CalendarApiAttendee,
  CalendarApiEvent,
  CalendarApiTime,
} from "../gmail/calendar-client";

/** The answers the card offers, with their button text. */
export const RSVP_CHOICES: readonly {
  partstat: ReplyPartstat;
  text: string;
}[] = [
  { partstat: "ACCEPTED", text: "Yes" },
  { partstat: "TENTATIVE", text: "Maybe" },
  { partstat: "DECLINED", text: "No" },
];

const RESPONSE_STATUS: Record<
  ReplyPartstat,
  CalendarApiAttendee["responseStatus"]
> = {
  ACCEPTED: "accepted",
  TENTATIVE: "tentative",
  DECLINED: "declined",
};

/** An iCalendar PARTSTAT for a Calendar API response, or null for no answer yet. */
export function partstatOf(
  response: CalendarApiAttendee["responseStatus"] | undefined,
): ReplyPartstat | null {
  switch (response) {
    case "accepted":
      return "ACCEPTED";
    case "tentative":
      return "TENTATIVE";
    case "declined":
      return "DECLINED";
    default:
      return null;
  }
}

/** How a PARTSTAT reads after a name: "accepted", "might come"… */
export function partstatText(partstat: string): string {
  switch (partstat.toUpperCase()) {
    case "ACCEPTED":
      return "Going";
    case "TENTATIVE":
      return "Maybe";
    case "DECLINED":
      return "Not going";
    case "DELEGATED":
      return "Delegated";
    default:
      return "Not answered";
  }
}

/** Epoch ms of an event time; all-day dates are local midnight in `timeZone`. */
export function epochOf(time: EventTime, timeZone: string): number {
  if (time.kind === "dateTime") return Date.parse(time.utc);
  const [year, month, day] = time.date.split("-").map(Number);
  // Local midnight in the zone: start from UTC midnight and correct by the zone's offset there.
  const utcMidnight = Date.UTC(year!, month! - 1, day!);
  const offset = zoneOffset(utcMidnight, timeZone);
  return utcMidnight - offset;
}

function zoneOffset(epoch: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat(LABEL_LOCALE, {
    timeZone,
    hourCycle: "h23",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(epoch);
  const get = (type: string) =>
    Number(parts.find((part) => part.type === type)?.value ?? 0);
  const asUtc = Date.UTC(
    get("year"),
    get("month") - 1,
    get("day"),
    get("hour"),
    get("minute"),
    get("second"),
  );
  return asUtc - epoch;
}

function dayText(epoch: number, timeZone: string): string {
  return new Intl.DateTimeFormat(LABEL_LOCALE, {
    timeZone,
    weekday: "short",
    month: "short",
    day: "numeric",
  }).format(epoch);
}

function clockText(epoch: number, timeZone: string): string {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone,
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(epoch);
}

function ymdIn(epoch: number, timeZone: string): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(epoch);
}

/** What the card's date tile and time line show. */
export type InviteWhen = {
  /** `Nov` */
  month: string;
  /** `5` */
  day: string;
  /** `Thu, Nov 5 · 10:00–10:30`, `Thu, Nov 5 – Sat, Nov 7` (all-day) */
  text: string;
};

/** When an event happens, in `timeZone`, 24-hour. */
export function inviteWhen(event: CalendarEvent, timeZone: string): InviteWhen {
  const start = epochOf(event.start, timeZone);
  const tileZone = event.start.kind === "date" ? "UTC" : timeZone;
  const tileEpoch =
    event.start.kind === "date"
      ? Date.parse(`${event.start.date}T00:00:00Z`)
      : start;
  const month = new Intl.DateTimeFormat(LABEL_LOCALE, {
    timeZone: tileZone,
    month: "short",
  }).format(tileEpoch);
  const day = new Intl.DateTimeFormat(LABEL_LOCALE, {
    timeZone: tileZone,
    day: "numeric",
  }).format(tileEpoch);
  if (event.start.kind === "date") {
    const first = dayText(tileEpoch, "UTC");
    const endDate =
      event.end.kind === "date" ? event.end.date : event.start.date;
    const lastEpoch = Date.parse(`${endDate}T00:00:00Z`) - 24 * 60 * 60 * 1000;
    const text =
      lastEpoch > tileEpoch ? `${first} – ${dayText(lastEpoch, "UTC")}` : first;
    return { month, day, text };
  }
  const end = epochOf(event.end, timeZone);
  const sameDay = ymdIn(start, timeZone) === ymdIn(end, timeZone);
  const text = sameDay
    ? `${dayText(start, timeZone)} · ${clockText(start, timeZone)}–${clockText(end, timeZone)}`
    : `${dayText(start, timeZone)} ${clockText(start, timeZone)} – ${dayText(end, timeZone)} ${clockText(end, timeZone)}`;
  return { month, day, text };
}

/** Where it happens: the location, else "Google Meet" or "Zoom" when the description links one. */
export function inviteWhere(event: CalendarEvent): string {
  const location = event.location.trim();
  if (location !== "") return location;
  if (/meet\.google\.com\//i.test(event.description)) return "Google Meet";
  if (/zoom\.us\//i.test(event.description)) return "Zoom";
  return "";
}

/** A Calendar API time as epoch ms (all-day: local midnight in `timeZone`). */
export function apiEpoch(
  time: CalendarApiTime | undefined,
  timeZone: string,
): number | null {
  if (time?.dateTime) return Date.parse(time.dateTime);
  if (time?.date) return epochOf({ kind: "date", date: time.date }, timeZone);
  return null;
}

/** An event of the user's that overlaps the invite. */
export type Conflict = { summary: string; start: number; allDay: boolean };

/**
 * Busy events overlapping `[start, end)`: not the invite itself, not canceled, not marked free,
 * and not declined by the user.
 */
export function conflicts(
  events: readonly CalendarApiEvent[],
  iCalUID: string,
  start: number,
  end: number,
  timeZone: string,
): Conflict[] {
  const out: Conflict[] = [];
  for (const event of events) {
    if (event.iCalUID === iCalUID || event.status === "cancelled") continue;
    if (event.transparency === "transparent") continue;
    const self = event.attendees?.find((attendee) => attendee.self);
    if (self?.responseStatus === "declined") continue;
    const from = apiEpoch(event.start, timeZone);
    const to = apiEpoch(event.end, timeZone);
    if (from === null || to === null || from >= end || to <= start) continue;
    out.push({
      summary: event.summary?.trim() || "Busy",
      start: from,
      allDay: event.start?.date !== undefined,
    });
  }
  return out;
}

/** The conflict line: `Overlaps ‘Dentist’ 10:15`, or how many events it overlaps. */
export function conflictText(
  found: readonly Conflict[],
  timeZone: string,
): string | null {
  if (found.length === 0) return null;
  if (found.length > 1) return `Overlaps ${found.length} events`;
  const only = found[0]!;
  return only.allDay
    ? `Overlaps ‘${only.summary}’`
    : `Overlaps ‘${only.summary}’ ${clockText(only.start, timeZone)}`;
}

/** The originalStart the Calendar API takes for an occurrence's RECURRENCE-ID. */
export function originalStart(recurrenceId: EventTime): string {
  return recurrenceId.kind === "date" ? recurrenceId.date : recurrenceId.utc;
}

/**
 * The attendee list with the user's answer, for `events.patch`: the entry marked `self`, else the
 * first with one of `ownAddresses`. Null when the user isn't on the list (invited through a
 * group), so the answer goes by mail instead.
 */
export function answeredAttendees(
  attendees: readonly CalendarApiAttendee[],
  ownAddresses: readonly string[],
  partstat: ReplyPartstat,
): CalendarApiAttendee[] | null {
  const own = new Set(ownAddresses.map((address) => address.toLowerCase()));
  let index = attendees.findIndex((attendee) => attendee.self);
  if (index === -1) {
    index = attendees.findIndex((attendee) =>
      own.has((attendee.email ?? "").toLowerCase()),
    );
  }
  if (index === -1) return null;
  return attendees.map((attendee, at) =>
    at === index
      ? { ...attendee, responseStatus: RESPONSE_STATUS[partstat] }
      : attendee,
  );
}

/** The user's current answer: from Google Calendar when the event is there, else from the invite. */
export function currentAnswer(
  calendarEvent: CalendarApiEvent | null,
  attendee: Attendee | null,
): string | null {
  if (calendarEvent) {
    const self = calendarEvent.attendees?.find((entry) => entry.self);
    const partstat = partstatOf(self?.responseStatus);
    if (partstat !== null) return partstat;
  }
  const fromInvite = attendee?.partstat.toUpperCase() ?? null;
  return fromInvite === "NEEDS-ACTION" ? null : fromInvite;
}
