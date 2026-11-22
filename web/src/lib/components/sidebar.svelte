<!-- @component The mailbox list: hideable to a rail of icons that widens again on hover, like latr's; at phone width the same list fills the slide-in side panel (`drawer`), without Compose and New todo, which float over the list there. -->
<script lang="ts">
  import { outboxShownAt } from "@inbox/shared/send-suggest";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import CircleCheck from "@lucide/svelte/icons/circle-check";
  import Clock from "@lucide/svelte/icons/clock";
  import ClockArrowUp from "@lucide/svelte/icons/clock-arrow-up";
  import FileIcon from "@lucide/svelte/icons/file";
  import Inbox from "@lucide/svelte/icons/inbox";
  import ListPlus from "@lucide/svelte/icons/list-plus";
  import ListTodo from "@lucide/svelte/icons/list-todo";
  import OctagonAlert from "@lucide/svelte/icons/octagon-alert";
  import PanelLeftClose from "@lucide/svelte/icons/panel-left-close";
  import PanelLeftOpen from "@lucide/svelte/icons/panel-left-open";
  import PenLine from "@lucide/svelte/icons/pen-line";
  import Send from "@lucide/svelte/icons/send";
  import { onMount } from "svelte";
  import { cubicOut } from "svelte/easing";
  import { prefersReducedMotion } from "svelte/motion";
  import { slide } from "svelte/transition";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";
  import { keyTitle } from "../shortcuts";
  import { sameView, VIEW_TITLES, type View } from "../views";
  import type { IconType } from "./icons";
  import IndicatorDot from "./indicator-dot.svelte";
  import LabelMark from "./label-mark.svelte";
  import SidebarAccount from "./sidebar-account.svelte";

  let {
    collapsed,
    onToggleCollapsed,
    onSignOut,
    onSettings,
    drawer = false,
    onNavigate,
  }: {
    collapsed: boolean;
    onToggleCollapsed: () => void;
    onSignOut: () => void;
    onSettings: () => void;
    /** fills the phone side panel instead of standing at the left edge */
    drawer?: boolean;
    /** called before a mailbox or label is shown from the list */
    onNavigate?: () => void;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();

  const MAILBOXES: {
    kind: keyof typeof VIEW_TITLES;
    icon: IconType;
  }[] = [
    { kind: "inbox", icon: Inbox },
    { kind: "snoozed", icon: Clock },
    { kind: "outbox", icon: ClockArrowUp },
    { kind: "done", icon: CircleCheck },
    { kind: "todos", icon: ListTodo },
    { kind: "sent", icon: Send },
    { kind: "drafts", icon: FileIcon },
    { kind: "spam", icon: OctagonAlert },
  ];

  const counts = $derived<Partial<Record<keyof typeof VIEW_TITLES, number>>>({
    inbox: mail.inboxRows.length,
    snoozed: mail.snoozedCount,
    outbox: hub.pending.length + hub.held.length,
    drafts: mail.draftCount,
    todos: mail.activeTodos.length,
  });

  // Bumped when a held-up send reaches the moment the Outbox should be listed.
  let outboxTick = $state(0);
  const outboxTiming = $derived.by(() => {
    void outboxTick;
    const now = Date.now();
    // A send held on the device counts as waiting for when it was meant to go.
    const held = hub.held.map(({ sendAt }) => ({ status: "waiting", sendAt }));
    return { now, shownAt: outboxShownAt([...hub.pending, ...held], now) };
  });
  const showOutbox = $derived(
    mail.view.kind === "outbox" || outboxTiming.shownAt === outboxTiming.now,
  );

  $effect(() => {
    const { now, shownAt } = outboxTiming;
    if (shownAt === null || shownAt <= now) return;
    const timer = setTimeout(() => outboxTick++, shownAt - now);
    return () => clearTimeout(timer);
  });

  const visibleMailboxes = $derived(
    MAILBOXES.filter(
      (mailbox) =>
        (mailbox.kind !== "outbox" || showOutbox) &&
        (mailbox.kind !== "todos" || mail.todos.enabled),
    ),
  );

  const LABELS_HIDDEN_KEY = "inbox:sidebar-labels-hidden";
  // Whether the Labels section is folded away, remembered on this device.
  let labelsHidden = $state(false);
  const motion = $derived(prefersReducedMotion.current ? 0 : 200);

  onMount(() => {
    try {
      labelsHidden = localStorage.getItem(LABELS_HIDDEN_KEY) === "1";
    } catch {
      // Storage can be blocked; the section just starts open.
    }
  });

  function toggleLabels() {
    labelsHidden = !labelsHidden;
    try {
      localStorage.setItem(LABELS_HIDDEN_KEY, labelsHidden ? "1" : "0");
    } catch {
      // Not remembered, but still folded for now.
    }
  }

  // Rows are finger-sized in the phone side panel.
  const itemHeight = $derived(drawer ? "h-12" : "h-10");

  const expandedOnly =
    "group-data-[collapsed=true]/sidebar:hidden group-data-[collapsed=true]/sidebar:group-hover/sidebar:inline";

  function isActive(view: View): boolean {
    return sameView(mail.view, view);
  }

  function show(view: View): void {
    onNavigate?.();
    mail.setView(view);
  }
</script>

<aside
  data-collapsed={drawer ? false : collapsed}
  class={drawer
    ? "group/sidebar flex flex-col h-full bg-sidebar overflow-hidden px-3 pb-safe-4 pt-safe"
    : "group/sidebar hidden sm:flex flex-col fixed inset-y-0 left-0 z-20 bg-sidebar transition-[width,box-shadow] duration-200 ease-out w-60 data-[collapsed=true]:w-16 data-[collapsed=true]:hover:w-60 data-[collapsed=true]:hover:shadow-raised overflow-hidden px-3 pb-safe-4"}
>
  <div
    class="h-16 shrink-0 flex items-center gap-3 px-3 group-data-[collapsed=true]/sidebar:px-0 group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-3"
  >
    <span
      class={[
        "w-7 h-7 shrink-0 rounded-full bg-accent text-on-accent items-center justify-center flex",
        "group-data-[collapsed=true]/sidebar:hidden group-data-[collapsed=true]/sidebar:group-hover/sidebar:flex",
      ]}
      aria-hidden="true"
    >
      <Inbox class="w-4 h-4" />
    </span>
    <span class={["text-base font-semibold tracking-[-0.01em]", expandedOnly]}>
      Inbox
    </span>
    <button
      type="button"
      hidden={drawer}
      onclick={onToggleCollapsed}
      class="ml-auto group-data-[collapsed=true]/sidebar:ml-0 group-data-[collapsed=true]/sidebar:group-hover/sidebar:ml-auto w-7 h-7 group-data-[collapsed=true]/sidebar:w-10 group-data-[collapsed=true]/sidebar:h-10 group-data-[collapsed=true]/sidebar:group-hover/sidebar:w-7 group-data-[collapsed=true]/sidebar:group-hover/sidebar:h-7 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-sidebar-hover hover:text-text transition-colors"
      aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
      title={keyTitle(
        collapsed ? "Expand" : "Collapse",
        "[",
        hub.settings.shortcuts,
      )}
    >
      {#if collapsed}
        <PanelLeftOpen class="w-[18px] h-[18px]" />
      {:else}
        <PanelLeftClose class="w-[18px] h-[18px]" />
      {/if}
    </button>
  </div>

  <button
    type="button"
    hidden={drawer}
    onclick={() => hub.compose()}
    title={keyTitle("Compose", "c", hub.settings.shortcuts)}
    aria-keyshortcuts={hub.settings.shortcuts ? "c" : undefined}
    class="h-10 shrink-0 flex items-center gap-3 px-3 group-data-[collapsed=true]/sidebar:px-[calc((2.5rem-18px)/2)] group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-3 rounded-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong transition-colors"
  >
    <PenLine class="w-[18px] h-[18px] shrink-0" aria-hidden="true" />
    <span class={["flex-1 text-left", expandedOnly]}>Compose</span>
  </button>
  {#if mail.todos.enabled && !drawer}
    <button
      type="button"
      onclick={mail.newTodo}
      title={keyTitle("New todo", "n", hub.settings.shortcuts)}
      aria-keyshortcuts={hub.settings.shortcuts ? "n" : undefined}
      class="mt-2 h-10 shrink-0 flex items-center gap-3 px-3 group-data-[collapsed=true]/sidebar:px-[calc((2.5rem-18px)/2)] group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-3 rounded-full bg-surface text-text text-sm font-semibold shadow-pill hover:bg-surface-hover transition-colors"
    >
      <ListPlus class="w-[18px] h-[18px] shrink-0" aria-hidden="true" />
      <span class={["flex-1 text-left", expandedOnly]}>New todo</span>
    </button>
  {/if}

  <nav
    aria-label="Mailboxes"
    class="flex-1 min-h-0 -m-1 mt-3 p-1 overflow-y-auto overflow-x-hidden [scrollbar-width:none] [&_:focus-visible]:outline-offset-[-2px]"
  >
    <div class="flex flex-col gap-0.5">
      {#each visibleMailboxes as mailbox (mailbox.kind)}
        {@const view = { kind: mailbox.kind }}
        {@const active = isActive(view)}
        {@const count = counts[mailbox.kind]}
        <button
          type="button"
          onclick={() => show(view)}
          aria-current={active ? "page" : undefined}
          title={VIEW_TITLES[mailbox.kind]}
          class={[
            itemHeight,
            "relative w-full flex items-center gap-3 px-3 group-data-[collapsed=true]/sidebar:px-[calc((2.5rem-18px)/2)] group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-3 rounded-full text-left transition-[background-color,box-shadow,color] duration-150",
            active
              ? "bg-surface text-text font-semibold shadow-pill"
              : "text-text-secondary hover:bg-sidebar-hover hover:text-text",
          ]}
        >
          <IndicatorDot
            show={mailbox.kind === "inbox" && Boolean(count)}
            tone="accent"
            ring={active ? "var(--color-surface)" : "var(--color-sidebar)"}
            class="hidden group-data-[collapsed=true]/sidebar:block group-data-[collapsed=true]/sidebar:group-hover/sidebar:hidden"
          >
            <mailbox.icon class="w-[18px] h-[18px]" aria-hidden="true" />
          </IndicatorDot>
          <span class={["flex-1 truncate", expandedOnly]}>
            {VIEW_TITLES[mailbox.kind]}
          </span>
          {#if count}
            <span
              class={[
                "text-[13px] tabular-nums",
                mailbox.kind === "outbox" && hub.failedCount > 0
                  ? "text-danger font-semibold"
                  : active
                    ? ""
                    : "text-text-secondary",
                expandedOnly,
              ]}
              title={mailbox.kind === "outbox" && hub.failedCount > 0
                ? `${hub.failedCount} couldn't send`
                : undefined}
            >
              {count}
            </span>
          {/if}
        </button>
      {/each}
    </div>

    {#if mail.sidebarLabels.length > 0}
      <button
        type="button"
        aria-expanded={!labelsHidden}
        onclick={toggleLabels}
        class={[
          drawer ? "h-12" : "h-9",
          "group/labels w-full mt-4 px-3 flex items-center gap-1 rounded-full text-[13px] font-semibold text-text-secondary hover:text-text transition-colors",
          "group-data-[collapsed=true]/sidebar:hidden group-data-[collapsed=true]/sidebar:group-hover/sidebar:flex",
        ]}
      >
        Labels
        <ChevronRight
          class={[
            "w-3.5 h-3.5 transition-transform duration-200",
            !labelsHidden && "rotate-90",
          ]}
          aria-hidden="true"
        />
      </button>
      <hr
        class="hidden group-data-[collapsed=true]/sidebar:block group-data-[collapsed=true]/sidebar:group-hover/sidebar:hidden w-6 h-px border-0 bg-border my-3 mx-auto"
      >
      {#if !labelsHidden || collapsed}
        <div
          class="flex flex-col gap-0.5"
          transition:slide={{ duration: motion, easing: cubicOut }}
        >
          {#each mail.sidebarLabels as label (label.id)}
            {@const view = { kind: "label" as const, labelId: label.id }}
            {@const active = isActive(view)}
            {@const count = mail.labelCounts.get(label.id)}
            <button
              type="button"
              onclick={() => show(view)}
              aria-current={active ? "page" : undefined}
              title={label.name}
              class={[
                itemHeight,
                "w-full flex items-center gap-3 px-3 group-data-[collapsed=true]/sidebar:px-[calc((2.5rem-18px)/2)] group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-3 rounded-full text-left transition-[background-color,box-shadow,color] duration-150",
                active
                  ? "bg-surface text-text font-semibold shadow-pill"
                  : "text-text-secondary hover:bg-sidebar-hover hover:text-text",
              ]}
            >
              <LabelMark {label} size={18} plain />
              <span class={["flex-1 truncate", expandedOnly]}
                >{label.name}</span
              >
              {#if count}
                <span
                  class={[
                    "text-[13px] tabular-nums",
                    active ? "" : "text-text-secondary",
                    expandedOnly,
                  ]}
                >
                  {count}
                </span>
              {/if}
            </button>
          {/each}
        </div>
      {/if}
    {/if}
  </nav>

  <SidebarAccount {onSignOut} {onSettings} />
</aside>
