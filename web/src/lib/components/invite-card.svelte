<!-- @component A calendar invite in a message: when (24-hour), where, who organizes it, the user's answer and conflicts from their Google Calendar, with Yes / Maybe / No. -->
<script lang="ts">
  import {
    buildReply,
    type CalendarEvent,
    type CalendarInvite,
    findAttendee,
    parseCalendar,
    primaryEvent,
    type ReplyPartstat,
    replyMail,
  } from "@inbox/shared/calendar";
  import { InvalidAddressError } from "@inbox/shared/compose";
  import { type CalendarPart, decodeBase64UrlText } from "@inbox/shared/mime";
  import CalendarClock from "@lucide/svelte/icons/calendar-clock";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import {
    type CalendarApiEvent,
    CalendarClient,
  } from "../gmail/calendar-client";
  import { isNetworkError } from "../gmail/request";
  import {
    answeredAttendees,
    conflicts,
    conflictText,
    currentAnswer,
    epochOf,
    inviteWhen,
    inviteWhere,
    originalStart,
    partstatText,
    RSVP_CHOICES,
  } from "../invite/invite-model";
  import { getMailState } from "../mail-state.svelte";
  import type { MessageView } from "../thread-detail";

  let { message, part }: { message: MessageView; part: CalendarPart } =
    $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const calendar = new CalendarClient(mail.tokens);
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  let invite = $state.raw<CalendarInvite | null>(null);
  let calendarEvent = $state.raw<CalendarApiEvent | null>(null);
  let conflictLine = $state<string | null>(null);
  let answered = $state<ReplyPartstat | null>(null);
  let busy = $state(false);
  let error = $state<string | null>(null);

  const event = $derived<CalendarEvent | null>(
    invite ? primaryEvent(invite) : null,
  );
  const attendee = $derived(
    event ? findAttendee(event, hub.ownAddresses) : null,
  );
  const answer = $derived(
    answered ?? (event ? currentAnswer(calendarEvent, attendee) : null),
  );
  const when = $derived(event ? inviteWhen(event, timeZone) : null);
  const where = $derived(event ? inviteWhere(event) : "");
  const canceled = $derived(
    invite?.method === "CANCEL" || event?.status === "CANCELLED",
  );
  const canAnswer = $derived(
    event !== null &&
      !canceled &&
      invite?.method !== "REPLY" &&
      (calendarEvent !== null || event.organizer !== null),
  );

  $effect(() => {
    let stale = false;
    void (async () => {
      const text = await partText();
      if (stale || text === null) return;
      const parsed = parseCalendar(text, timeZone);
      invite = parsed;
      if (!parsed) return;
      const shown = primaryEvent(parsed);
      if (parsed.method === "REPLY") return;
      try {
        const found = await findInCalendar(shown);
        if (stale) return;
        calendarEvent = found;
        const start = epochOf(shown.start, timeZone);
        const end = epochOf(shown.end, timeZone);
        const busyEvents = await calendar.eventsBetween(start, end);
        if (!stale) {
          conflictLine = conflictText(
            conflicts(busyEvents, shown.uid, start, end, timeZone),
            timeZone,
          );
        }
      } catch (failure) {
        if (!isNetworkError(failure)) {
          console.warn("calendar lookup failed", failure);
        }
      }
    })();
    return () => {
      stale = true;
    };
  });

  async function partText(): Promise<string | null> {
    if (part.data) return decodeBase64UrlText(part.data, part.charset);
    if (!part.attachmentId) return null;
    try {
      const bytes = await mail.attachmentBytes(
        message.id,
        part.partId,
        part.attachmentId,
      );
      return new TextDecoder().decode(bytes);
    } catch {
      return null;
    }
  }

  async function findInCalendar(
    shown: CalendarEvent,
  ): Promise<CalendarApiEvent | null> {
    const events = await calendar.eventsByICalUID(shown.uid);
    if (events.length === 0) return null;
    if (shown.recurrenceId === null) {
      return events.find((entry) => !entry.recurringEventId) ?? events[0]!;
    }
    const series = events.find((entry) => !entry.recurringEventId);
    if (!series) return events[0]!;
    return calendar.instance(series.id, originalStart(shown.recurrenceId));
  }

  async function rsvp(partstat: ReplyPartstat) {
    if (!event || busy) return;
    busy = true;
    error = null;
    try {
      const attendees =
        calendarEvent &&
        answeredAttendees(
          calendarEvent.attendees ?? [],
          hub.ownAddresses,
          partstat,
        );
      if (calendarEvent && attendees) {
        calendarEvent = await calendar.patchAttendees(
          calendarEvent.id,
          attendees,
        );
      } else {
        await rsvpByMail(event, partstat);
      }
      answered = partstat;
    } catch (failure) {
      console.error("rsvp failed", failure);
      error = isNetworkError(failure)
        ? "You're offline. Answer again once you're back."
        : failure instanceof InvalidAddressError
          ? "This invitation has an address that isn't valid, so it can't be answered by mail."
          : "Couldn't send your answer.";
    } finally {
      busy = false;
    }
  }

  async function rsvpByMail(shown: CalendarEvent, partstat: ReplyPartstat) {
    if (shown.organizer === null) throw new Error("no organizer");
    const invitedAs =
      attendee?.email ??
      message.to.find((person) => hub.canSendFrom(person.email))?.email ??
      hub.ownAddresses[0] ??
      mail.email;
    const input = {
      event: shown,
      attendee: { email: invitedAs, name: attendee?.name ?? "" },
      partstat,
      now: Date.now(),
      comment: null,
    };
    const reply = replyMail(input);
    const queued = await hub.sendMessage({
      from: invitedAs,
      to: [reply.to],
      subject: reply.subject,
      markdown: reply.markdown,
      threadId: null,
      calendar: { method: "REPLY", content: buildReply(input) },
    });
    if (!queued) throw new Error("the answer couldn't be built");
  }
