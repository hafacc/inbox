<!-- @component Pull to refresh: pulling the list down from the top of the page draws a small round spinner out from under the header, and letting go past the mark reloads the view from Gmail, as Android's list does. It also stops the browser's own pull-to-reload and bounce at the page's ends while the list shows. -->
<script lang="ts">
  import { prefersReducedMotion } from "svelte/motion";
  import {
    LIST_PULL_THRESHOLD,
    listPullOffset,
    listPullProgress,
    refreshesByPull,
  } from "../list-pull";
  import { getMailState } from "../mail-state.svelte";
  import { tick } from "../thread-pull";
  import { lockDirection } from "./swipe";

  // The spinner's size, and where it rests hidden under the header.
  const SPINNER_PX = 36;
  const HIDDEN_PX = -SPINNER_PX - 8;
  // The arc's circumference (radius 8).
  const ARC = 2 * Math.PI * 8;
  // How long the spinner takes to go back under the header, matching its transition.
  const SETTLE_MS = 200;
  // Touches on what floats over the list, or scrolls on its own, are never a pull.
  const NOT_THE_LIST =
    "dialog, [role=dialog], [role=menu], [aria-modal=true], form, [data-thread-view]";
  // While one of these is open anywhere, the list doesn't pull.
  const LAYER_OPEN =
    "dialog[open], [role=dialog], [role=menu], [aria-modal=true]";

  const mail = getMailState();
  // How far the finger has gone down past the top, in pixels.
  let travel = $state(0);
  let dragging = $state(false);
  let refreshing = $state(false);
  let settling = $state(false);
  const progress = $derived(listPullProgress(travel));
  const reduced = $derived(prefersReducedMotion.current);
  // Reduced motion shows the spinner in place, fading in and out, rather than moving.
  const offset = $derived(
    reduced
      ? LIST_PULL_THRESHOLD + HIDDEN_PX
      : HIDDEN_PX + (refreshing ? LIST_PULL_THRESHOLD : listPullOffset(travel)),
  );
  const opacity = $derived(
    reduced && !refreshing ? (dragging ? progress : 0) : null,
  );

  $effect(() => {
    // The page scrolls, not an element in it; browsers differ on which of these holds its bounce.
    const ends = [document.documentElement, document.body];
    const before = ends.map((end) => end.style.overscrollBehaviorY);
    for (const end of ends) end.style.overscrollBehaviorY = "none";
    let touchStart: { x: number; y: number } | null = null;
    let touchPulling = false;
    let settleTimer: ReturnType<typeof setTimeout> | undefined;

    // Within a pixel, as a zoomed page may stop a fraction short of 0.
    const atTop = () => window.scrollY <= 1;
    const mayStart = (target: EventTarget | null) =>
      !refreshing &&
      mail.list.menu === null &&
      !(target instanceof Element && target.closest(NOT_THE_LIST)) &&
      document.querySelector(LAYER_OPEN) === null;

    const settle = () => {
      settling = true;
      clearTimeout(settleTimer);
      settleTimer = setTimeout(() => {
        settling = false;
      }, SETTLE_MS);
    };
    const follow = (next: number) => {
      const wasArmed = refreshesByPull(travel);
      if (next === 0 && travel > 0) settle();
      travel = next;
      dragging = next > 0;
      if (refreshesByPull(next) !== wasArmed) tick();
    };
    const letGo = () => {
      if (refreshesByPull(travel)) {
        tick();
        refreshing = true;
        void mail.refresh(true).finally(() => {
          refreshing = false;
          settle();
        });
      } else if (travel > 0) {
        settle();
      }
      travel = 0;
      dragging = false;
    };

    const onTouchStart = (event: TouchEvent) => {
      const touch = event.touches[0];
      const selecting = document.getSelection()?.isCollapsed === false;
      touchStart =
        event.touches.length === 1 &&
        touch &&
        atTop() &&
        !selecting &&
        mayStart(event.target)
          ? { x: touch.clientX, y: touch.clientY }
          : null;
      touchPulling = false;
    };
    const onTouchMove = (event: TouchEvent) => {
      const touch = event.touches[0];
      if (!touchStart || !touch) return;
      const moveX = touch.clientX - touchStart.x;
      const moveY = touch.clientY - touchStart.y;
      if (!touchPulling) {
        const lock = lockDirection(moveX, moveY);
        if (lock === "pending") return;
        if (lock === "horizontal" || moveY < 0 || !atTop()) {
          touchStart = null;
          return;
        }
        touchPulling = true;
      }
      if (event.cancelable) event.preventDefault();
      follow(Math.max(0, moveY));
    };
    const onTouchEnd = () => {
      if (touchPulling) letGo();
      touchStart = null;
      touchPulling = false;
    };

    window.addEventListener("touchstart", onTouchStart, { passive: true });
    window.addEventListener("touchmove", onTouchMove, { passive: false });
    window.addEventListener("touchend", onTouchEnd);
    window.addEventListener("touchcancel", onTouchEnd);
    return () => {
      ends.forEach((end, index) => {
        end.style.overscrollBehaviorY = before[index] ?? "";
      });
      clearTimeout(settleTimer);
      window.removeEventListener("touchstart", onTouchStart);
      window.removeEventListener("touchmove", onTouchMove);
      window.removeEventListener("touchend", onTouchEnd);
      window.removeEventListener("touchcancel", onTouchEnd);
    };
  });
</script>

{#if dragging || refreshing || settling}
  <div
    role={refreshing ? "status" : undefined}
    style:transform={`translate(-50%, ${offset}px)`}
    style:opacity
    style:width={`${SPINNER_PX}px`}
    style:height={`${SPINNER_PX}px`}
    class={[
      "fixed left-1/2 top-safe-16 z-[9] flex items-center justify-center rounded-full bg-surface-raised shadow-pop text-accent",
      !dragging && "transition-[transform,opacity] duration-200 ease-out",
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
