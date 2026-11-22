<!-- @component The snooze choices: learned suggestions, the last custom time, the date and time picker, and for a todo its repeat rule. -->
<script lang="ts">
  import { formatClock, type SnoozeSource } from "@inbox/shared/snooze-suggest";
  import {
    type Repeat,
    type RepeatDraft,
    repeatText,
  } from "@inbox/shared/todos";
  import CalendarClock from "@lucide/svelte/icons/calendar-clock";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import History from "@lucide/svelte/icons/history";
  import RepeatIcon from "@lucide/svelte/icons/repeat";
  import { untrack } from "svelte";
  import { pushBack } from "../back-stack.svelte";
  import { getMailState, type SnoozeMenuStep } from "../mail-state.svelte";
  import CustomPicker from "./custom-picker.svelte";
  import { TIME_ROW_ICONS } from "./icons";
  import { type StepDirection, slideStep } from "./menu-motion";
  import RepeatEditor from "./repeat-editor.svelte";

  let {
    onPick,
    repeat = null,
    onRepeat,
    step = $bindable("menu"),
    highlight = $bindable(0),
  }: {
    onPick: (
      epochMillis: number,
      source: SnoozeSource,
      pickedKey?: string,
    ) => void;
    /** a todo's repeat rule, shown with the choices */
    repeat?: Repeat | null;
    /** save a todo's repeat rule; without it the menu offers none */
    onRepeat?: (draft: RepeatDraft) => void;
    step?: SnoozeMenuStep;
    highlight?: number;
  } = $props();

  const MENU_CLOCK_TICK_MS = 30_000;

  const mail = getMailState();

  $effect(() => {
    mail.refreshNow();
    const timer = setInterval(mail.refreshNow, MENU_CLOCK_TICK_MS);
    return () => clearInterval(timer);
  });

  // Back from a deeper step returns to the choices, not out of the menu.
  $effect(() => {
    if (step === "menu") return;
    return untrack(() =>
      pushBack(() => {
        step = "menu";
      }),
    );
  });

  const stepDirection = (): StepDirection =>
    step === "menu" ? "back" : "forward";

  const rowClass =
    "w-full h-10 max-sm:h-12 flex items-center gap-3 px-2 rounded-full text-sm text-left text-text outline-none transition-colors hover:bg-surface-muted focus-visible:bg-surface-muted";
</script>

<div class="grid overflow-hidden">
  {#key step}
    <div
      class="[grid-area:1/1] min-w-0"
      in:slideStep={{ direction: stepDirection, leaving: false }}
      out:slideStep={{ direction: stepDirection, leaving: true }}
    >
      {#if step === "custom"}
        <CustomPicker
          {onPick}
          onBack={() => {
            step = "menu";
          }}
        />
      {:else if step === "repeat" && onRepeat}
        <RepeatEditor
          {repeat}
          onSave={onRepeat}
          onBack={() => {
            step = "menu";
          }}
        />
      {:else}
        <div class="p-2">
          <div
            class="h-8 flex items-center px-2 text-[13px] font-semibold text-text-secondary"
          >
            Snooze until
          </div>
          {#each mail.snoozeRows as row, index (`${row.keyId}-${row.time}`)}
            {@const Icon = TIME_ROW_ICONS[row.icon]}
            <button
              type="button"
              role="menuitem"
              title={`Press ${index + 1}`}
              onclick={() => onPick(row.time, "suggestion", row.keyId)}
              onmouseenter={() => {
                highlight = index;
              }}
              class={[rowClass, highlight === index && "bg-surface-muted"]}
            >
              <Icon
                class="w-[18px] h-[18px] shrink-0 text-text-secondary"
                aria-hidden="true"
              />
              <span class="flex-1 min-w-0 truncate">{row.text}</span>
              <span
                class="text-[13px] text-text-secondary tabular-nums shrink-0"
              >
                {formatClock(row.time)}
              </span>
            </button>
          {/each}
          {#if mail.snoozeLastRow}
            {@const last = mail.snoozeLastRow}
            {@const index = mail.snoozeRows.length}
            <button
              type="button"
              role="menuitem"
              onclick={() => onPick(last.time, "last")}
              onmouseenter={() => {
                highlight = index;
              }}
              class={[rowClass, highlight === index && "bg-surface-muted"]}
            >
              <History
                class="w-[18px] h-[18px] shrink-0 text-text-secondary"
                aria-hidden="true"
              />
              <span class="flex-1 min-w-0 truncate">{last.text}</span>
              <span
                class="text-[13px] text-text-secondary tabular-nums shrink-0"
              >
                {formatClock(last.time)}
              </span>
            </button>
          {/if}
          {#if mail.snoozeRows.length > 0 || mail.snoozeLastRow}
            <hr class="h-px border-0 bg-border my-1">
          {/if}
          <button
            type="button"
            role="menuitem"
            title="Press d"
            onclick={() => {
              step = "custom";
            }}
            class={rowClass}
          >
            <CalendarClock
              class="w-[18px] h-[18px] shrink-0 text-text-secondary"
              aria-hidden="true"
            />
            <span class="flex-1 min-w-0">Pick date &amp; time…</span>
          </button>
          {#if onRepeat}
            <button
              type="button"
              role="menuitem"
              title="Press r"
              onclick={() => {
                step = "repeat";
              }}
              class={rowClass}
            >
              <RepeatIcon
                class={[
                  "w-[18px] h-[18px] shrink-0",
                  repeat ? "text-accent-strong" : "text-text-secondary",
                ]}
                aria-hidden="true"
              />
              <span class="flex-1 min-w-0 truncate"
                >{repeat ? repeatText(repeat) : "Repeat…"}</span
              >
              {#if repeat}
                <ChevronRight
                  class="w-4 h-4 shrink-0 text-text-secondary"
                  aria-hidden="true"
                />
              {/if}
            </button>
          {/if}
        </div>
      {/if}
    </div>
  {/key}
</div>