</script>

{#if event && when}
  <section
    aria-label={`Invitation: ${event.summary || "Event"}`}
    class="max-w-[640px] flex items-center gap-3 p-2 pr-2 rounded-xl border border-border bg-bg"
  >
    <div
      class="w-10 h-10 shrink-0 rounded-xl border border-border bg-surface flex flex-col items-center justify-center leading-[14px]"
      aria-hidden="true"
    >
      <span class="text-xs font-semibold text-danger">{when.month}</span>
      <span class="text-sm font-semibold">{when.day}</span>
    </div>
    <div class="flex-1 min-w-0 leading-[18px]">
      <div class="text-[13px] font-semibold truncate">
        {event.summary || "(no title)"}
      </div>
      <div class="text-[13px] font-medium truncate">
        {canceled ? "Canceled · " : ""}{when.text}{where ? ` · ${where}` : ""}
      </div>
      <div class="text-xs text-text-secondary truncate">
        {#if invite?.method === "REPLY"}
          {@const replier = event.attendees[0]}
          {#if replier}
            {replier.name || replier.email}: {partstatText(replier.partstat)}
          {/if}
        {:else}
          {#if event.organizer}
            From {event.organizer.name || event.organizer.email}
          {/if}
          {#if event.recurrence}
            · {event.recurrence}
          {/if}
          {#if answer}
            · You: {partstatText(answer)}
          {/if}
        {/if}
      </div>
      {#if conflictLine && !canceled}
        <div class="text-xs text-on-snooze-soft flex items-center gap-1.5">
          <TriangleAlert class="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
          {conflictLine}
        </div>
      {/if}
      {#if error}
        <div role="alert" class="text-xs text-danger">{error}</div>
      {/if}
    </div>
    {#if canAnswer}
      <fieldset
        class="shrink-0 m-0 p-0 flex rounded-xl border border-border-strong bg-surface overflow-hidden"
      >
        <legend class="sr-only">RSVP</legend>
        {#each RSVP_CHOICES as choice, index (choice.partstat)}
          <button
            type="button"
            disabled={busy}
            aria-pressed={answer === choice.partstat}
            onclick={() => rsvp(choice.partstat)}
            class={[
              "h-[30px] px-3.5 text-[13px] font-medium transition-colors disabled:opacity-60",
              index > 0 && "border-l border-border",
              answer === choice.partstat
                ? "bg-accent-soft text-accent-strong"
                : "hover:bg-surface-hover",
            ]}
          >
            {choice.text}
          </button>
        {/each}
      </fieldset>
    {:else if !canceled && invite?.method !== "REPLY"}
      <CalendarClock
        class="w-4 h-4 shrink-0 text-text-secondary"
        aria-label="No organizer to answer"
      />
    {/if}
  </section>
{/if}
