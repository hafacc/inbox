// Calendar invites: text/calendar (iCalendar, RFC 5545) read into a card, and the iTIP REPLY
// (RFC 5546) an RSVP by mail sends. Parsing uses ical.js (pure JS, browser and bun); Android uses
// biweekly (see docs/shared-contract.md). Fixtures: calendar-fixtures.json.

import ICAL from "ical.js";
import {
  addressKey,
  InvalidAddressError,
  isPlainAddress,
  type Mailbox,
} from "./compose/address";
import { CRLF, utf8Length } from "./compose/encoding";
import { dateTimeFormat, MONTH_ABBREVIATIONS, zeroPad } from "./format";

/** When an event starts or ends: a whole day (all-day events), or an instant. */
export type EventTime =
  | {
      kind: "date";
      /** `yyyy-mm-dd`; an all-day end is the day after the last day */
      date: string;
    }
  | {
      kind: "dateTime";
      /** ISO 8601 in UTC, `2026-11-10T17:00:00Z` */
      utc: string;
      /** IANA zone it was written in (`UTC` for `Z` times), or null when unknown or floating */
      timeZone: string | null;
    };

/** Someone named in an invite. */
export type CalendarPerson = {
  /** address, without `mailto:` */
  email: string;
  /** `CN`, or empty when missing or just the address */
  name: string;
};

/** An invited person, room or group. */
export type Attendee = CalendarPerson & {
  /** `PARTSTAT`: NEEDS-ACTION (default), ACCEPTED, DECLINED, TENTATIVE, DELEGATED… */
  partstat: string;
  /** `ROLE`: REQ-PARTICIPANT (default), OPT-PARTICIPANT, CHAIR, NON-PARTICIPANT */
  role: string;
  /** `CUTYPE`: INDIVIDUAL (default), ROOM, RESOURCE, GROUP… */
  kind: string;
  /** `RSVP=TRUE`: a reply is asked for */
  rsvp: boolean;
};

/** One VEVENT. */
export type CalendarEvent = {
  /** `UID` */
  uid: string;
  /** `SEQUENCE`, 0 when missing */
  sequence: number;
  /** the occurrence a changed instance replaces, or null for the event itself */
  recurrenceId: EventTime | null;
  /** `STATUS` (CONFIRMED, TENTATIVE, CANCELLED), or null */
  status: string | null;
  /** `SUMMARY`, the title; empty when missing */
  summary: string;
  /** `LOCATION`; empty when missing */
  location: string;
  /** `DESCRIPTION`; empty when missing */
  description: string;
  /** null when missing, or when neither its EMAIL nor its value is a plain address */
  organizer: CalendarPerson | null;
  /** those with a plain address (EMAIL, else the value), in order */
  attendees: Attendee[];
  /** from DTSTART */
  start: EventTime;
  /** from DTEND, else DTSTART + DURATION, else the next day (all-day) or the start */
  end: EventTime;
  /** the repeat rule in English (`Weekly on Tuesday`), or null when it doesn't repeat */
  recurrence: string | null;
};

/** A text/calendar part. */
export type CalendarInvite = {
  /** `METHOD` upper-case (REQUEST, CANCEL, REPLY, PUBLISH…), or null */
  method: string | null;
  /** its events, in order */
  events: CalendarEvent[];
};

/** An RSVP answer. */
export type ReplyPartstat = "ACCEPTED" | "TENTATIVE" | "DECLINED";

/** `PRODID` written into replies. */
export const PRODID = "-//hafa.cc//Inbox//EN";

