<!-- @component Pull to refresh on touch: pulling the list down from the top of the page draws a small round spinner out from under the header, and letting go past the mark reloads the view from Gmail, as Android's list does. It also stops the browser's own pull-to-reload while the list shows. -->
<script lang="ts">
  import { prefersReducedMotion } from "svelte/motion";
  import { getMailState } from "../mail-state.svelte";
  import { tick } from "../thread-pull";
  import { lockDirection } from "./swipe";

  // How far the spinner travels before letting go refreshes, at most, and how much of the finger's travel it follows.
  const TRIGGER_PX = 72;
  const MAX_PX = 104;
  const DRAG_RATIO = 0.5;
  // The spinner's size, and where it rests hidden under the header.
  const SPINNER_PX = 36;
  const HIDDEN_PX = -SPINNER_PX - 8;
  // The arc's circumference (radius 8).
  const ARC = 2 * Math.PI * 8;
  // Touches on what floats over the list, or scrolls on its own, are never a pull.
  const NOT_THE_LIST =
    "dialog, [role=dialog], [role=menu], [aria-modal=true], form, [data-thread-view]";

  const mail = getMailState();
  let pull = $state(0);
  let refreshing = $state(false);
  const progress = $derived(Math.min(1, pull / TRIGGER_PX));
  const reduced = $derived(prefersReducedMotion.current);
  // Reduced motion shows the spinner in place, fading in, rather than moving with the finger.
  const offset = $derived(
    reduced
      ? TRIGGER_PX + HIDDEN_PX
      : HIDDEN_PX + (refreshing ? TRIGGER_PX : pull),
  );

  $effect(() => {
    const root = document.documentElement;
    const before = root.style.overscrollBehaviorY;
    root.style.overscrollBehaviorY = "contain";
    let start: { x: number; y: number } | null = null;
    let pulling = false;

    const onStart = (event: TouchEvent) => {
      const touch = event.touches[0];
      start =
        event.touches.length === 1 &&
        touch &&
        window.scrollY <= 0 &&
        !refreshing &&
        !(event.target instanceof Element && event.target.closest(NOT_THE_LIST))
          ? { x: touch.clientX, y: touch.clientY }
          : null;
      pulling = false;
    };
    const onMove = (event: TouchEvent) => {
      const touch = event.touches[0];
      if (!start || !touch) return;
      const moveX = touch.clientX - start.x;
      const moveY = touch.clientY - start.y;
      if (!pulling) {
        const lock = lockDirection(moveX, moveY);
        if (lock === "pending") return;
        if (lock === "horizontal" || moveY < 0 || window.scrollY > 0) {
          start = null;
          return;
        }
        pulling = true;
      }
      if (event.cancelable) event.preventDefault();
      const wasArmed = pull >= TRIGGER_PX;
      pull = Math.max(0, Math.min(MAX_PX, moveY * DRAG_RATIO));
      if (pull >= TRIGGER_PX !== wasArmed) tick();
    };
    const onEnd = () => {
      if (pulling) {
        if (pull >= TRIGGER_PX) {
          tick();
          refreshing = true;
          void mail.refresh(true).finally(() => {
            refreshing = false;
          });
        }
        pull = 0;
      }
      start = null;
      pulling = false;
    };

    window.addEventListener("touchstart", onStart, { passive: true });
    window.addEventListener("touchmove", onMove, { passive: false });
    window.addEventListener("touchend", onEnd);
    window.addEventListener("touchcancel", onEnd);
    return () => {
      root.style.overscrollBehaviorY = before;
      window.removeEventListener("touchstart", onStart);
      window.removeEventListener("touchmove", onMove);
      window.removeEventListener("touchend", onEnd);
      window.removeEventListener("touchcancel", onEnd);
    };
  });
</script>

{#if pull > 0 || refreshing}
  <div
    role={refreshing ? "status" : undefined}
    style:transform={`translate(-50%, ${offset}px)`}
    style:opacity={reduced && !refreshing ? progress : null}
    style:width={`${SPINNER_PX}px`}
    style:height={`${SPINNER_PX}px`}
    class={[
      "fixed left-1/2 top-safe-16 z-[9] flex items-center justify-center rounded-full bg-surface-raised shadow-pop text-accent",
      pull === 0 && "transition-transform duration-200 ease-out",
    ]}
  >
    {#if refreshing}
      <span class="sr-only">Refreshing</span>
    {/if}
    <svg
      viewBox="0 0 20 20"
      width="20"
      height="20"
      aria-hidden="true"
      class={[refreshing && "animate-spin motion-reduce:animate-none"]}
      style:transform={refreshing ? null : `rotate(${progress * 270}deg)`}
    >
      <circle
        cx="10"
        cy="10"
        r="8"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-dasharray={`${(refreshing ? 0.7 : progress * 0.8) * ARC} ${ARC}`}
        opacity={refreshing || progress >= 1 ? 1 : 0.6}
      />
    </svg>
  </div>
{/if}
