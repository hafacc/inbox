<!-- @component A round 56 px button in a bottom corner of a phone's screen (the side every round button shares, `cornerSide`). With `menu`, a press grows its actions out of it as a two-ring round menu: slide to one and let go, or tap to keep the menu open and tap one. Without, a tap runs `onTap`. A press dragged down from it, before reaching a wedge, lifts the button to follow the finger; letting go snaps it to the nearer bottom corner. -->
<script lang="ts" module>
  import { MediaQuery } from "svelte/reactivity";

  // One for every button: each instance would add its own media listener.
  const touch = new MediaQuery("hover: none");
</script>

<script lang="ts">
  import {
    cornerDragStarts,
    cornerHoldWedges,
    cornerSide as nearerSide,
  } from "@inbox/shared/hold-menu";
  import type { Snippet } from "svelte";
  import { tick } from "svelte";
  import { prefersReducedMotion } from "svelte/motion";
  import { cornerSide } from "../corner-side.svelte";
  import { swallowReleaseClick } from "../hold-menu";
  import {
    type HoldMenuRequest,
    type HoldTone,
    holdMenu,
  } from "../hold-menu.svelte";
  import { isEditableTarget } from "../keyboard";
  import type { IconType } from "./icons";
  import { portal } from "./phone";

  /** One action of a corner button's menu. */
  type CornerItem = {
    /** what it does, handed to `onPick` */
    id: string;
    /** its name */
    label: string;
    /** its glyph */
    icon: IconType;
    /** its colors */
    tone?: HoldTone;
  };

  let {
    label,
    title,
    icon: Icon,
    bottom,
    hidden = false,
    menu,
    menuLabel = label,
    onTap,
    onPick,
    above,
  }: {
    /** its name, read out */
    label: string;
    /** its tooltip, when it has a key */
    title?: string;
    /** its glyph */
    icon: IconType;
    /** how far its stack sits from the window's bottom, as CSS */
    bottom: string;
    /** whether it steps aside while the list scrolls down */
    hidden?: boolean;
    /** its actions, the ring next to it and the ring beyond, each from the top; none for a button that just taps */
    menu?: () => { inner: CornerItem[]; outer: CornerItem[] };
    /** the menu's name, read out */
    menuLabel?: string;
    /** a tap, for a button with no menu */
    onTap?: () => void;
    /** run the menu's action `id` */
    onPick?: (id: string) => void;
    /** what stacks above it, moving with it (New todo) */
    above?: Snippet;
  } = $props();

  // How long after a lift lets go its trailing click is swallowed.
  const RELEASE_CLICK_MS = 400;
  // How long it takes to ease into its corner.
  const SNAP_MS = 200;

  let button = $state<HTMLButtonElement>();
  let stack = $state<HTMLDivElement>();
  let request = $state<HoldMenuRequest | null>(null);
  let press: {
    pointerId: number;
    x: number;
    y: number;
    reachedWedge: boolean;
  } | null = null;
  // While lifted: how far the finger has moved from where it pressed.
  let drag = $state<{ x: number; y: number } | null>(null);
  const expanded = $derived(request !== null && holdMenu.current === request);
  // A text field has focus on a touch screen, so its keyboard is up: the button keeps out of the
  // way below it, as on Android, rather than riding above it over what is being typed.
  let typing = $state(false);

  $effect(() => {
    const update = () => {
      // Focus moving between fields passes through the page's body.
      queueMicrotask(() => {
        typing = isEditableTarget(document.activeElement);
      });
    };
    update();
    document.addEventListener("focusin", update);
    document.addEventListener("focusout", update);
    return () => {
      document.removeEventListener("focusin", update);
      document.removeEventListener("focusout", update);
    };
  });

  function openMenu(
    pointerId: number | null,
    at: { x: number; y: number },
    focusFirst: boolean,
  ) {
    if (menu === undefined || button === undefined) return;
    const { inner, outer } = menu();
    const box = button.getBoundingClientRect();
    const wedges = cornerHoldWedges(
      inner.map((item) => item.id),
      outer.map((item) => item.id),
      cornerSide.side,
    );
    const items = [...inner, ...outer];
    const shown: HoldMenuRequest = {
      x: box.left + box.width / 2,
      y: box.top + box.height / 2,
      items: wedges.map((wedge, index) => {
        const item = items[index];
        return {
          ...wedge,
          label: item?.label ?? "",
          icon: item?.icon ?? Icon,
          tone: item?.tone,
        };
      }),
      pill: "Slide to an action",
      stickyPill: "Tap an action",
      label: menuLabel,
      pointerId,
      press: at,
      focusFirst,
      onPick: (id) => onPick?.(id),
      onClose: () => {
        if (request === shown) request = null;
      },
    };
    request = shown;
    holdMenu.open(shown);
  }

  function onPointerDown(event: PointerEvent) {
    if (event.button !== 0 || button === undefined) return;
    endPress();
    try {
      button.setPointerCapture(event.pointerId);
    } catch {
      // A pointer the browser no longer tracks can't be captured; the press still works.
    }
    const at = { x: event.clientX, y: event.clientY };
    press = { pointerId: event.pointerId, ...at, reachedWedge: false };
    if (menu !== undefined) openMenu(event.pointerId, at, false);
  }

  function onPointerMove(event: PointerEvent) {
    const held = press;
    if (held === null || held.pointerId !== event.pointerId) return;
    if (drag === null) {
      held.reachedWedge ||=
        holdMenu.current === request && holdMenu.active >= 0;
      if (!cornerDragStarts(held.y, event.clientY, held.reachedWedge)) return;
      // Down, away from the wedges: the menu fades away and the button follows the finger.
      if (menu !== undefined && holdMenu.current === request)
        holdMenu.close(true);
      navigator.vibrate?.(15);
    }
    drag = { x: event.clientX - held.x, y: event.clientY - held.y };
  }

  async function onPointerUp(event: PointerEvent, canceled: boolean) {
    const held = press;
    if (held === null || held.pointerId !== event.pointerId) return;
    endPress();
    if (drag === null) return;
    swallowReleaseClick(window, RELEASE_CLICK_MS);
    const before = stack?.getBoundingClientRect();
    if (!canceled) cornerSide.set(nearerSide(event.clientX, window.innerWidth));
    drag = null;
    await tick();
    const after = stack?.getBoundingClientRect();
    if (stack === undefined || before === undefined || after === undefined) {
      return;
    }
    if (prefersReducedMotion.current) return;
    // Drawn where the finger left it, then eased into its corner.
    stack.animate(
      [
        {
          transform: `translate(${before.left - after.left}px, ${before.top - after.top}px)`,
        },
        { transform: "none" },
      ],
      { duration: SNAP_MS, easing: "ease-out" },
    );
  }

  function endPress() {
    press = null;
  }

  function onClick() {
    // A press already acted on the pointer's way down and up; this is a key's or a screen reader's.
    if (menu !== undefined) {
      if (holdMenu.current === null) openMenu(null, { x: 0, y: 0 }, true);
    } else {
      onTap?.();
    }
  }

  const offset = $derived(
    drag !== null ? `translate(${drag.x}px, ${drag.y}px)` : undefined,
  );