/** Windows zone names (Outlook's TZIDs) for the zones most invites use. */
export const WINDOWS_ZONES: Readonly<Record<string, string>> = {
  "Eastern Standard Time": "America/New_York",
  "Central Standard Time": "America/Chicago",
  "Mountain Standard Time": "America/Denver",
  "US Mountain Standard Time": "America/Phoenix",
  "Pacific Standard Time": "America/Los_Angeles",
  "Alaskan Standard Time": "America/Anchorage",
  "Hawaiian Standard Time": "Pacific/Honolulu",
  "Atlantic Standard Time": "America/Halifax",
  "GMT Standard Time": "Europe/London",
  "Greenwich Standard Time": "Atlantic/Reykjavik",
  "W. Europe Standard Time": "Europe/Berlin",
  "Romance Standard Time": "Europe/Paris",
  "Central Europe Standard Time": "Europe/Budapest",
  "Central European Standard Time": "Europe/Warsaw",
  "FLE Standard Time": "Europe/Kyiv",
  "India Standard Time": "Asia/Kolkata",
  "China Standard Time": "Asia/Shanghai",
  "Singapore Standard Time": "Asia/Singapore",
  "Tokyo Standard Time": "Asia/Tokyo",
  "AUS Eastern Standard Time": "Australia/Sydney",
  UTC: "UTC",
};

function isIanaZone(name: string): boolean {
  if (!/^[A-Za-z_]+(\/[A-Za-z0-9_+-]+)+$|^UTC$/.test(name)) return false;
  try {
    dateTimeFormat({ timeZone: name });
    return true;
  } catch {
    return false;
  }
}

/** The IANA zone a `TZID` names (itself, or a Windows name), or null. */
export function ianaZone(tzid: string): string | null {
  const trimmed = tzid.trim();
  if (isIanaZone(trimmed)) return trimmed;
  return WINDOWS_ZONES[trimmed] ?? null;
}

/** Offset of `zone` from UTC at `epochMs`, in ms. */
function zoneOffset(epochMs: number, zone: string): number {
  const parts = dateTimeFormat({
    timeZone: zone,
    hourCycle: "h23",
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
  }).formatToParts(new Date(epochMs));
  const part = (type: string) =>
    Number(parts.find((candidate) => candidate.type === type)!.value);
  const wall = Date.UTC(
    part("year"),
    part("month") - 1,
    part("day"),
    part("hour"),
    part("minute"),
    part("second"),
  );
  return wall - Math.floor(epochMs / 1000) * 1000;
}

/**
 * A wall-clock time in `zone` as epoch ms, as java.time's `ZonedDateTime.of` does: a time the
 * clock repeats takes the earlier instant, one it skips moves forward by the gap.
 */
function wallToEpoch(time: ICAL.Time, zone: string): number {
  const wall = Date.UTC(
    time.year,
    time.month - 1,
    time.day,
    time.hour,
    time.minute,
    time.second,
  );
  const day = 24 * 60 * 60 * 1000;
  const before = zoneOffset(wall - day, zone);
  const after = zoneOffset(wall + day, zone);
  const valid = [wall - before, wall - after].filter(
    (epoch) => zoneOffset(epoch, zone) === wall - epoch,
  );
  return valid.length > 0 ? Math.min(...valid) : wall - before;
}

function isoUtc(epochMs: number): string {
  return `${new Date(epochMs).toISOString().slice(0, 19)}Z`;
}

/** An ICAL time with the TZID it was written with, resolved to an `EventTime`. */
function eventTime(
  time: ICAL.Time,
  tzid: string | null,
  defaultTimeZone: string,
): EventTime {
  if (time.isDate) {
    return {
      kind: "date",
      date: `${zeroPad(time.year, 4)}-${zeroPad(time.month)}-${zeroPad(time.day)}`,
    };
  }
  const zoneId = time.zone?.tzid;
  if (zoneId === "UTC") {
    return {
      kind: "dateTime",
      utc: isoUtc(time.toUnixTime() * 1000),
      timeZone: "UTC",
    };
  }
  const named = tzid === null ? null : ianaZone(tzid);
  if (tzid !== null && zoneId === tzid) {
    // Resolved from the part's own VTIMEZONE.
    return {
      kind: "dateTime",
      utc: isoUtc(time.toUnixTime() * 1000),
      timeZone: named,
    };
  }
  return {
    kind: "dateTime",
    utc: isoUtc(wallToEpoch(time, named ?? defaultTimeZone)),
    timeZone: named,
  };
}

function propertyTime(
  component: ICAL.Component,
  name: string,
  defaultTimeZone: string,
): EventTime | null {
  const property = component.getFirstProperty(name);
  if (property === null) return null;
  const value = property.getFirstValue();
  if (!(value instanceof ICAL.Time)) return null;
  const tzid = property.getParameter("tzid");
  return eventTime(
    value,
    typeof tzid === "string" ? tzid : null,
    defaultTimeZone,
  );
}

