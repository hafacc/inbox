<!-- @component A picked day and 24-hour time, with the user's usual times as shortcuts; for snooze and schedule send. -->
<script lang="ts">
  import { LABEL_LOCALE } from "@inbox/shared/format";
  import {
    type QuickTime,
    quickTimeEpoch,
    type SnoozeSource,
  } from "@inbox/shared/snooze-suggest";
  import { formatPickedTime } from "@inbox/shared/time-format";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import ChevronLeft from "@lucide/svelte/icons/chevron-left";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { untrack } from "svelte";
  import {
    formatClockMinutes,
    monthGrid,
    monthLabel,
    nextQuarterHour,
    parseClockInput,
    parseYmd,
    toYmd,
    WEEKDAY_SHORT,
  } from "../calendar";
  import { getMailState } from "../mail-state.svelte";

  let {
    onPick,
    onBack,
    quickTimes: quickTimesProp,
    action = "Snooze",
    result = "Back in your inbox",
  }: {
    onPick: (epochMillis: number, source: SnoozeSource) => void;
    onBack: () => void;
    // the user's usual times; snooze's when not given
    quickTimes?: QuickTime[];
    // the submit button's label
    action?: string;
    // the summary before the picked time
    result?: string;
  } = $props();

  /** Today at the strongest quick time if it's still ahead, else the next quarter hour after now. */
  function initialPick(
    now: Date,
    quickTimes: QuickTime[],
  ): { ymd: string; time: string } {
    let best: QuickTime | null = null;
    for (const quick of quickTimes) {
      if (!best || quick.weight > best.weight) best = quick;
    }
    const today = toYmd(now);
    if (best) {
      const epoch = quickTimeEpoch(today, best.clockMinutes);
      if (epoch !== null && epoch > now.getTime()) {
        return { ymd: today, time: formatClockMinutes(best.clockMinutes) };
      }
    }
    const next = nextQuarterHour(now);
    return { ymd: next.ymd, time: formatClockMinutes(next.minutes) };
  }

  const navButton =
    "w-9 h-9 max-sm:w-12 max-sm:h-12 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover disabled:text-border-strong disabled:hover:bg-transparent transition-colors";

  const mail = getMailState();
  const usualTimes = $derived(quickTimesProp ?? mail.snoozeQuickTimes);
  const initial = initialPick(
    new Date(mail.now),
    untrack(() => usualTimes),
  );
  const initialDate = parseYmd(initial.ymd);

  let selected = $state(initial.ymd);
  let view = $state({
    year: initialDate.getFullYear(),
    month: initialDate.getMonth(),
  });
  let customTime = $state(initial.time);
  let timeInput = $state<HTMLInputElement>();
  const timeId = $props.id();

  const today = $derived(new Date(mail.now));
  const grid = $derived(monthGrid(view.year, view.month, today));
  const weeks = $derived(
    Array.from({ length: grid.length / 7 }, (_, week) =>
      grid.slice(week * 7, week * 7 + 7),
    ),
  );
  const atCurrentMonth = $derived(
    view.year === today.getFullYear() && view.month === today.getMonth(),
  );
  const typedMinutes = $derived(parseClockInput(customTime));
  const customEpoch = $derived.by(() => {
    if (typedMinutes === null) return null;
    const date = parseYmd(selected);
    date.setHours(Math.floor(typedMinutes / 60), typedMinutes % 60, 0, 0);
    return date.getTime() > mail.now ? date.getTime() : null;
  });
  const dayLabel = new Intl.DateTimeFormat(LABEL_LOCALE, {
    month: "long",
    day: "numeric",
  });

  $effect(() => {
    timeInput?.focus();
    timeInput?.select();
  });

  function shiftMonth(delta: number) {
    const date = new Date(view.year, view.month + delta, 1);
    view = { year: date.getFullYear(), month: date.getMonth() };
  }

  function pick(epoch: number | null) {
    if (epoch === null || epoch <= Date.now()) mail.refreshNow();
    else onPick(epoch, "custom");
  }
</script>

<form
  class="w-[304px] max-sm:w-full flex flex-col gap-2 px-3.5 pt-2 pb-3.5"
  aria-label="Pick date and time"
  onsubmit={(event) => {
    event.preventDefault();
    pick(customEpoch);
  }}