</script>

<div class="contents">
  <div
    {@attach portal}
    bind:this={stack}
    style:bottom
    style:transform={offset}
    class={[
      "sm:hidden",
      "fixed z-[25] flex flex-col gap-3",
      cornerSide.side === "left"
        ? "left-safe-4 items-start"
        : "right-safe-4 items-end",
      drag === null && "transition-[translate,opacity] duration-200 ease-out",
      (hidden || (typing && touch.current)) &&
        drag === null &&
        "translate-y-[calc(100%+6rem+var(--safe-bottom))] opacity-0 pointer-events-none",
    ]}
  >
    {@render above?.()}
    <button
      bind:this={button}
      type="button"
      aria-label={label}
      {title}
      aria-haspopup={menu !== undefined ? "menu" : undefined}
      aria-expanded={menu !== undefined ? expanded : undefined}
      onpointerdown={onPointerDown}
      onpointermove={onPointerMove}
      onpointerup={(event) => onPointerUp(event, false)}
      onpointercancel={(event) => onPointerUp(event, true)}
      oncontextmenu={(event) => event.preventDefault()}
      onclick={onClick}
      class={[
        "w-14 h-14 flex items-center justify-center rounded-full bg-accent text-on-accent touch-none select-none [-webkit-touch-callout:none] hover:bg-accent-strong transition-[background-color,scale,box-shadow] duration-150",
        drag !== null
          ? "scale-110 shadow-[0_12px_28px_-8px_rgb(0_0_0/0.45)]"
          : "shadow-pop",
      ]}
    >
      <Icon class="w-6 h-6" aria-hidden="true" />
    </button>
  </div>
</div>
