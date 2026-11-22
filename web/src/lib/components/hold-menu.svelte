<!-- @component The page's round menu (see `holdMenu`), the one every round menu uses: a disc of wedges on one or two rings, with a pill naming the wedge under the finger. Slide onto a wedge and let go to pick it; let go without sliding and it stays open for a tap, the arrow keys and Enter. Escape, Back or a tap away closes it. -->
<script lang="ts">
  import {
    hitWedge,
    holdMenuKey,
    pillTop,
    polar,
    slidAway,
  } from "@inbox/shared/hold-menu";
  import { untrack } from "svelte";
  import type { Attachment } from "svelte/attachments";
  import { pushBack } from "../back-stack.svelte";
  import { pillLeft, swallowReleaseClick, wedgePath } from "../hold-menu";
  import {
    type HoldMenuItem,
    type HoldMenuRequest,
    holdMenu,
  } from "../hold-menu.svelte";
  import { portal } from "./phone";

  // How long after the press lets go its trailing click is swallowed.
  const RELEASE_CLICK_MS = 400;
  // How long a menu takes to fade away when a corner button lifts out of it.
  const FADE_MS = 150;
  // The pill keeps below this many pixels from the window's top.
  const PILL_TOP_LIMIT = 36;

  const menu = $derived(holdMenu.current);
  let sticky = $state(false);
  let shown = $state(false);
  let wedgeElements = $state<SVGGElement[]>([]);

  function buzz() {
    navigator.vibrate?.(8);
  }

  function setActive(index: number) {
    if (index === holdMenu.active) return;
    holdMenu.active = index;
    if (index >= 0) buzz();
  }

  function pick(request: HoldMenuRequest, index: number) {
    const item = request.items[index];
    holdMenu.close();
    if (item) request.onPick(item.id);
  }

  function track(request: HoldMenuRequest): Attachment<HTMLElement> {
    return () => untrack(() => setUp(request));
  }

  function fadeAway(request: HoldMenuRequest): Attachment<HTMLElement> {
    return () => {
      const reduced = window.matchMedia(
        "(prefers-reduced-motion: reduce)",
      ).matches;
      const timer = window.setTimeout(
        () => {
          if (holdMenu.fading === request) holdMenu.fading = null;
        },
        reduced ? 0 : FADE_MS,
      );
      return () => window.clearTimeout(timer);
    };
  }

  // Runs once per menu shown; returns what takes it down.
  function setUp(request: HoldMenuRequest): () => void {
    sticky = request.pointerId === null;
    shown = false;
    buzz();
    const restore =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    const frame = requestAnimationFrame(() => {
      shown = true;
      if (request.focusFirst) wedgeElements[0]?.focus();
    });
    const releaseBack = pushBack(() => holdMenu.close());

    // The disc may sit away from the press (a row's hold opens it on the dot), so a wedge
    // under the finger lights only once the finger slides; letting go before that waits for a tap.
    let slid = false;
    const onMove = (event: PointerEvent) => {
      if (sticky && event.pointerType === "touch") return;
      if (!sticky && event.pointerId !== request.pointerId) return;
      if (!sticky) {
        slid ||= slidAway(request.press, event.clientX, event.clientY);
        if (!slid) return;
      }
      setActive(
        hitWedge(
          request.items,
          request.x,
          request.y,
          event.clientX,
          event.clientY,
        ),
      );
    };
    const onUp = (event: PointerEvent) => {
      if (sticky || event.pointerId !== request.pointerId) return;
      // It outlives the menu: a wedge picked on release closes it before the click comes.
      swallowReleaseClick(window, RELEASE_CLICK_MS);
      const near = !slidAway(request.press, event.clientX, event.clientY);
      if (holdMenu.active >= 0) pick(request, holdMenu.active);
      else if (near) sticky = true;
      else holdMenu.close();
    };
    const onCancel = (event: PointerEvent) => {
      if (!sticky && event.pointerId === request.pointerId) holdMenu.close();
    };
    // The slide is the menu's, not a scroll of the page under it.
    const onTouchMove = (event: TouchEvent) => {
      if (!sticky && event.cancelable) event.preventDefault();
    };
    const onKey = (event: KeyboardEvent) => {
      const focused = wedgeElements.indexOf(
        document.activeElement as SVGGElement,
      );
      const action = holdMenuKey(
        event.key,
        event.shiftKey,
        focused,
        request.items.length,
      );
      event.stopImmediatePropagation();
      if (action.kind === "none") return;
      event.preventDefault();
      if (action.kind === "close") holdMenu.close();
      else if (action.kind === "focus") wedgeElements[action.index]?.focus();
      else pick(request, action.index);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onCancel);
    window.addEventListener("touchmove", onTouchMove, { passive: false });
    window.addEventListener("keydown", onKey, true);
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onCancel);
      window.removeEventListener("touchmove", onTouchMove);
      window.removeEventListener("keydown", onKey, true);
      releaseBack();
      if (request.focusFirst && restore?.isConnected) {
        restore.focus({ preventScroll: true });
      }
    };
  }

  function wedgeFill(item: HoldMenuItem, lit: boolean): string {
    if (!lit) return "fill-surface-raised stroke-border-strong";
    else if (item.tone === "danger") return "fill-danger stroke-danger";
    else if (item.tone === "done") return "fill-done stroke-done";
    else return "fill-accent stroke-accent";
  }

  function iconColor(item: HoldMenuItem, lit: boolean): string {
    if (lit) {
      if (item.tone === "danger") return "text-on-danger";
      else if (item.tone === "done") return "text-on-done";
      else return "text-on-accent";
    } else if (item.tone === "danger") return "text-danger";
    else if (item.tone === "done") return "text-done";
    else if (item.tone === "on") return "text-accent";
    else return "text-text-secondary";
  }
