<!-- @component A todo's repeat rule, as latr sets it: every 1–7 days, weeks or months, on a weekday or a day of the month, at a 24-hour time. -->
<script lang="ts">
  import type { QuickTime } from "@inbox/shared/snooze-suggest";
  import {
    LAST_DAY,
    type Repeat,
    type RepeatDraft,
    type RepeatUnit,
  } from "@inbox/shared/todos";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import { untrack } from "svelte";
  import { formatClockMinutes, parseClockInput } from "../calendar";
  import { getMailState } from "../mail-state.svelte";

  let {
    repeat,
    onSave,
    onBack,
  }: {
    /** the todo's rule, or null for a one-off */
    repeat: Repeat | null;
    /** save the rule */
    onSave: (draft: RepeatDraft) => void;
    /** back to the snooze menu */
    onBack: () => void;
  } = $props();

  const COUNTS = [1, 2, 3, 4, 5, 6, 7];
  const UNITS: readonly RepeatUnit[] = ["day", "week", "month"];
  const WEEKDAYS = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
  ];
  const MONTH_DAYS = Array.from({ length: 31 }, (_, index) => index + 1);
  const DEFAULT_AT = "09:00";

  /** The todo's own rule, else weekly on today at the strongest quick time. */
  function initialDraft(
    current: Repeat | null,
    now: Date,
    quickTimes: QuickTime[],
  ): RepeatDraft {
    if (current) {
      const { every, unit, weekday, monthDay, at } = current;
      return { every, unit, weekday, monthDay, at };
    } else {
      let best: QuickTime | null = null;
      for (const quick of quickTimes) {
        if (!best || quick.weight > best.weight) best = quick;
      }
      return {
        every: 1,
        unit: "week",
        weekday: ((now.getDay() + 6) % 7) + 1,
        monthDay: now.getDate(),
        at: best ? formatClockMinutes(best.clockMinutes) : DEFAULT_AT,
      };
    }
  }

  const mail = getMailState();
  const uid = $props.id();
  const initial = untrack(() =>
    initialDraft(repeat, new Date(mail.now), mail.snoozeQuickTimes),
  );

  let every = $state(initial.every);
  let unit = $state<RepeatUnit>(initial.unit);
  let weekday = $state(initial.weekday);
  let monthDay = $state(initial.monthDay);
  let typedTime = $state(initial.at);
  const typedMinutes = $derived(parseClockInput(typedTime));

  const sectionLabel = "text-xs font-semibold text-text-secondary";
  const choice =
    "h-9 rounded-xl text-sm tabular-nums flex items-center justify-center transition-colors";
  const chosen = "bg-accent text-on-accent font-semibold";
  const unchosen = "text-text hover:bg-surface-hover";

  function unitLabel(value: RepeatUnit): string {
    return every > 1 ? `${value}s` : value;
  }

  function save(minutes: number | null) {
    if (minutes === null) return;
    onSave({ every, unit, weekday, monthDay, at: formatClockMinutes(minutes) });
  }
</script>

<form
  class="w-[304px] flex flex-col gap-3 px-3.5 pt-2 pb-3.5"
  aria-label="Repeat"
  onsubmit={(event) => {
    event.preventDefault();
    save(typedMinutes);
  }}
