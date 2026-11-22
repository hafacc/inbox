<!-- @component The schedule-send menu: learned send times (shifted when "Vary scheduled send times" is on), the last custom time, and the picker. -->
<script lang="ts">
  import { PICK_DATE_TIME_LABEL } from "@inbox/shared/send-suggest";
  import type { SnoozeSource } from "@inbox/shared/snooze-suggest";
  import { formatWakeTime } from "@inbox/shared/time-format";
  import CalendarClock from "@lucide/svelte/icons/calendar-clock";
  import History from "@lucide/svelte/icons/history";
  import { untrack } from "svelte";
  import { pushBack } from "../back-stack.svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { freshSeed, sendMenuItems } from "../compose/send-menu";
  import CustomPicker from "./custom-picker.svelte";
  import { TIME_ROW_ICONS } from "./icons";
  import { type StepDirection, slideStep } from "./menu-motion";
  import MenuSheet from "./menu-sheet.svelte";

  let {
    onPick,
    onClose,
  }: {
    // sendAt as shown; chosenAt the unshifted suggestion (null for typed times); the stats time
    onPick: (pick: {
      sendAt: number;
      chosenAt: number | null;
      source: SnoozeSource;
      keyId: string | null;
      typed: number | null;
    }) => void;
    onClose: () => void;
  } = $props();

  const hub = getComposeHub();
  const now = Date.now();
  // One draw per opening, so the shown times hold still while the menu is open.
  const items = untrack(() =>
    sendMenuItems(
      hub.sendRows,
      now,
      {
        enabled: hub.settings.send.varySendTimes,
        maxMinutes: hub.settings.send.varySendMaxMinutes,
      },
      freshSeed(),
    ),
  );
  const last = untrack(() => hub.sendLastRow);
  let step = $state<"menu" | "custom">("menu");
  let highlight = $state(0);
  const choices = items.length + (last ? 1 : 0) + 1;
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

  function pickIndex(index: number) {
    const item = items[index];
    if (item) {
      onPick({
        sendAt: item.sendAt,
        chosenAt: item.chosenAt,
        source: "suggestion",
        keyId: item.keyId,
        typed: null,
      });
    } else if (last && index === items.length) {
      onPick({
        sendAt: last.time,
        chosenAt: null,
        source: "last",
        keyId: null,
        typed: last.time,
      });
    } else {
      step = "custom";
    }
  }

  function onKey(event: KeyboardEvent) {
    if (event.key === "Escape") {
      if (step === "custom") {
        event.preventDefault();
        event.stopPropagation();
        step = "menu";
      }
      return;
    }
    if (step !== "menu") return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const digit = Number.parseInt(event.key, 10);
    let handled = true;
    if (digit >= 1 && digit <= items.length) pickIndex(digit - 1);
    else if (event.key === "d") step = "custom";
    else if (event.key === "ArrowDown" || event.key === "j") {
      highlight = (highlight + 1) % choices;
    } else if (event.key === "ArrowUp" || event.key === "k") {
      highlight = (highlight - 1 + choices) % choices;
    } else if (event.key === "Enter") pickIndex(highlight);
    else handled = false;
    if (handled || event.key.length === 1) {
      event.preventDefault();
      event.stopPropagation();
    }
  }

  const rowClass =
    "w-full h-10 max-sm:h-12 flex items-center gap-3 px-2 rounded-full text-sm text-left text-text outline-none transition-colors hover:bg-surface-muted focus-visible:bg-surface-muted";
</script>

<MenuSheet
  label="Send at"
  role="menu"
  {onClose}
  {onKey}
  prefer="above"
  anchored={(above) => [
    "absolute z-40 left-0 rounded-[20px] bg-surface-raised shadow-pop animate-rise",
    step === "menu" && "w-[296px]",
    above ? "bottom-full mb-2" : "top-full mt-2",
  ]}
>
  <div class="grid overflow-hidden">
    {#key step}
      <div
        class="[grid-area:1/1] min-w-0"
        in:slideStep={{ direction: stepDirection, leaving: false }}
        out:slideStep={{ direction: stepDirection, leaving: true }}
      >
        {#if step === "custom"}
          <CustomPicker
            quickTimes={hub.sendQuickTimes}
            action="Schedule"
            result="Sends"
            onPick={(epoch) =>
              onPick({
                sendAt: epoch,
                chosenAt: null,
                source: "custom",
                keyId: null,
                typed: epoch,
              })}
            onBack={() => {
              step = "menu";
            }}
          />
        {:else}
          <div class="p-2">
            <div
              class="h-8 flex items-center px-2 text-[13px] font-semibold text-text-secondary"
            >
              Send at
            </div>
            {#each items as item, index (item.keyId)}
              {@const Icon = TIME_ROW_ICONS[item.icon]}
              <button
                type="button"
                role="menuitem"
                title={`Press ${index + 1}`}
                onclick={() => pickIndex(index)}
                onmouseenter={() => {
                  highlight = index;
                }}
                class={[rowClass, highlight === index && "bg-surface-muted"]}
              >
                <Icon
                  class="w-[18px] h-[18px] shrink-0 text-text-secondary"
                  aria-hidden="true"
                />
                <span class="flex-1 min-w-0 truncate">{item.text}</span>
                <span
                  class="text-[13px] text-text-secondary tabular-nums shrink-0"
                >
                  {formatWakeTime(item.sendAt, now)}
                </span>
              </button>
            {/each}
            {#if last}
              <button
                type="button"
                role="menuitem"
                onclick={() => pickIndex(items.length)}
                onmouseenter={() => {
                  highlight = items.length;
                }}
                class={[
                  rowClass,
                  highlight === items.length && "bg-surface-muted",
                ]}
              >
                <History
                  class="w-[18px] h-[18px] shrink-0 text-text-secondary"
                  aria-hidden="true"
                />
                <span class="flex-1 min-w-0 truncate">{last.text}</span>
                <span
                  class="text-[13px] text-text-secondary tabular-nums shrink-0"
                >
                  {formatWakeTime(last.time, now)}
                </span>
              </button>
            {/if}
            <hr class="h-px border-0 bg-border my-1">
            <button
              type="button"
              role="menuitem"
              title="Press d"
              onclick={() => {
                step = "custom";
              }}
              onmouseenter={() => {
                highlight = choices - 1;
              }}
              class={[
                rowClass,
                highlight === choices - 1 && "bg-surface-muted",
              ]}
            >
              <CalendarClock
                class="w-[18px] h-[18px] shrink-0 text-text-secondary"
                aria-hidden="true"
              />
              <span class="flex-1 min-w-0">{PICK_DATE_TIME_LABEL}</span>
            </button>
          </div>
        {/if}
      </div>
    {/key}
  </div>
</MenuSheet>