function text(component: ICAL.Component, name: string): string {
  const value = component.getFirstPropertyValue(name);
  return typeof value === "string" ? value.trim() : "";
}

function stringParameter(property: ICAL.Property, name: string): string | null {
  const value = property.getParameter(name);
  if (typeof value === "string") return value;
  if (Array.isArray(value) && typeof value[0] === "string") return value[0];
  return null;
}

/** The person a property names, or null when neither its EMAIL nor its value is a plain address. */
function person(property: ICAL.Property): CalendarPerson | null {
  const value = String(property.getFirstValue() ?? "").trim();
  const email = [
    stringParameter(property, "email") ?? "",
    /^mailto:/i.test(value) ? value.slice(7) : value,
  ]
    .map((candidate) => candidate.trim())
    .find(isPlainAddress);
  if (email === undefined) return null;
  const cn = (stringParameter(property, "cn") ?? "").trim();
  return {
    email,
    name: addressKey(cn) === addressKey(email) ? "" : cn,
  };
}

function attendee(property: ICAL.Property): Attendee | null {
  const who = person(property);
  if (who === null) return null;
  const upper = (name: string, fallback: string) =>
    (stringParameter(property, name) ?? fallback).toUpperCase();
  return {
    ...who,
    partstat: upper("partstat", "NEEDS-ACTION"),
    role: upper("role", "REQ-PARTICIPANT"),
    kind: upper("cutype", "INDIVIDUAL"),
    rsvp: upper("rsvp", "FALSE") === "TRUE",
  };
}

function eventFrom(
  component: ICAL.Component,
  defaultTimeZone: string,
): CalendarEvent | null {
  const start = propertyTime(component, "dtstart", defaultTimeZone);
  if (start === null) return null;
  const startProperty = component.getFirstProperty("dtstart")!;
  const startTzid = stringParameter(startProperty, "tzid");
  let end = propertyTime(component, "dtend", defaultTimeZone);
  if (end === null) {
    const endTime = new ICAL.Event(component).endDate;
    end = eventTime(endTime, startTzid, defaultTimeZone);
  }
  const organizer = component.getFirstProperty("organizer");
  const sequence = Number(component.getFirstPropertyValue("sequence") ?? 0);
  const status = text(component, "status").toUpperCase();
  const rule = component.getFirstPropertyValue("rrule");
  const zone =
    start.kind === "dateTime"
      ? (start.timeZone ?? defaultTimeZone)
      : defaultTimeZone;
  return {
    uid: text(component, "uid"),
    sequence: Number.isFinite(sequence) ? sequence : 0,
    recurrenceId: propertyTime(component, "recurrence-id", defaultTimeZone),
    status: status === "" ? null : status,
    summary: text(component, "summary"),
    location: text(component, "location"),
    description: text(component, "description"),
    organizer: organizer === null ? null : person(organizer),
    attendees: component
      .getAllProperties("attendee")
      .map(attendee)
      .filter((entry): entry is Attendee => entry !== null),
    start,
    end,
    recurrence:
      rule instanceof ICAL.Recur ? recurrenceText(rule.toString(), zone) : null,
  };
}

/**
 * Drop each VTIMEZONE rule that doesn't repeat yearly: real zones change at most a few times a
 * year, and ical.js expands a rule like FREQ=SECONDLY one step at a time, which never ends.
 */
function dropUnusualZoneRules(root: ICAL.Component): void {
  for (const zone of root.getAllSubcomponents("vtimezone")) {
    for (const observance of zone.getAllSubcomponents()) {
      for (const rule of observance.getAllProperties("rrule")) {
        const value = rule.getFirstValue();
        if (!(value instanceof ICAL.Recur) || value.freq !== "YEARLY") {
          observance.removeProperty(rule);
        }
      }
    }
  }
}

/**
 * Read a text/calendar part. Times written with a `TZID` use the part's VTIMEZONE when it has one
 * (Google, Outlook and Apple all send them), else the IANA zone the TZID names (or its Windows
 * name), else `defaultTimeZone`; floating times use `defaultTimeZone`. A VTIMEZONE rule that isn't
 * FREQ=YEARLY is ignored, and an event that can't be read is skipped. Null when the text isn't
 * iCalendar or holds no VEVENT with a DTSTART.
 */