>
  <div class="h-9 max-sm:h-12 flex items-center gap-1 -mx-1.5">
    <button
      type="button"
      onclick={onBack}
      aria-label="Back to suggestions"
      class="w-9 h-9 max-sm:w-12 max-sm:h-12 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text transition-colors"
    >
      <ArrowLeft class="w-[18px] h-[18px]" />
    </button>
    <span class="flex-1 text-[13px] font-semibold text-text-secondary">
      Repeat
    </span>
  </div>

  <div class="flex flex-col gap-1.5">
    <span id="{uid}-every" class={sectionLabel}>Every</span>
    <fieldset
      aria-labelledby="{uid}-every"
      class="grid grid-cols-7 gap-1 border-0 p-0 m-0 min-w-0"
    >
      {#each COUNTS as count (count)}
        <button
          type="button"
          aria-pressed={every === count}
          onclick={() => (every = count)}
          class={[choice, every === count ? chosen : unchosen]}
        >
          {count}
        </button>
      {/each}
    </fieldset>
    <fieldset
      aria-label="Unit"
      class="grid grid-cols-3 gap-0.5 p-0.5 rounded-full bg-surface-muted border-0 m-0 min-w-0"
    >
      {#each UNITS as value (value)}
        <button
          type="button"
          aria-pressed={unit === value}
          onclick={() => (unit = value)}
          class={[
            "h-8 rounded-full text-sm transition-colors",
            unit === value
              ? "bg-surface-raised text-text font-semibold shadow-pill"
              : "text-text-secondary hover:text-text",
          ]}
        >
          {unitLabel(value)}
        </button>
      {/each}
    </fieldset>
  </div>

  {#if unit === "week"}
    <div class="flex flex-col gap-1.5">
      <span id="{uid}-weekday" class={sectionLabel}>On</span>
      <fieldset
        aria-labelledby="{uid}-weekday"
        class="grid grid-cols-7 gap-1 border-0 p-0 m-0 min-w-0"
      >
        {#each WEEKDAYS as name, index (name)}
          {@const day = index + 1}
          <button
            type="button"
            aria-pressed={weekday === day}
            aria-label={name}
            onclick={() => (weekday = day)}
            class={[choice, weekday === day ? chosen : unchosen]}
          >
            {name[0]}
          </button>
        {/each}
      </fieldset>
    </div>
  {:else if unit === "month"}
    <div class="flex flex-col gap-1.5">
      <span id="{uid}-month-day" class={sectionLabel}>On</span>
      <fieldset
        aria-labelledby="{uid}-month-day"
        class="grid grid-cols-7 gap-1 border-0 p-0 m-0 min-w-0"
      >
        {#each MONTH_DAYS as day (day)}
          <button
            type="button"
            aria-pressed={monthDay === day}
            onclick={() => (monthDay = day)}
            class={[
              "h-8 rounded-xl text-[13px] tabular-nums transition-colors",
              monthDay === day ? chosen : unchosen,
            ]}
          >
            {day}
          </button>
        {/each}
        <button
          type="button"
          aria-pressed={monthDay === LAST_DAY}
          onclick={() => (monthDay = LAST_DAY)}
          class={[
            "col-span-4 h-8 rounded-xl text-[13px] transition-colors",
            monthDay === LAST_DAY ? chosen : unchosen,
          ]}
        >
          Last day
        </button>
      </fieldset>
    </div>
  {/if}

  <div class="flex flex-col gap-1.5">
    <span class={sectionLabel}>At</span>
    {#if mail.snoozeQuickTimes.length > 0}
      <fieldset
        aria-label="Your usual times"
        class="flex gap-2 border-0 p-0 m-0 min-w-0"
      >
        {#each mail.snoozeQuickTimes as quick (quick.clockMinutes)}
          <button
            type="button"
            onclick={() => save(quick.clockMinutes)}
            class="flex-1 h-9 rounded-full border border-border bg-surface text-[13px] font-medium tabular-nums hover:bg-surface-hover transition-colors"
          >
            {formatClockMinutes(quick.clockMinutes)}
          </button>
        {/each}
      </fieldset>
    {/if}
    <div class="flex items-center gap-2">
      <input
        type="text"
        inputmode="numeric"
        bind:value={typedTime}
        aria-label="Time, 24-hour"
        aria-invalid={typedMinutes === null}
        placeholder="HH:mm"
        class="h-9 w-0 flex-1 min-w-0 px-2.5 rounded-xl border border-border-strong bg-surface text-sm tabular-nums text-text outline-none focus:border-focus focus:ring-1 focus:ring-focus"
      >
      <button
        type="submit"
        disabled={typedMinutes === null}
        class="h-9 px-4 rounded-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong transition-colors disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
      >
        Save
      </button>
    </div>
  </div>
</form>