</script>

{#snippet disc(
  request: HoldMenuRequest,
  active: number,
  waiting: boolean,
  grown: boolean,
)}
  {@const activeItem = request.items[active]}
  <svg
    role="none"
    class={[
      "absolute inset-0 w-full h-full overflow-visible transition-[scale,opacity] duration-200 ease-[cubic-bezier(0.2,0.8,0.2,1)] motion-reduce:transition-none",
      grown ? "opacity-100 scale-100" : "opacity-0 scale-[0.15]",
    ]}
    style:transform-origin={`${request.x}px ${request.y}px`}
  >
    {#each request.items as item, index (item.id)}
      {@const lit = index === active}
      {@const [iconX, iconY] = polar(
        request.x,
        request.y,
        (item.ring.inner + item.ring.outer) / 2,
        (item.from + item.to) / 2,
      )}
      <g
        bind:this={wedgeElements[index]}
        role="menuitem"
        tabindex={waiting ? 0 : -1}
        aria-label={item.label}
        onfocus={() => (holdMenu.active = index)}
        class={["outline-none group/wedge", waiting && "cursor-pointer"]}
      >
        <path
          d={wedgePath(request.x, request.y, item)}
          class={[
            "stroke-1 transition-[fill] duration-100 group-focus-visible/wedge:stroke-focus group-focus-visible/wedge:stroke-[3px]",
            wedgeFill(item, lit),
          ]}
        />
        <item.icon
          x={iconX - 10}
          y={iconY - 10}
          size={20}
          strokeWidth={2.2}
          aria-hidden="true"
          class={iconColor(item, lit)}
        />
      </g>
    {/each}
  </svg>
  <span
    aria-hidden="true"
    style:left={`${request.x}px`}
    style:top={`${request.y}px`}
    class="absolute -ml-5 -mt-5 w-10 h-10 rounded-full border-2 border-accent bg-accent-soft/80 pointer-events-none"
  ></span>
  <div
    aria-live="polite"
    style:left={`${pillLeft(request.x, window.innerWidth)}px`}
    style:top={`${pillTop(request.x, request.y, request.items, PILL_TOP_LIMIT)}px`}
    class="absolute -translate-x-1/2 max-w-[calc(100vw-24px)] truncate px-3.5 py-1.5 rounded-full bg-snackbar text-on-snackbar text-sm font-medium shadow-pop pointer-events-none"
  >
    {activeItem
      ? activeItem.label
      : waiting
        ? (request.stickyPill ?? request.pill)
        : request.pill}
  </div>
{/snippet}

{#if menu}
  {@const request = menu}
  {#key request}
    <div class="contents">
      <!-- svelte-ignore a11y_click_events_have_key_events: keys are handled on the window while it is open -->
      <!-- biome-ignore lint/a11y/useKeyWithClickEvents: keys are handled on the window while it is open -->
      <div
        {@attach portal}
        {@attach track(request)}
        role="menu"
        tabindex="-1"
        aria-label={request.label}
        onclick={(event) => {
          if (!sticky) return;
          const index = hitWedge(
            request.items,
            request.x,
            request.y,
            event.clientX,
            event.clientY,
          );
          if (index >= 0) pick(request, index);
          else holdMenu.close();
        }}
        class={[
          "fixed inset-0 z-50 select-none [-webkit-touch-callout:none] outline-none transition-colors duration-150 motion-reduce:transition-none",
          shown ? "bg-scrim/30" : "bg-transparent",
        ]}
      >
        {@render disc(request, holdMenu.active, sticky, shown)}
      </div>
    </div>
  {/key}
{:else if holdMenu.fading}
  {@const request = holdMenu.fading}
  {#key request}
    <div class="contents">
      <div
        {@attach portal}
        {@attach fadeAway(request)}
        aria-hidden="true"
        class="fixed inset-0 z-50 pointer-events-none bg-scrim/30 animate-[hold-fade_150ms_ease-out_forwards] motion-reduce:hidden"
      >
        {@render disc(request, -1, false, true)}
      </div>
    </div>
  {/key}
{/if}

<style>
  @keyframes -global-hold-fade {
    to {
      opacity: 0;
    }
  }
</style>
