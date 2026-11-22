import { expect, test } from "bun:test";
import type { CalendarEvent } from "@inbox/shared/calendar";
import {
  answeredAttendees,
  conflicts,
  conflictText,
  currentAnswer,
  epochOf,
  inviteWhen,
  inviteWhere,
  originalStart,
  partstatOf,
} from "./invite-model";

const ZONE = "America/New_York";

function event(patch: Partial<CalendarEvent>): CalendarEvent {
  return {
    uid: "u1",
    sequence: 0,
    recurrenceId: null,
    status: "CONFIRMED",
    summary: "Design review",
    location: "",
    description: "",
    organizer: { email: "priya@example.com", name: "Priya" },
    attendees: [],
    start: { kind: "dateTime", utc: "2026-11-05T15:00:00Z", timeZone: "UTC" },
    end: { kind: "dateTime", utc: "2026-11-05T15:30:00Z", timeZone: "UTC" },
    recurrence: null,
    ...patch,
  };
}

test("times show in the device zone, 24-hour", () => {
  expect(inviteWhen(event({}), ZONE)).toEqual({
    month: "Nov",
    day: "5",
    text: "Thu, Nov 5 · 10:00–10:30",
  });
  expect(
    inviteWhen(
      event({
        end: { kind: "dateTime", utc: "2026-11-06T16:00:00Z", timeZone: null },
      }),
      ZONE,
    ).text,
  ).toBe("Thu, Nov 5 10:00 – Fri, Nov 6 11:00");
});

test("all-day events show their days, the end being exclusive", () => {
  const oneDay = event({
    start: { kind: "date", date: "2026-11-05" },
    end: { kind: "date", date: "2026-11-06" },
  });
  expect(inviteWhen(oneDay, ZONE).text).toBe("Thu, Nov 5");
  const three = event({
    start: { kind: "date", date: "2026-11-05" },
    end: { kind: "date", date: "2026-11-08" },
  });
  expect(inviteWhen(three, ZONE).text).toBe("Thu, Nov 5 – Sat, Nov 7");
  expect(
    new Date(epochOf({ kind: "date", date: "2026-11-05" }, ZONE)).toISOString(),
  ).toBe("2026-11-05T05:00:00.000Z");
});

test("the place falls back to the meeting link's service", () => {
  expect(inviteWhere(event({ location: " Room 4B " }))).toBe("Room 4B");
  expect(
    inviteWhere(event({ description: "Join https://meet.google.com/abc" })),
  ).toBe("Google Meet");
  expect(inviteWhere(event({}))).toBe("");
});

test("conflicts skip the invite, free time, cancelled and declined events", () => {
  const start = Date.parse("2026-11-05T15:00:00Z");
  const end = Date.parse("2026-11-05T15:30:00Z");
  const found = conflicts(
    [
      {
        id: "1",
        iCalUID: "u1",
        start: { dateTime: "2026-11-05T15:00:00Z" },
        end: { dateTime: "2026-11-05T15:30:00Z" },
      },
      {
        id: "2",
        summary: "Dentist",
        start: { dateTime: "2026-11-05T15:15:00Z" },
        end: { dateTime: "2026-11-05T16:00:00Z" },
      },
      {
        id: "3",
        summary: "Lunch",
        transparency: "transparent",
        start: { dateTime: "2026-11-05T15:00:00Z" },
        end: { dateTime: "2026-11-05T16:00:00Z" },
      },
      {
        id: "4",
        summary: "Old",
        status: "cancelled",
        start: { dateTime: "2026-11-05T15:00:00Z" },
        end: { dateTime: "2026-11-05T16:00:00Z" },
      },
      {
        id: "5",
        summary: "Nope",
        attendees: [{ self: true, responseStatus: "declined" }],
        start: { dateTime: "2026-11-05T15:00:00Z" },
        end: { dateTime: "2026-11-05T16:00:00Z" },
      },
      {
        id: "6",
        summary: "Before",
        start: { dateTime: "2026-11-05T14:00:00Z" },
        end: { dateTime: "2026-11-05T15:00:00Z" },
      },
    ],
    "u1",
    start,
    end,
    ZONE,
  );
  expect(found.map((entry) => entry.summary)).toEqual(["Dentist"]);
  expect(conflictText(found, ZONE)).toBe("Overlaps ‘Dentist’ 10:15");
  expect(conflictText([...found, ...found], ZONE)).toBe("Overlaps 2 events");
  expect(conflictText([], ZONE)).toBeNull();
});

test("answers the user's own attendee entry", () => {
  const attendees = [
    { email: "priya@example.com", responseStatus: "accepted" as const },
    { email: "Me@Example.com", responseStatus: "needsAction" as const },
  ];
  expect(answeredAttendees(attendees, ["me@example.com"], "TENTATIVE")).toEqual(
    [attendees[0]!, { email: "Me@Example.com", responseStatus: "tentative" }],
  );
  expect(
    answeredAttendees([{ email: "x@y", self: true }], [], "DECLINED")?.[0]
      ?.responseStatus,
  ).toBe("declined");
  expect(answeredAttendees(attendees, ["other@x"], "ACCEPTED")).toBeNull();
});

test("the current answer prefers Google Calendar's", () => {
  const attendee = {
    email: "me@x",
    name: "",
    partstat: "NEEDS-ACTION",
    role: "REQ-PARTICIPANT",
    kind: "INDIVIDUAL",
    rsvp: true,
  };
  expect(currentAnswer(null, attendee)).toBeNull();
  expect(currentAnswer(null, { ...attendee, partstat: "accepted" })).toBe(
    "ACCEPTED",
  );
  expect(
    currentAnswer(
      { id: "e", attendees: [{ self: true, responseStatus: "declined" }] },
      attendee,
    ),
  ).toBe("DECLINED");
  expect(partstatOf("needsAction")).toBeNull();
  expect(
    originalStart({
      kind: "dateTime",
      utc: "2026-11-05T15:00:00Z",
      timeZone: null,
    }),
  ).toBe("2026-11-05T15:00:00Z");
});
