<!-- @component The view's name in the phone's top bar. A tap scrolls the list back to the top; a hold opens the round menu of Inbox, Snoozed, Done and Todos under it, the current view marked, to switch views (picking the current one scrolls to the top). The menu key or a right click opens the menu too, and screen readers get it as a second button, "Switch view". -->
<script lang="ts">
  import { viewHoldWedges } from "@inbox/shared/hold-menu";
  import CircleCheck from "@lucide/svelte/icons/circle-check";
  import Clock from "@lucide/svelte/icons/clock";
  import Inbox from "@lucide/svelte/icons/inbox";
  import ListTodo from "@lucide/svelte/icons/list-todo";
  import { onDestroy } from "svelte";
  import { prefersReducedMotion } from "svelte/motion";
  import { HoldPress } from "../hold-menu";
  import { type HoldMenuItem, holdMenu } from "../hold-menu.svelte";
  import { getMailState } from "../mail-state.svelte";
  import { VIEW_TITLES } from "../views";

  let { title }: { title: string } = $props();

  const mail = getMailState();

  // The views on the menu, in the side menu's order.
  const VIEWS = [
    { kind: "inbox", icon: Inbox },
    { kind: "snoozed", icon: Clock },
    { kind: "done", icon: CircleCheck },
    { kind: "todos", icon: ListTodo },
  ] as const;
  type MenuView = (typeof VIEWS)[number]["kind"];

  const press = new HoldPress();
  let button = $state<HTMLButtonElement>();
  let held = $state(false);

  onDestroy(() => press.cancel());

  function toTop() {
    window.scrollTo({
      top: 0,
      left: 0,
      behavior: prefersReducedMotion.current ? "instant" : "smooth",
    });
  }

  function pick(kind: MenuView) {
    if (mail.view.kind === kind) toTop();
    else mail.setView({ kind });
  }

  function openMenu(
    pointerId: number | null,
    at: { x: number; y: number },
    focusFirst: boolean,
  ) {
    if (button === undefined) return;
    const box = button.getBoundingClientRect();
    const shown = VIEWS.filter(
      (view) => view.kind !== "todos" || mail.todos.enabled,
    );
    const current = mail.view.kind;
    const items: HoldMenuItem[] = viewHoldWedges(
      shown.map((view) => view.kind),
    ).map((wedge, index) => ({
      ...wedge,
      label: VIEW_TITLES[wedge.id],
      icon: shown[index]?.icon ?? Inbox,
      tone: wedge.id === current ? "on" : "plain",
    }));
    held = true;
    holdMenu.open({
      x: box.left + box.width / 2,
      y: box.top + box.height / 2,
      items,
      pill: "Slide to a view",
      stickyPill: "Tap a view",
      label: "Switch view",
      pointerId,
      press: at,
      focusFirst,
      onPick: (id) => pick(id as MenuView),
      onClose: () => {
        held = false;
      },
    });
  }

  function onPointerDown(
    event: PointerEvent & { currentTarget: HTMLButtonElement },
  ) {
    if (event.button !== 0) return;
    const pointerId = event.pointerId;
    const at = { x: event.clientX, y: event.clientY };
    press.start(pointerId, at.x, at.y, () => openMenu(pointerId, at, false));
  }

  function onContextMenu(event: MouseEvent) {
    event.preventDefault();
    // A touch held this long already opened the menu.
    if (press.pending || holdMenu.current !== null) return;
    // The menu key sends no position.
    const fromKeyboard = event.clientX === 0 && event.clientY === 0;
    openMenu(null, { x: event.clientX, y: event.clientY }, fromKeyboard);
  }
</script>

<div class="flex-1 min-w-0 flex items-center">
  <button
    bind:this={button}
    type="button"
    aria-label={`${title}, go to top`}
    title="Go to top (hold to switch view)"
    onclick={toTop}
    onpointerdown={onPointerDown}
    onpointermove={(event) =>
      press.move(event.pointerId, event.clientX, event.clientY)}
    onpointerup={() => press.cancel()}
    onpointercancel={() => press.cancel()}
    oncontextmenu={onContextMenu}
    class={[
      "max-w-full min-w-0 h-12 px-1 rounded-xl text-left truncate text-lg font-semibold tracking-[-0.01em] select-none [-webkit-touch-callout:none] hover:bg-surface-hover active:bg-surface-hover",
      held && "bg-surface-hover",
    ]}
  >
    {title}
  </button>
  <button
    type="button"
    tabindex="-1"
    aria-haspopup="menu"
    onclick={() => openMenu(null, { x: 0, y: 0 }, true)}
    class="sr-only"
  >
    Switch view
  </button>
</div>