export function parseCalendar(
  text: string,
  defaultTimeZone: string,
): CalendarInvite | null {
  let root: ICAL.Component;
  try {
    const parsed = ICAL.parse(text);
    root = new ICAL.Component(
      (Array.isArray(parsed[0]) ? parsed[0] : parsed) as unknown[],
    );
  } catch {
    return null;
  }
  if (root.name !== "vcalendar") return null;
  dropUnusualZoneRules(root);
  const events = root
    .getAllSubcomponents("vevent")
    .map((component) => {
      try {
        return eventFrom(component, defaultTimeZone);
      } catch {
        return null;
      }
    })
    .filter((event): event is CalendarEvent => event !== null);
  if (events.length === 0) return null;
  const method = root.getFirstPropertyValue("method");
  return {
    method: typeof method === "string" ? method.trim().toUpperCase() : null,
    events,
  };
}

/** The event a card shows: the first without a RECURRENCE-ID, else the first. */
export function primaryEvent(invite: CalendarInvite): CalendarEvent {
  return (
    invite.events.find((event) => event.recurrenceId === null) ??
    invite.events[0]!
  );
}

/** The attendee entry that is the user: the first whose address is one of `ownAddresses`. */
export function findAttendee(
  event: CalendarEvent,
  ownAddresses: readonly string[],
): Attendee | null {
  const own = new Set(ownAddresses.map(addressKey));
  return (
    event.attendees.find((candidate) => own.has(addressKey(candidate.email))) ??
    null
  );
}

const DAY_NAMES: Record<string, string> = {
  MO: "Monday",
  TU: "Tuesday",
  WE: "Wednesday",
  TH: "Thursday",
  FR: "Friday",
  SA: "Saturday",
  SU: "Sunday",
};

const ORDINALS: Record<string, string> = {
  "1": "first",
  "2": "second",
  "3": "third",
  "4": "fourth",
  "5": "fifth",
  "-1": "last",
};

function englishList(items: string[]): string {
  return items.length <= 1
    ? (items[0] ?? "")
    : `${items.slice(0, -1).join(", ")} and ${items.at(-1)}`;
}

/** `UNTIL` as `Dec 18, 2026`, the date in `timeZone` for a UTC time. */
function untilText(until: string, timeZone: string): string | null {
  const match = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z?))?$/.exec(
    until,
  );
  if (match === null) return null;
  const [, year, month, day, hour, minute, second, utc] = match;
  let date = { year: Number(year), month: Number(month), day: Number(day) };
  if (hour !== undefined && utc === "Z") {
    const epoch = Date.UTC(
      date.year,
      date.month - 1,
      date.day,
      Number(hour),
      Number(minute),
      Number(second),
    );
    const local = new Date(epoch + zoneOffset(epoch, timeZone));
    date = {
      year: local.getUTCFullYear(),
      month: local.getUTCMonth() + 1,
      day: local.getUTCDate(),
    };
  }
  return `${MONTH_ABBREVIATIONS[date.month - 1]} ${date.day}, ${date.year}`;
}

/**
 * An RRULE in English: `Daily`, `Every 2 weeks on Monday and Thursday`, `Weekly on weekdays`,
 * `Monthly on day 15`, `Monthly on the last Friday`, `Annually`, then `, 6 times` or
 * `, until Dec 18, 2026` (a UTC `UNTIL` as its date in `timeZone`). Rules it can't describe
 * (other frequencies) give `Repeats`.
 */
