import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  buildReply,
  type CalendarInvite,
  type CalendarPerson,
  findAttendee,
  foldLine,
  parseCalendar,
  primaryEvent,
  type ReplyMail,
  type ReplyPartstat,
  recurrenceText,
  replyMail,
} from "./calendar";
import { buildMime, type ComposeInput, InvalidAddressError } from "./compose";

type Fixtures = {
  invites: Record<string, string[]>;
  parse: {
    invite: string;
    defaultTimeZone: string;
    expect: CalendarInvite | null;
  }[];
  recurrence: { rrule: string; timeZone: string; expect: string }[];
  reply: {
    name: string;
    invite: string;
    event: number;
    attendee: CalendarPerson;
    partstat: ReplyPartstat;
    now: string;
    comment: string | null;
    expect: string[];
    mail: ReplyMail;
  }[];
  replyRefused: {
    name: string;
    invite: string;
    event: number;
    attendee: CalendarPerson;
    partstat: ReplyPartstat;
    now: string;
    comment: string | null;
  }[];
  mime: { name: string; input: ComposeInput; expect: string[] }[];
  fold: { line: string; expect: string[] }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/calendar-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function crlf(lines: string[]): string {
  return `${lines.join("\r\n")}\r\n`;
}

function invite(name: string, zone = "America/New_York"): CalendarInvite {
  return parseCalendar(crlf(fixtures.invites[name]!), zone)!;
}

describe("calendar fixtures (must match Android)", () => {
  for (const fixture of fixtures.parse) {
    test(`parse: ${fixture.invite}`, () => {
      expect(
        parseCalendar(
          crlf(fixtures.invites[fixture.invite]!),
          fixture.defaultTimeZone,
        ),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.recurrence) {
    test(`recurrence: ${fixture.rrule} (${fixture.timeZone})`, () => {
      expect(recurrenceText(fixture.rrule, fixture.timeZone)).toBe(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.reply) {
    test(`reply: ${fixture.name}`, () => {
      const input = {
        event: invite(fixture.invite).events[fixture.event]!,
        attendee: fixture.attendee,
        partstat: fixture.partstat,
        now: Date.parse(fixture.now),
        comment: fixture.comment,
      };
      expect(buildReply(input)).toBe(crlf(fixture.expect));
      expect(replyMail(input)).toEqual(fixture.mail);
    });
  }
  for (const fixture of fixtures.replyRefused) {
    test(`reply refused: ${fixture.name}`, () => {
      expect(() =>
        buildReply({
          event: invite(fixture.invite).events[fixture.event]!,
          attendee: fixture.attendee,
          partstat: fixture.partstat,
          now: Date.parse(fixture.now),
          comment: fixture.comment,
        }),
      ).toThrow(InvalidAddressError);
    });
  }
  for (const fixture of fixtures.fold) {
    test(`fold: ${fixture.line.slice(0, 12)}…`, () => {
      expect(foldLine(fixture.line)).toBe(fixture.expect.join("\r\n"));
    });
  }
  for (const fixture of fixtures.mime) {
    test(`mime: ${fixture.name}`, () => {
      expect(buildMime(fixture.input)).toBe(fixture.expect.join("\r\n"));
    });
  }
});

test("reply lines are at most 75 octets", () => {
  for (const fixture of fixtures.reply) {
    for (const line of fixture.expect) {
      expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(75);
    }
  }
});

test("a reply parses back with the answer", () => {
  const event = primaryEvent(invite("google"));
  const own = findAttendee(event, ["Maya.Chen@example.org"])!;
  expect(own.email).toBe("maya.chen@example.org");
  const reply = parseCalendar(
    buildReply({
      event,
      attendee: own,
      partstat: "TENTATIVE",
      now: 0,
      comment: null,
    }),
    "UTC",
  )!;
  expect(reply.method).toBe("REPLY");
  const [answered] = reply.events;
  expect(answered!.uid).toBe(event.uid);
  expect(answered!.start).toEqual({
    kind: "dateTime",
    utc: "2026-11-10T17:00:00Z",
    timeZone: "UTC",
  });
  expect(answered!.attendees).toEqual([
    { ...own, partstat: "TENTATIVE", rsvp: false },
  ]);
});

test("the event shown is the one without a recurrence id", () => {
  const both = invite("googleException");
  both.events.unshift(...invite("google").events);
  both.events.reverse();
  expect(primaryEvent(both).recurrenceId).toBeNull();
});

test("no organizer, no reply", () => {
  const event = invite("floatingAndWindowsName").events[0]!;
  expect(() =>
    buildReply({
      event,
      attendee: { email: "a@b.example", name: "" },
      partstat: "ACCEPTED",
      now: 0,
      comment: null,
    }),
  ).toThrow();
});
