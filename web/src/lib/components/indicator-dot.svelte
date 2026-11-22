<!-- @component A small dot on the top-right corner of an icon or avatar: accent for unread mail, warning for something that needs attention. It grows in and shrinks out. -->
<script lang="ts">
  import type { Snippet } from "svelte";
  import { cubicOut } from "svelte/easing";
  import type { ClassValue } from "svelte/elements";
  import { prefersReducedMotion } from "svelte/motion";
  import { scale } from "svelte/transition";

  let {
    show,
    tone,
    ring = "var(--color-sidebar)",
    label,
    round = false,
    class: dotClass,
    children,
  }: {
    /** whether the dot is shown */
    show: boolean;
    /** its color: accent for unread mail, warning for a problem */
    tone: "accent" | "warning";
    /** the color of the ring that sets it off, matching what's behind it */
    ring?: string;
    /** what the dot means, for screen readers; without it the dot is hidden from them */
    label?: string;
    /** whether it sits on a circle such as an avatar, so it moves in onto the circle's edge */
    round?: boolean;
    /** extra classes on the dot */
    class?: ClassValue;
    /** the icon or avatar the dot sits on */
    children: Snippet;
  } = $props();

  const TONES = { accent: "bg-accent", warning: "bg-snooze" } as const;
  const duration = $derived(prefersReducedMotion.current ? 0 : 150);
</script>

<span class="relative inline-flex shrink-0">
  {@render children()}
  {#if show}
    <span
      transition:scale={{ duration, start: 0.3, easing: cubicOut }}
      class={[
        "absolute w-2 h-2 rounded-full pointer-events-none",
        round ? "top-0 right-0" : "-top-1 -right-1",
        TONES[tone],
        dotClass,
      ]}
      style:box-shadow={`0 0 0 2px ${ring}`}
      aria-hidden="true"
    ></span>
    {#if label}
      <span class="sr-only">{label}</span>
    {/if}
  {/if}
</span>