export function recurrenceText(rrule: string, timeZone: string): string {
  const parts = new Map<string, string>();
  for (const pair of rrule.replace(/^RRULE:/i, "").split(";")) {
    const equals = pair.indexOf("=");
    if (equals > 0) {
      parts.set(
        pair.slice(0, equals).toUpperCase(),
        pair.slice(equals + 1).toUpperCase(),
      );
    }
  }
  const interval = Math.max(1, Number(parts.get("INTERVAL") ?? 1) || 1);
  const days = (parts.get("BYDAY") ?? "")
    .split(",")
    .filter((day) => day !== "");
  const units: Record<string, [string, string]> = {
    DAILY: ["Daily", "days"],
    WEEKLY: ["Weekly", "weeks"],
    MONTHLY: ["Monthly", "months"],
    YEARLY: ["Annually", "years"],
  };
  const unit = units[parts.get("FREQ") ?? ""];
  if (unit === undefined) return "Repeats";
  let sentence = interval === 1 ? unit[0] : `Every ${interval} ${unit[1]}`;
  const frequency = parts.get("FREQ");
  if (frequency === "WEEKLY" && days.length > 0) {
    const plain = days.map((day) => day.slice(-2));
    const weekdays = ["MO", "TU", "WE", "TH", "FR"];
    sentence +=
      plain.length === 5 && weekdays.every((day) => plain.includes(day))
        ? " on weekdays"
        : ` on ${englishList(plain.map((day) => DAY_NAMES[day] ?? day))}`;
  } else if (frequency === "MONTHLY") {
    const monthDay = parts.get("BYMONTHDAY");
    const setPosition = parts.get("BYSETPOS");
    const positioned =
      days.length === 1
        ? /^([+-]?\d)?(MO|TU|WE|TH|FR|SA|SU)$/.exec(days[0]!)
        : null;
    const position = positioned?.[1]?.replace("+", "") ?? setPosition;
    if (monthDay !== undefined && /^\d+$/.test(monthDay)) {
      sentence += ` on day ${Number(monthDay)}`;
    } else if (monthDay === "-1") {
      sentence += " on the last day";
    } else if (positioned && position && ORDINALS[position]) {
      sentence += ` on the ${ORDINALS[position]} ${DAY_NAMES[positioned[2]!]}`;
    }
  }
  const count = parts.get("COUNT");
  const until = parts.get("UNTIL");
  if (count !== undefined && /^\d+$/.test(count)) {
    sentence += Number(count) === 1 ? ", once" : `, ${Number(count)} times`;
  } else if (until !== undefined) {
    const date = untilText(until, timeZone);
    if (date !== null) sentence += `, until ${date}`;
  }
  return sentence;
}

/** Text escaped for an iCalendar TEXT value. */
export function escapeText(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,")
    .replace(/\r\n|\r|\n/g, "\\n");
}