>
  <div class="h-9 max-sm:h-12 flex items-center gap-1 -mx-1.5">
    <button
      type="button"
      onclick={onBack}
      aria-label="Back to suggestions"
      class={navButton}
    >
      <ArrowLeft class="w-[18px] h-[18px]" />
    </button>
    <span class="flex-1 text-[13px] font-semibold text-text-secondary">
      Pick date &amp; time
    </span>
  </div>
  <div class="h-9 max-sm:h-12 flex items-center gap-1">
    <span class="flex-1 text-sm font-semibold">
      {monthLabel(view.year, view.month)}
    </span>
    <button
      type="button"
      onclick={() => shiftMonth(-1)}
      disabled={atCurrentMonth}
      aria-label="Previous month"
      class={navButton}
    >
      <ChevronLeft class="w-[18px] h-[18px]" />
    </button>
    <button
      type="button"
      onclick={() => shiftMonth(1)}
      aria-label="Next month"
      class={navButton}
    >
      <ChevronRight class="w-[18px] h-[18px]" />
    </button>
  </div>

  <fieldset
    aria-label={monthLabel(view.year, view.month)}
    class="flex flex-col gap-1 border-0 p-0 m-0 min-w-0"
  >
    <div aria-hidden="true" class="h-6 flex items-center gap-1">
      {#each WEEKDAY_SHORT as weekday (weekday)}
        <span
          class="w-9 max-sm:w-auto max-sm:flex-1 text-center text-xs font-semibold text-text-secondary"
        >
          {weekday}
        </span>
      {/each}
    </div>
    {#each weeks as week (week[0]!.ymd)}
      <div class="flex gap-1">
        {#each week as cell (cell.ymd)}
          {@const isSelected = cell.ymd === selected}
          <button
            type="button"
            disabled={cell.past}
            onclick={() => {
              selected = cell.ymd;
            }}
            aria-pressed={isSelected}
            aria-current={cell.today ? "date" : undefined}
            aria-label={dayLabel.format(parseYmd(cell.ymd))}
            class={[
              "w-9 h-9 max-sm:w-auto max-sm:flex-1 max-sm:h-11 rounded-full text-sm tabular-nums flex items-center justify-center transition-colors",
              isSelected
                ? "bg-accent text-on-accent font-semibold"
                : cell.past
                  ? "text-muted cursor-default"
                  : cell.today
                    ? "text-accent-strong font-semibold shadow-[inset_0_0_0_1px_var(--color-accent)] hover:bg-surface-hover"
                    : cell.inMonth
                      ? "text-text hover:bg-surface-hover"
                      : "text-text-secondary hover:bg-surface-hover",
            ]}
          >
            {cell.day}
          </button>
        {/each}
      </div>
    {/each}
  </fieldset>

  {#if usualTimes.length > 0}
    <hr class="h-px border-0 bg-border my-1">
    <fieldset
      aria-label="Your usual times"
      class="flex gap-2 border-0 p-0 m-0 min-w-0"
    >
      {#each usualTimes as quick (quick.clockMinutes)}
        {@const epoch = quickTimeEpoch(selected, quick.clockMinutes)}
        <button
          type="button"
          disabled={epoch === null || epoch <= mail.now}
          onclick={() => pick(epoch)}
          class="flex-1 h-9 max-sm:h-12 rounded-full border border-border bg-surface text-[13px] font-medium tabular-nums hover:bg-surface-hover transition-colors disabled:text-muted disabled:hover:bg-surface"
        >
          {formatClockMinutes(quick.clockMinutes)}
        </button>
      {/each}
    </fieldset>
  {/if}

  <div class="flex items-center gap-2">
    <label
      for={timeId}
      class="text-[13px] font-medium text-text-secondary shrink-0"
    >
      Time
    </label>
    <input
      id={timeId}
      bind:this={timeInput}
      type="text"
      inputmode="numeric"
      bind:value={customTime}
      aria-describedby={`${timeId}-hint`}
      aria-invalid={typedMinutes === null}
      placeholder="HH:mm"
      class="h-9 max-sm:h-12 w-0 flex-1 min-w-0 px-3.5 rounded-full border border-border-strong bg-surface text-sm tabular-nums text-text outline-none focus:border-focus focus:ring-1 focus:ring-focus"
    >
    <span id={`${timeId}-hint`} class="sr-only"
      >24-hour, for example 18:45</span
    >
    <button
      type="submit"
      disabled={customEpoch === null}
      class="h-9 max-sm:h-12 px-4 rounded-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong transition-colors disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
    >
      {action}
    </button>
  </div>
  <div class="text-xs leading-4 text-text-secondary" aria-live="polite">
    {#if customEpoch !== null}
      {result} {formatPickedTime(customEpoch)}
    {:else if typedMinutes === null}
      Type a 24-hour time, for example 18:45.
    {:else}
      That time has passed.
    {/if}
  </div>
</form>
