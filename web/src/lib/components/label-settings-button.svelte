<!-- @component A gear that opens one label's settings (sidebar, bundling, icon) in a popover. -->
<script lang="ts">
  import Settings2 from "@lucide/svelte/icons/settings-2";
  import { cubicOut } from "svelte/easing";
  import { prefersReducedMotion } from "svelte/motion";
  import { scale } from "svelte/transition";
  import type { GmailLabel } from "../gmail/types";
  import LabelSettings from "./label-settings.svelte";
  import { popover } from "./popover";

  let {
    label,
    name,
  }: {
    /** the label */
    label: GmailLabel;
    /** its name as shown */
    name: string;
  } = $props();

  let open = $state(false);
  let above = $state(false);
  const motion = $derived(prefersReducedMotion.current ? 0 : 150);
</script>

<div class="relative">
  <button
    type="button"
    aria-haspopup="dialog"
    aria-expanded={open}
    aria-label={`${name} settings`}
    title="Label settings"
    onclick={() => (open = !open)}
    class="hit-target w-8 h-8 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text transition-colors"
  >
    <Settings2 class="w-4 h-4" aria-hidden="true" />
  </button>
  {#if open}
    <div
      role="dialog"
      aria-label={`${name} settings`}
      {@attach popover({
        onClose: () => (open = false),
        onPlace: (value) => (above = value),
      })}
      transition:scale={{ duration: motion, start: 0.96, easing: cubicOut }}
      class={[
        "absolute right-0 z-30 w-[300px] p-3 rounded-[20px] bg-surface-raised shadow-pop",
        above
          ? "bottom-full mb-2 origin-bottom-right"
          : "top-full mt-2 origin-top-right",
      ]}
    >
      <div class="px-1 pb-1 text-sm font-semibold">{name}</div>
      <LabelSettings {label} />
    </div>
  {/if}
</div>
