<!-- @component A menu that floats by its button on wider screens and rises as a bottom sheet at phone width: a scrim behind it, a drag handle above what scrolls, and Escape, a scrim tap, dragging it down or Back to close. -->
<script lang="ts">
  import type { Snippet } from "svelte";
  import type { ClassValue } from "svelte/elements";
  import { closeMenu, closeSheet, fadeScrim } from "./menu-motion";
  import { phoneWidth, portal } from "./phone";
  import { popover } from "./popover";
  import { sheetDrag } from "./sheet-drag";

  let {
    label,
    role,
    onClose,
    onKey,
    onkeydown,
    prefer,
    anchored,
    padded = false,
    fit = false,
    children,
  }: {
    /** its accessible name */
    label: string;
    /** what it is to assistive tech */
    role: "menu" | "dialog";
    /** close it */
    onClose: () => void;
    /** keys while it is open, seen first (see `popover`) */
    onKey?: (event: KeyboardEvent) => void;
    /** keys pressed inside it */
    onkeydown?: (event: KeyboardEvent) => void;
    /** the side it floats on while it fits there */
    prefer?: "above" | "below";
    /** its classes while it floats by its button, given whether it opens above it */
    anchored: (above: boolean) => ClassValue;
    /** whether its content needs the sheet to pad it */
    padded?: boolean;
    /** whether its content lays out to fit: floating, kept inside the window (see `popover`); as a sheet, a flex column, so a part of it marked to shrink scrolls while the rest stays */
    fit?: boolean;
    /** what it holds */
    children: Snippet;
  } = $props();

  let above = $state(false);
  // Fixed when opened, so turning the phone doesn't move it out from under a finger.
  const sheet = phoneWidth.current;
</script>

{#if sheet}
  <div class="contents">
    <div {@attach portal} class="fixed inset-0 z-50 flex flex-col justify-end">
      <button
        type="button"
        tabindex="-1"
        aria-label="Close"
        onclick={onClose}
        transition:fadeScrim|global
        class="absolute inset-0 w-full cursor-default bg-scrim/40"
      ></button>
      <!-- svelte-ignore a11y_click_events_have_key_events: the handler only keeps clicks from reaching the page behind -->
      <!-- biome-ignore lint/a11y: its role, a menu or a dialog, comes from the caller -->
      <div
        out:closeSheet|global
        {@attach popover({ onClose: () => onClose(), onKey })}
        {@attach sheetDrag(() => onClose())}
        {role}
        tabindex="-1"
        aria-label={label}
        aria-modal={role === "dialog" ? "true" : undefined}
        onclick={(event) => event.stopPropagation()}
        {onkeydown}
        class="relative max-h-[85dvh] flex flex-col rounded-t-[28px] bg-surface-raised shadow-sheet animate-sheet pb-[env(safe-area-inset-bottom)] outline-none"
      >
        <div
          data-sheet-handle
          class="h-6 shrink-0 flex items-center justify-center touch-none cursor-grab"
          aria-hidden="true"
        >
          <span class="w-8 h-1 rounded-full bg-border-strong"></span>
        </div>
        <div
          class={[
            "min-h-0 shrink overflow-y-auto overscroll-contain",
            fit && "flex flex-col",
            padded && "px-2 pb-2",
          ]}
        >
          {@render children()}
        </div>
      </div>
    </div>
  </div>
{:else}
  <!-- svelte-ignore a11y_click_events_have_key_events: the handler only keeps clicks from reaching the row behind -->
  <!-- biome-ignore lint/a11y: its role, a menu or a dialog, comes from the caller -->
  <div
    out:closeMenu|global
    {@attach popover({
      onClose: () => onClose(),
      onKey,
      onPlace: (placed) => {
        above = placed;
      },
      prefer,
      fit,
    })}
    {role}
    tabindex="-1"
    aria-label={label}
    onclick={(event) => event.stopPropagation()}
    {onkeydown}
    class={anchored(above)}
  >
    {@render children()}
  </div>
{/if}
