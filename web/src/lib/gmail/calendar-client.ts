import { type FetchLike, requestJson, type TokenSource } from "./request";

const BASE = "https://www.googleapis.com/calendar/v3/calendars/primary";

/** A start or end in the Calendar API: `date` for all-day, else `dateTime`. */
export type CalendarApiTime = {
  date?: string;
  dateTime?: string;
  timeZone?: string;
};

/** An attendee in the Calendar API. */
export type CalendarApiAttendee = {
  email?: string;
  displayName?: string;
  self?: boolean;
  organizer?: boolean;
  resource?: boolean;
  responseStatus?: "needsAction" | "declined" | "tentative" | "accepted";
  optional?: boolean;
  comment?: string;
  additionalGuests?: number;
};

/** An event in the Calendar API, reduced to what the invite card reads. */
export type CalendarApiEvent = {
  id: string;
  iCalUID?: string;
  status?: "confirmed" | "tentative" | "cancelled";
  summary?: string;
  start?: CalendarApiTime;
  end?: CalendarApiTime;
  transparency?: "opaque" | "transparent";
  attendees?: CalendarApiAttendee[];
  recurringEventId?: string;
};

/** The Calendar REST calls the invite card makes, on the user's primary calendar. */
export class CalendarClient {
  #tokens: TokenSource;
  #fetch: FetchLike;

  constructor(tokens: TokenSource, fetchImpl: FetchLike = fetch) {
    this.#tokens = tokens;
    this.#fetch = (input, init) => fetchImpl(input, init);
  }

  #request<Result>(path: string, init: RequestInit = {}): Promise<Result> {
    return requestJson(this.#tokens, this.#fetch, `${BASE}${path}`, init);
  }

  /** Events with an iCalendar UID (the series, and changed instances). */
  async eventsByICalUID(iCalUID: string): Promise<CalendarApiEvent[]> {
    const params = new URLSearchParams({ iCalUID, showDeleted: "false" });
    const result = await this.#request<{ items?: CalendarApiEvent[] }>(
      `/events?${params}`,
    );
    return result.items ?? [];
  }

  /** The instance of a recurring event that originally started at `originalStart` (RFC 3339 or a date). */
  async instance(
    eventId: string,
    originalStart: string,
  ): Promise<CalendarApiEvent | null> {
    const params = new URLSearchParams({ originalStart });
    const result = await this.#request<{ items?: CalendarApiEvent[] }>(
      `/events/${encodeURIComponent(eventId)}/instances?${params}`,
    );
    return result.items?.[0] ?? null;
  }

  /** Events overlapping a time range, recurring ones expanded. */
  async eventsBetween(
    timeMin: number,
    timeMax: number,
  ): Promise<CalendarApiEvent[]> {
    const params = new URLSearchParams({
      timeMin: new Date(timeMin).toISOString(),
      timeMax: new Date(timeMax).toISOString(),
      singleEvents: "true",
      orderBy: "startTime",
      maxResults: "20",
    });
    const result = await this.#request<{ items?: CalendarApiEvent[] }>(
      `/events?${params}`,
    );
    return result.items ?? [];
  }

  /** Replace an event's attendees; Google mails the organizer the change. */
  patchAttendees(
    eventId: string,
    attendees: CalendarApiAttendee[],
  ): Promise<CalendarApiEvent> {
    return this.#request(
      `/events/${encodeURIComponent(eventId)}?sendUpdates=all`,
      { method: "PATCH", body: JSON.stringify({ attendees }) },
    );
  }
}