/** A parameter value: quoted when it holds `:`, `;` or `,`; quotes and line breaks dropped. */
function parameterValue(value: string): string {
  const clean = value.replace(/["\r\n]/g, "");
  return /[:;,]/.test(clean) ? `"${clean}"` : clean;
}

/** Fold a content line at 75 octets (RFC 5545 3.1), never inside a UTF-8 sequence. */
export function foldLine(line: string): string {
  const pieces: string[] = [];
  let current = "";
  let octets = 0;
  for (const character of line) {
    const size = utf8Length(character.codePointAt(0)!);
    const limit = pieces.length === 0 ? 75 : 74;
    if (octets + size > limit) {
      pieces.push(current);
      current = "";
      octets = 0;
    }
    current += character;
    octets += size;
  }
  pieces.push(current);
  return pieces.join(`${CRLF} `);
}

function basicUtc(utc: string): string {
  return utc.replace(/[-:]/g, "").replace(/\.\d+/, "");
}

function timeProperty(name: string, time: EventTime): string {
  return time.kind === "date"
    ? `${name};VALUE=DATE:${time.date.replace(/-/g, "")}`
    : `${name}:${basicUtc(time.utc)}`;
}

function personProperty(
  name: string,
  who: CalendarPerson,
  parameters: string[],
): string {
  if (!isPlainAddress(who.email)) throw new InvalidAddressError(who.email);
  const cn = who.name.trim() === "" ? [] : [`CN=${parameterValue(who.name)}`];
  return `${[name, ...parameters, ...cn].join(";")}:mailto:${who.email}`;
}

/** What an RSVP by mail needs. */
export type ReplyInput = {
  /** the event answered (an occurrence when it has a RECURRENCE-ID) */
  event: CalendarEvent;
  /** the user, as invited (`findAttendee`) or their own address */
  attendee: CalendarPerson;
  /** the answer */
  partstat: ReplyPartstat;
  /** epoch ms, for DTSTAMP */
  now: number;
  /** a note to the organizer, or null */
  comment: string | null;
};

/**
 * The iCalendar REPLY (RFC 5546) for an RSVP: VCALENDAR with PRODID, VERSION, CALSCALE and
 * METHOD:REPLY, and one VEVENT with DTSTART and DTEND (UTC, or VALUE=DATE), DTSTAMP (`now`, UTC,
 * whole seconds), ORGANIZER, UID, RECURRENCE-ID (when answering one occurrence), the user's
 * ATTENDEE with PARTSTAT, COMMENT (when given), SEQUENCE and SUMMARY, copied from the invite. CRLF
 * line ends, lines folded at 75 octets. UID is written TEXT-escaped like SUMMARY. Throws when the
 * event has no organizer to reply to, and `InvalidAddressError` when the organizer's or attendee's
 * address isn't a plain address.
 */
export function buildReply(input: ReplyInput): string {
  const { event } = input;
  if (event.organizer === null) throw new Error("The invite has no organizer");
  const lines = [
    "BEGIN:VCALENDAR",
    `PRODID:${PRODID}`,
    "VERSION:2.0",
    "CALSCALE:GREGORIAN",
    "METHOD:REPLY",
    "BEGIN:VEVENT",
    timeProperty("DTSTART", event.start),
    timeProperty("DTEND", event.end),
    `DTSTAMP:${basicUtc(isoUtc(input.now))}`,
    personProperty("ORGANIZER", event.organizer, []),
    `UID:${escapeText(event.uid)}`,
  ];
  if (event.recurrenceId !== null) {
    lines.push(timeProperty("RECURRENCE-ID", event.recurrenceId));
  }
  lines.push(
    personProperty("ATTENDEE", input.attendee, [`PARTSTAT=${input.partstat}`]),
  );
  if (input.comment !== null && input.comment.trim() !== "") {
    lines.push(`COMMENT:${escapeText(input.comment.trim())}`);
  }
  lines.push(
    `SEQUENCE:${event.sequence}`,
    `SUMMARY:${escapeText(event.summary)}`,
    "END:VEVENT",
    "END:VCALENDAR",
  );
  return lines.map(foldLine).join(CRLF) + CRLF;
}

const REPLY_SUBJECTS: Record<ReplyPartstat, string> = {
  ACCEPTED: "Accepted",
  TENTATIVE: "Tentatively accepted",
  DECLINED: "Declined",
};

const REPLY_VERBS: Record<ReplyPartstat, string> = {
  ACCEPTED: "accepted",
  TENTATIVE: "tentatively accepted",
  DECLINED: "declined",
};

/** The mail an RSVP goes in, besides the calendar part. */
export type ReplyMail = {
  /** the organizer */
  to: Mailbox;
  /** `Accepted: Design review` */
  subject: string;
  /** body, markdown: `Maya Chen has accepted this invitation.` and the comment */
  markdown: string;
};

/**
 * The message an RSVP by mail is sent as: to the organizer, subject `Accepted: …`,
 * `Tentatively accepted: …` or `Declined: …`, and a one-line body. Build it with `buildMime`,
 * `calendar: { method: "REPLY", content: buildReply(…) }`. Line breaks and other whitespace runs in
 * the subject become one space. Throws without an organizer, and `InvalidAddressError` when the
 * organizer's address isn't a plain address.
 */
export function replyMail(input: ReplyInput): ReplyMail {
  const { event, attendee, partstat } = input;
  if (event.organizer === null) throw new Error("The invite has no organizer");
  if (!isPlainAddress(event.organizer.email)) {
    throw new InvalidAddressError(event.organizer.email);
  }
  const who = attendee.name.trim() || attendee.email;
  const comment = input.comment?.trim() ?? "";
  return {
    to: { name: event.organizer.name, email: event.organizer.email },
    subject: `${REPLY_SUBJECTS[partstat]}: ${event.summary}`
      .replace(/\s+/g, " ")
      .trim(),
    markdown:
      `${who} has ${REPLY_VERBS[partstat]} this invitation.` +
      (comment === "" ? "" : `\n\n${comment}`),
  };
}
