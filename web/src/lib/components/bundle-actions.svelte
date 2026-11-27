<!-- @component A bundle's Pin all (Unpin all when everything in it is pinned) and Snooze all (with its menu) buttons, then Done all unless left out, as a thread row's hover actions draw them: on a closed bundle's hover bar, and before the open bundle's own Done all. -->
<script lang="ts">
  import type { SnoozeSource } from "@inbox/shared/snooze-suggest";
  import Check from "@lucide/svelte/icons/check";
  import Clock from "@lucide/svelte/icons/clock";
  import Pin from "@lucide/svelte/icons/pin";
  import PinOff from "@lucide/svelte/icons/pin-off";
  import {
    iconButton,
    pinnedColors,
    snoozeOpenColors,
    toneColors,
  } from "./icon-button";
  import SnoozePopover from "./snooze-popover.svelte";

  let {
    snoozeOpen,
    showDone = true,
    unpins = false,
    onPin,
    onSnoozeToggle,
    onSnooze,
    onSnoozeClose,
    onDone,
  }: {
    /** whether Snooze all's menu is open */
    snoozeOpen: boolean;
    /** whether Done all shows */
    showDone?: boolean;
    /** whether Pin all is Unpin all (`bundleUnpinsAll`) */
    unpins?: boolean;
    /** pin them all, or unpin them all */
    onPin: () => void;
    /** open or close Snooze all's menu */
    onSnoozeToggle: () => void;
    /** snooze them all, picked from the menu */
    onSnooze: (
      epochMillis: number,
      source: SnoozeSource,
      pickedKey?: string,
    ) => void;
    /** close Snooze all's menu */
    onSnoozeClose: () => void;
    /** mark them all done */
    onDone: () => void;
  } = $props();
</script>

<div class="flex items-center gap-1 shrink-0">
  <button
    type="button"
    data-action="pin"
    aria-label={unpins ? "Unpin all" : "Pin all"}
    title={unpins ? "Unpin all" : "Pin all"}
    aria-pressed={unpins}
    onclick={onPin}
    class={iconButton("small", unpins ? pinnedColors : toneColors.pin)}
  >
    {#if unpins}
      <PinOff class="w-[18px] h-[18px]" />
    {:else}
      <Pin class="w-[18px] h-[18px]" />
    {/if}
  </button>
  <div class="relative">
    <button
      type="button"
      data-action="snooze"
      aria-label="Snooze all"
      title="Snooze all"
      aria-haspopup="menu"
      aria-expanded={snoozeOpen}
      onclick={onSnoozeToggle}
      class={iconButton(
        "small",
        snoozeOpen ? snoozeOpenColors : toneColors.snooze,
      )}
    >
      <Clock class="w-[18px] h-[18px]" />
    </button>
    {#if snoozeOpen}
      <SnoozePopover onPick={onSnooze} onClose={onSnoozeClose} />
    {/if}
  </div>
  {#if showDone}
    <button
      type="button"
      data-action="done"
      aria-label="Done all"
      title="Done all"
      onclick={onDone}
      class={iconButton("small", toneColors.done)}
    >
      <Check class="w-[18px] h-[18px]" />
    </button>
  {/if}
</div>
