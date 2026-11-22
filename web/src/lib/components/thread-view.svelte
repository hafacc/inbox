<!-- @component An open thread, in place of its row: subject, actions, its email's todo (a new, empty one from Add todo), and its messages, opened at the first unread one with every unread one and the last expanded; a quick reply at the bottom. At phone width the top bar holds Back, the subject and the importance marker, every other action the thread offers (`threadActions`, placed by `threadActionPlaces`) is a wedge of the round corner button or on the line above the messages, and a sideways swipe slides in the thread before or after it in the list, drawn beside it all along. -->
<script lang="ts" module>
  // How many phone threads hold the page behind them still.
  let pageLocks = 0;
</script>

<script lang="ts">
  import { isImportant } from "@inbox/shared/focus";
  import { openNeighbors, rowOf } from "@inbox/shared/list-state";
  import { threadOpenState } from "@inbox/shared/thread-open";
  import {
    type PageStep,
    pageOffset,
    pageStarts,
    pageStep,
  } from "@inbox/shared/thread-pager";
  import {
    type ThreadAction,
    threadActionPlaces,
    threadActions,
  } from "@inbox/shared/view-filter";
  import ArrowDown from "@lucide/svelte/icons/arrow-down";
  import Check from "@lucide/svelte/icons/check";
  import EllipsisVertical from "@lucide/svelte/icons/ellipsis-vertical";
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import { onDestroy, tick, untrack } from "svelte";
  import { prefersReducedMotion } from "svelte/motion";
  import { innerWidth } from "svelte/reactivity/window";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import type { GmailLabel } from "../gmail/types";
  import { CHIP_MIN, HEADER_GAP, SUBJECT_MIN } from "../header-fit";
  import { isEditableTarget } from "../keyboard";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { swipeReveals } from "../neighbor-preview";
  import { openMark } from "../open-timing";
  import { rowText } from "../pgp/message-crypto";
  import {
    ACTION_ICONS,
    type HoldMenuItemLook,
    threadActionTone,
    threadActionWords,
  } from "../thread-action-look";
  import { type ThreadDetail, threadItems } from "../thread-detail";
  import {
    mayPull as canPull,
    closesByPull,
    tick as hapticTick,
    pullProgress,
    pullStretch,
    pullTravel,
    setThreadTouches,
  } from "../thread-pull";
  import CornerButton from "./corner-button.svelte";
  import DraftItem from "./draft-item.svelte";
  import EmailTodoLine from "./email-todo-line.svelte";
  import LabelChip from "./label-chip.svelte";
  import LabelMenu from "./label-menu.svelte";
  import MessageItem from "./message-item.svelte";
  import { openDuration, openScrolled, resizeFrom } from "./open-motion.svelte";
  import { phoneWidth, portal } from "./phone";
  import QuickReply from "./quick-reply.svelte";
  import SnoozePopover from "./snooze-popover.svelte";
  import { stuck } from "./sticky";
  import { lockDirection } from "./swipe";
  import { textCut } from "./text-cut";
  import { runThreadAction } from "./thread-action-run";
  import ThreadHeaderActions from "./thread-header-actions.svelte";
  import ThreadNeighbor from "./thread-neighbor.svelte";
  import ThreadSubjectBar from "./thread-subject-bar.svelte";

  let {
    row,
    closedDetail = null,
  }: {
    /** the open thread's row */
    row: Row;
    /** the messages it showed when it closed, while it shrinks away */
    closedDetail?: ThreadDetail | null;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const thread = $derived(row.thread);
  // At phone width the reply is the full-screen composer instead (mail-app).
  const replying = $derived(
    hub.inline !== null &&
      !phoneWidth.current &&
      mail.openId === thread.id &&
      mail.inOpenThread(hub.inline.threadId)
      ? hub.inline
      : null,
  );
  // Closed, it shrinks away showing its messages rather than a placeholder.
  const detail = $derived(
    mail.detail?.id === thread.id ? mail.detail : closedDetail,
  );
  // The reply open in the thread shows as its composer, not as a draft line.
  const items = $derived(
    detail === null
      ? []
      : threadItems(
          detail,
          hub.localDrafts.values(),
          mail.outboxDrafts.messageIds,
        ).filter((item) =>
          item.kind === "local"
            ? item.draft.localId !== replying?.localId
            : item.kind !== "draft" ||
              replying === null ||
              item.message.id !== replying.messageId,
        ),
  );
  const subject = $derived(
    rowText(thread.subject, thread.snippet, mail.decryptedSubjectOf(thread.id))
      .subject || "(no subject)",
  );
  // The wide header's subject shown whole, until the subject changes.
  let subjectWholeFor = $state<string | null>(null);
  const subjectWhole = $derived(subjectWholeFor === subject);
  // Whether the subject is cut to one line; only then does a click show it whole.
  let subjectCut = $state(false);
  const subjectToggles = $derived(subjectWhole || subjectCut);
  const labels = $derived(mail.labelsOf(thread));
  const snoozeOpen = $derived(mail.snoozeMenuFor === thread.id);
  const labelMenuOpen = $derived(mail.labelMenuFor === thread.id);
  // Whether the wide header's More menu is open.
  let moreOpen = $state(false);
  const menuOpen = $derived(snoozeOpen || labelMenuOpen || moreOpen);
  const important = $derived(isImportant(thread.labelIds));
  const emailTodo = $derived(mail.todoOfRow(row));
  // An email has one todo: the action adds it, or opens it to edit once there is one.
  const todoAction = $derived(emailTodo === null ? "Add todo" : "Edit todo");
  // The sender of its latest message, to filter mail like it by.
  const sender = $derived(detail?.messages.at(-1)?.from.email || null);
  // Every action the thread takes anywhere else, the bar's too, so the menu alone always has it.
  const menuActions = $derived(
    threadActions({
      labelIds: thread.labelIds,
      snoozed: row.status === "snoozed",
      unread: thread.unread,
      todo: mail.todos.enabled
        ? todoAction === "Add todo"
          ? "add"
          : "edit"
        : null,
      hasSender: sender !== null,
    }),
  );
  // Where a phone shows each action: the bottom toolbar, the subject's marker, the line above the messages.
  const places = $derived(threadActionPlaces(menuActions));
  // The phone's toolbar, swipes and neighbors belong to the thread while it is open, not while it shrinks closed.
  const phoneOpen = $derived(phoneWidth.current && mail.openId === thread.id);
  const neighbors = $derived(
    phoneOpen ? openNeighbors(mail.list) : { previous: null, next: null },
  );
  const previousRow = $derived(neighborRow(neighbors.previous));
  const nextRow = $derived(neighborRow(neighbors.next));
  let expanded = $state<Set<string>>(new Set());
  let seenDetail: string | null = null;
  let article: HTMLElement;
  let heading = $state<HTMLHeadingElement>();
  // On a phone: the layer covering the screen, the box its messages scroll in, and whether they
  // have scrolled under the top bar, which then casts its shadow on them.
  let layer: HTMLDivElement;
  let scroller: HTMLDivElement;
  let scrolledUnder = $state(false);
  // The layer shrinking back into its row, held there until the thread is gone.
  let layerShrink: Animation | null = null;

  // Where the thread's row sits on the screen, as an inset of the layer, or null when it is off screen.
  function rowInset(): string | null {
    const row = layer.parentElement?.getBoundingClientRect();
    if (!row || row.bottom <= 0 || row.top >= window.innerHeight) return null;
    const right = window.innerWidth - row.right;
    const bottom = window.innerHeight - row.bottom;
    return `inset(${row.top}px ${right}px ${bottom}px ${row.left}px round 18px)`;
  }

  // On a phone the thread grows out of its row to fill the screen (or fades in when the row is
  // off screen), and shrinks back into it (or fades out) as it closes.
  function layerMotion(opening: boolean): void {
    const duration = openDuration();
    if (duration === 0) {
      if (opening) openMark("open motion done");
      return;
    }
    const row = rowInset();
    const full = "inset(0px 0px 0px 0px round 0px)";
    const frames =
      row === null
        ? [{ opacity: 0 }, { opacity: 1 }]
        : [{ clipPath: row }, { clipPath: full }];
    layerShrink?.cancel();
    const motion = layer.animate(opening ? frames : frames.reverse(), {
      duration,
      easing: "cubic-bezier(0.33, 1, 0.68, 1)",
      fill: opening ? "none" : "forwards",
    });
    layerShrink = opening ? null : motion;
    if (opening) {
      motion.finished.then(
        () => openMark("open motion done"),
        () => {},
      );
    }
  }

  // A thread swapped in by a swipe takes the place of the one before it, nothing growing.
  const swappedIn = untrack(
    () => mail.openedInPlace && mail.openId === thread.id,
  );

  $effect(() => {
    untrack(() => {
      if (phoneWidth.current && !swappedIn) layerMotion(true);
    });
  });

  /**
   * Make the app behind the phone's covering thread unreachable (to Tab and screen readers, as
   * Android hides it): the app header and everything in the list beside the thread's own place,
   * including rows drawn while it is open. Returns the undo, which hands focus back to the row
   * the list has focused once it shows.
   */
  function coverApp(): () => void {
    const header = document.querySelector("[data-app-header]");
    const covered: Element[] = [];
    const cover = (element: Element) => {
      if (element.hasAttribute("inert")) return;
      element.setAttribute("inert", "");
      covered.push(element);
    };
    const coverAll = () => {
      let node = layer.parentElement;
      while (node?.parentElement) {
        const parent: HTMLElement = node.parentElement;
        if (header !== null && parent === header.parentElement) {
          cover(header);
          break;
        }
        for (const sibling of parent.children) {
          if (sibling !== node) cover(sibling);
        }
        node = parent;
      }
    };
    coverAll();
    const list = header?.parentElement;
    const changes = new MutationObserver(coverAll);
    if (list) changes.observe(list, { childList: true, subtree: true });
    // A drag on what is fixed over the thread (its toolbar, the status bar's strip) would scroll the
    // page behind, as fixed boxes pass their scroll to it.
    // Counted, as a thread swapped in by a swipe covers before the one it replaces uncovers.
    pageLocks += 1;
    document.documentElement.style.overflow = "hidden";
    return () => {
      pageLocks -= 1;
      if (pageLocks === 0) document.documentElement.style.overflow = "";
      changes.disconnect();
      for (const element of covered) element.removeAttribute("inert");
      requestAnimationFrame(() => {
        const key = mail.focusKey;
        if (key === null || document.activeElement !== document.body) return;
        document
          .querySelector<HTMLElement>(
            `[data-row-key="${CSS.escape(key)}"] [data-shortcuts]`,
          )
          ?.focus({ preventScroll: true });
      });
    };
  }

  $effect(() => {
    if (phoneOpen) return untrack(coverApp);
  });

  // Opened again while it shrinks, it is shown whole at once.
  $effect(() => {
    const closed = mail.openId !== thread.id;
    if (!closed) {
      layerShrink?.cancel();
      layerShrink = null;
    } else if (!untrack(() => mail.openedInPlace)) {
      // After the list has brought the row back into view.
      void tick().then(() => {
        if (phoneWidth.current && mail.openId !== thread.id) {
          layerMotion(false);
        }
      });
    }
  });
  // The wide header's height, which messages scroll clear of.
  let headerHeight = $state(72);
  // The wide header's width inside its padding.
  let headerWidth = $state(Number.POSITIVE_INFINITY);
  // The subject keeps 160 px and each label chip its shortest, 40 px and a gap; the buttons get the rest.
  const actionRoom = $derived(
    headerWidth -
      SUBJECT_MIN -
      labels.length * (CHIP_MIN + HEADER_GAP) -
      HEADER_GAP,
  );

  function measureHeader(header: HTMLElement) {
    const observer = new ResizeObserver(([entry]) => {
      if (entry) headerWidth = entry.contentRect.width;
    });
    observer.observe(header);
    return () => observer.disconnect();
  }

  // Whether the item at `index` starts a phone's card of messages: no message, nor the todo joined
  // to them, sits above it.
  function startsCard(index: number): boolean {
    const above = items[index - 1];
    return above === undefined ? emailTodo === null : above.kind !== "message";
  }

  function neighborRow(id: string | null): Row | null {
    const row = id === null ? null : rowOf(mail.list, id);
    return row?.kind === "thread" ? row.data : null;
  }

  // Where a touch began on the screen, whether the thread was at its top or end then, and whether
  // it is still a possible pull or swipe to a neighbor.
  let pullStart: { x: number; y: number } | null = null;
  let startedAtTop = false;
  let startedAtEnd = false;
  let mayPull = false;
  let mayPage = false;
  // How far a sideways swipe has moved the thread (rightward positive), whether a finger is on
  // it, and its recent places, for the speed it is let go at.
  let page = $state(0);
  let paging = $state(false);
  let samples: { x: number; time: number }[] = [];
  // While the thread slides out for its neighbor, which opens once it has.
  let settling = false;
  // The gap between the thread and a neighbor as one slides in.
  const PAGE_GAP = 12;
  // How far the finger has pulled the thread past its top (positive) or end (negative), and how
  // far the thread moved; with reduced motion it stays in place and the marker alone shows it.
  let pull = $state(0);
  let shift = $state(0);
  let dragging = $state(false);
  const armed = $derived(closesByPull(pull));
  // The arrow points the way of the pull and turns over once letting go closes the thread.
  const pullTurn = $derived(pull > 0 === armed ? 180 : 0);

  function selecting(event: TouchEvent): boolean {
    const own = event.view?.getSelection?.();
    return (
      own?.isCollapsed === false || window.getSelection()?.isCollapsed === false
    );
  }

  function resetPull() {
    pullStart = null;
    dragging = false;
    pull = 0;
    shift = 0;
    paging = false;
    page = 0;
  }

  // A touch's place on the screen; one in a message's frame is moved by where the frame is now,
  // which the drag itself moves.
  function screenPoint(
    event: TouchEvent,
    touch: Touch,
  ): { x: number; y: number } {
    const frame = event.view !== window ? event.view?.frameElement : null;
    const box = frame?.getBoundingClientRect();
    return {
      x: touch.clientX + (box?.left ?? 0),
      y: touch.clientY + (box?.top ?? 0),
    };
  }

  // Whether `target`, or what holds it up to the thread (out of a message's frame too), scrolls
  // sideways and has room to: a sideways drag there scrolls it rather than changing thread.
  function scrollsSideways(target: EventTarget | null): boolean {
    let node = target as Node | null;
    while (node !== null && node !== article) {
      if (node.nodeType === Node.ELEMENT_NODE) {
        const element = node as Element;
        const own = element.ownerDocument;
        const scroller =
          own !== document && element === own.scrollingElement
            ? true
            : /auto|scroll/.test(
                own.defaultView?.getComputedStyle(element).overflowX ?? "",
              );
        if (scroller && element.scrollWidth > element.clientWidth + 1) {
          return true;
        }
      }
      const parent: Node | null = node.parentNode;
      node =
        parent?.nodeType === Node.DOCUMENT_NODE
          ? ((parent as Document).defaultView?.frameElement ?? null)
          : parent;
    }
    return false;
  }

  /**
   * On a phone, a single touch that begins with the thread's top or end in view may pull it, and
   * one that begins away from the screen's edges and off content that scrolls sideways may swipe
   * to a neighbor.
   */
  function onPullStart(event: TouchEvent) {
    const touch = event.touches[0];
    if (settling) return;
    resetPull();
    if (
      !touch ||
      event.touches.length !== 1 ||
      !phoneOpen ||
      menuOpen ||
      selecting(event)
    )
      return;
    const point = screenPoint(event, touch);
    startedAtTop = scroller.scrollTop <= 1;
    startedAtEnd =
      scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 1;
    mayPull = canPull(event.target) && (startedAtTop || startedAtEnd);
    mayPage =
      (neighbors.previous !== null || neighbors.next !== null) &&
      // Pinch-zoomed, a sideways drag pans the page.
      (window.visualViewport?.scale ?? 1) <= 1.01 &&
      pageStarts(point.x, window.innerWidth) &&
      !isEditableTarget(event.target) &&
      !scrollsSideways(event.target);
    if (mayPull || mayPage) pullStart = point;
  }

  /**
   * The thread follows a pull past its edge, less and less the further it goes, crossing the mark
   * ticking; or a sideways swipe, its neighbor sliding in beside it.
   */
  function onPullMove(event: TouchEvent) {
    const touch = event.touches[0];
    if (!pullStart || !touch) return;
    if (event.touches.length > 1 || selecting(event)) {
      resetPull();
      return;
    }
    const point = screenPoint(event, touch);
    const across = point.x - pullStart.x;
    const down = point.y - pullStart.y;
    if (!dragging && !paging) {
      const lock = lockDirection(across, down);
      if (lock === "pending") return;
      if (lock === "horizontal" && mayPage) {
        paging = true;
        samples = [];
      } else if (
        lock === "horizontal" ||
        !mayPull ||
        pullTravel(down, startedAtTop, startedAtEnd) === 0
      ) {
        pullStart = null;
        return;
      } else dragging = true;
    }
    if (paging) {
      page = pageOffset(
        across,
        neighbors.previous !== null,
        neighbors.next !== null,
      );
      const now = performance.now();
      samples = [
        ...samples.filter((sample) => now - sample.time < 100),
        { x: point.x, time: now },
      ];
      if (event.cancelable) event.preventDefault();
      return;
    }
    const wasArmed = armed;
    pull = pullTravel(down, startedAtTop, startedAtEnd);
    shift = prefersReducedMotion.current ? 0 : pullStretch(pull);
    // The thread moves instead of the page scrolling.
    if (event.cancelable) event.preventDefault();
    if (closesByPull(pull) !== wasArmed) hapticTick();
  }

  /**
   * Letting go past the mark closes the thread, as Back does, and a swipe far or fast enough
   * opens the neighbor it slid in; short of either the thread springs back.
   */
  function onPullEnd(event: TouchEvent) {
    if (paging) {
      const first = samples[0];
      const last = samples.at(-1);
      const velocity =
        first !== undefined && last !== undefined && last.time > first.time
          ? ((last.x - first.x) / (last.time - first.time)) * 1000
          : 0;
      const step =
        event.type === "touchend"
          ? pageStep(
              page,
              window.innerWidth,
              velocity,
              neighbors.previous !== null,
              neighbors.next !== null,
            )
          : null;
      paging = false;
      pullStart = null;
      if (step === null) page = 0;
      else turnPage(step);
      return;
    }
    const closes = dragging && event.type === "touchend" && armed;
    resetPull();
    if (closes) {
      hapticTick();
      mail.close();
    }
  }

  // Slide the neighbor the swipe went toward in, then open it as a tap on its row would, in this
  // thread's place on the screen with nothing growing or shrinking.
  function turnPage(step: PageStep) {
    const id = step === "next" ? neighbors.next : neighbors.previous;
    if (id === null) {
      page = 0;
      return;
    }
    const swap = () => {
      pageTimer = null;
      // Closed or left while it slid out: nothing opens, and a reopened thread starts in place.
      if (mail.openId !== thread.id) {
        settling = false;
        page = 0;
        return;
      }
      mail.open(id, true);
      // Only seen when the neighbor didn't open after all and this thread stays.
      settling = false;
      page = 0;
    };
    if (prefersReducedMotion.current) swap();
    else {
      settling = true;
      page = (step === "next" ? -1 : 1) * (window.innerWidth + PAGE_GAP);
      pageTimer = setTimeout(swap, 200);
    }
  }

  let pageTimer: ReturnType<typeof setTimeout> | null = null;
  onDestroy(() => {
    if (pageTimer !== null) clearTimeout(pageTimer);
  });

  setThreadTouches({ start: onPullStart, move: onPullMove, end: onPullEnd });

  // Svelte's touchmove handlers are passive, so they could not stop the page's scroll.
  function listenPull(node: HTMLElement) {
    node.addEventListener("touchstart", onPullStart, { passive: true });
    node.addEventListener("touchmove", onPullMove, { passive: false });
    node.addEventListener("touchend", onPullEnd);
    node.addEventListener("touchcancel", onPullEnd);
    return () => {
      node.removeEventListener("touchstart", onPullStart);
      node.removeEventListener("touchmove", onPullMove);
      node.removeEventListener("touchend", onPullEnd);
      node.removeEventListener("touchcancel", onPullEnd);
    };
  }

  // Before the messages draw, so a thread shown at once opens expanded where it should.
  $effect.pre(() => {
    const current = detail;
    // Its messages, not its history id: the saved copy adds mail without reading the thread again.
    const seen =
      current === null
        ? null
        : current.messages.map((message) => message.id).join(",");
    if (current === null || seenDetail === seen) return;
    const firstLoad = seenDetail === null;
    seenDetail = seen;
    untrack(() => {
      const opening = threadOpenState(current.messages);
      const next = new Set(opening.expanded);
      for (const id of expanded) next.add(id);
      expanded = next;
      if (
        firstLoad &&
        opening.scrollTo !== null &&
        opening.scrollTo !== current.messages[0]?.id
      ) {
        void scrollToMessage(opening.scrollTo);
      }
    });
  });

  // Once, as it opens: a later change of layout (crossing the phone width) neither scrolls nor
  // moves focus.
  $effect(() => {
    untrack(() => {
      // On a phone the thread covers the screen in place, so the list behind stays where it was;
      // a thread swapped in by a swipe is placed by the swipe.
      if (!phoneWidth.current && !swappedIn) {
        article.scrollIntoView({
          block: "nearest",
          behavior: prefersReducedMotion.current ? "auto" : "smooth",
        });
      }
      if (swappedIn || !isEditableTarget(document.activeElement)) {
        heading?.focus({ preventScroll: true });
      }
    });
  });

  function messageNode(id: string): HTMLElement | null {
    return article.querySelector<HTMLElement>(
      `[data-message="${CSS.escape(id)}"]`,
    );
  }

  // After the messages draw, so the place it scrolls to is laid out; a thread still growing out of its
  // row is shown already there instead of scrolling once it has grown.
  async function scrollToMessage(id: string) {
    await tick();
    openScrolled(article, () => {
      openMark("scrolled to the first unread or last message");
      const node = messageNode(id);
      if (node === null) return;
      if (untrack(() => phoneWidth.current)) {
        // Its own box only: scrollIntoView can also scroll the list behind the covering thread.
        const margin = Number.parseFloat(
          getComputedStyle(node).scrollMarginTop,
        );
        scroller.scrollTop +=
          node.getBoundingClientRect().top -
          scroller.getBoundingClientRect().top -
          (margin || 0);
      } else node.scrollIntoView({ block: "start", behavior: "auto" });
    });
  }

  async function toggle(id: string) {
    const node = messageNode(id);
    const from = node?.getBoundingClientRect().height ?? 0;
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    expanded = next;
    if (node === null) return;
    await tick();
    resizeFrom(node, from);
  }

  function openLabel(label: GmailLabel) {
    mail.setView({ kind: "label", labelId: label.id });
  }

  // Each menu action's name.
  const actionWords = (action: ThreadAction) =>
    threadActionWords(action, row.status === "snoozed");

  function runAction(action: ThreadAction): void {
    runThreadAction(mail, thread.id, action, sender);
  }

  // An action as a wedge of the phone's corner button.
  function discItem(action: ThreadAction): HoldMenuItemLook {
    return {
      id: action,
      label: actionWords(action),
      icon: ACTION_ICONS[action] ?? Check,
      tone: threadActionTone(action),
    };
  }

  const linePill =
    "h-8 flex items-center gap-1.5 px-3 rounded-full border border-border text-sm font-medium hover:bg-surface-hover transition-colors";
</script>

{#snippet labelChips()}
  {#each labels as label (label.id)}
    <button
      type="button"
      onclick={() => openLabel(label)}
      title={`Show ${label.name}`}
      class="relative min-w-10 pointer-events-auto rounded-full hover:brightness-95 after:absolute after:-inset-y-[13px] after:inset-x-0"
    >
      <LabelChip {label} />
    </button>
  {/each}
{/snippet}

<!-- On a phone the thread covers the screen, as Android's: its own top bar over the page color,
     its messages scrolling under the bar on a card set in from the edges. Elsewhere these two
     boxes don't draw. -->
<div
  bind:this={layer}
  data-thread-view
  class="contents max-sm:fixed max-sm:inset-0 max-sm:z-[15] max-sm:flex max-sm:flex-col max-sm:pt-safe max-sm:pl-safe max-sm:pr-safe max-sm:bg-bg"
>
  <div
    bind:this={scroller}
    onscroll={() => (scrolledUnder = scroller.scrollTop > 0)}
    class="contents max-sm:block max-sm:flex-1 max-sm:min-h-0 max-sm:overflow-y-auto max-sm:overflow-x-hidden max-sm:overscroll-y-contain"
  >
    <article
      bind:this={article}
      aria-label={subject}
      {@attach listenPull}
      style:--thread-header={`${headerHeight}px`}
      style:transform={shift === 0 && page === 0
        ? undefined
        : `translate(${page}px, ${shift}px)`}
      class={[
        "relative bg-surface scroll-mt-20 rounded-[20px] shadow-raised max-sm:min-h-full max-sm:bg-transparent max-sm:rounded-none max-sm:shadow-none max-sm:pb-[calc(var(--thread-corner)+56px)] max-sm:[--thread-corner:calc(5.5rem+var(--safe-bottom))]",
        menuOpen && "z-20",
        !dragging && !paging && "transition-transform duration-200 ease-out",
      ]}
    >
      {#if pull !== 0}
        <!-- Centered in the gap the pull opens; with reduced motion, just inside the thread's edge. -->
        <span
          aria-hidden="true"
          style:top={pull > 0
            ? `${shift === 0 ? 28 : -shift / 2}px`
            : undefined}
          style:bottom={pull < 0
            ? phoneWidth.current
              ? `calc(var(--thread-corner) + 40px + ${shift / 2}px)`
              : `${shift === 0 ? 28 : shift / 2}px`
            : undefined}
          style:opacity={pullProgress(pull)}
          style:transform={`translate(-50%, ${pull > 0 ? -50 : 50}%) scale(${0.6 + 0.4 * pullProgress(pull)})`}
          class={[
            "pointer-events-none absolute left-1/2 z-[7] w-9 h-9 flex items-center justify-center rounded-full bg-surface-raised shadow-pop",
            armed ? "text-accent" : "text-text-secondary",
          ]}
        >
          <ArrowDown
            class="w-5 h-5 transition-transform duration-150 motion-reduce:transition-none"
            style={`transform: rotate(${pullTurn}deg)`}
          />
        </span>
      {/if}
      <p class="sr-only" aria-live="polite">
        {armed ? "Release to close" : ""}
      </p>
      {#if phoneWidth.current}
        <ThreadSubjectBar
          bind:heading
          {subject}
          {important}
          marked={places.marker !== null}
          raised={scrolledUnder && detail !== null}
          onBack={mail.close}
          onImportant={() => mail.setImportant(thread.id, !important)}
        />
        {#if labels.length > 0 || places.line.length > 0 || detail !== null}
          <!-- Each chip and pill sits in a 48 px tall tap area, so the rows of a wrapped line touch. -->
          <div class="px-4 py-1 flex flex-wrap items-center gap-x-1.5">
            {#each labels as label (label.id)}
              <button
                type="button"
                onclick={() => openLabel(label)}
                title={`Show ${label.name}`}
                class="h-12 shrink-0 flex items-center rounded-full hover:brightness-95"
              >
                <LabelChip {label} />
              </button>
            {/each}
            {#if detail !== null}
              <span class="text-[13px] text-text-secondary"
                >{detail.messages.length === 1
                  ? "1 message"
                  : `${detail.messages.length} messages`}</span
              >
            {/if}
            {#each places.line as action (action)}
              {@const Icon = ACTION_ICONS[action]}
              <button
                type="button"
                onclick={() => runAction(action)}
                class="h-12 shrink-0 flex items-center rounded-full"
              >
                <span class={linePill}>
                  {#if Icon !== null}
                    <Icon
                      class="w-4 h-4 text-text-secondary"
                      aria-hidden="true"
                    />
                  {/if}
                  {actionWords(action)}
                </span>
              </button>
            {/each}
          </div>
        {/if}
        {#if phoneOpen}
          <CornerButton
            label="Thread actions"
            icon={EllipsisVertical}
            bottom="calc(1rem + var(--safe-bottom))"
            menu={() => ({
              inner: places.inner.map(discItem),
              outer: places.outer.map(discItem),
            })}
            onPick={(id) => runAction(id as ThreadAction)}
          />
          {#if snoozeOpen}
            <SnoozePopover
              onPick={(epoch, source, key) =>
                mail.snooze(thread.id, epoch, source, key)}
              onClose={mail.closeSnoozeMenu}
            />
          {/if}
          {#if labelMenuOpen}
            <LabelMenu threadIds={[thread.id]} />
          {/if}
          {#each [
            { row: previousRow, side: -1 as const },
            { row: nextRow, side: 1 as const },
          ] as neighbor (neighbor.side)}
            {#if neighbor.row !== null}
              <div class="contents">
                <div
                  {@attach portal}
                  aria-hidden="true"
                  inert
                  style:transform={`translateX(${neighbor.side * ((innerWidth.current ?? 0) + PAGE_GAP) + page}px)`}
                  class={[
                    "fixed z-[16] left-0 w-screen top-0 bottom-0 pt-safe pl-safe pr-safe overflow-hidden pointer-events-none bg-bg",
                    !paging && "transition-transform duration-200 ease-out",
                  ]}
                >
                  {#key neighbor.row.thread.id}
                    <ThreadNeighbor
                      row={neighbor.row}
                      full={swipeReveals(page, neighbor.side)}
                    />
                  {/key}
                </div>
              </div>
            {/if}
          {/each}
        {/if}
      {:else}
        <div
          {@attach stuck}
          bind:clientHeight={headerHeight}
          {@attach measureHeader}
          class="sticky top-[calc(4rem+env(safe-area-inset-top))] z-[6] min-h-[72px] py-[18px] pl-7 pr-4 flex items-center gap-3 rounded-t-[20px] bg-surface/80 backdrop-blur-xl backdrop-saturate-150 transition-shadow duration-200 data-[stuck]:rounded-none data-[stuck]:shadow-[0_6px_18px_-12px_rgb(0_0_0/0.35)] [&>*:not(.close-area)]:relative"
        >
          <button
            type="button"
            tabindex="-1"
            aria-label="Close thread (Esc)"
            onclick={mail.close}
            class="close-area absolute inset-0 w-full rounded-t-[20px] cursor-default outline-none"
          ></button>
          <div
            class="grow basis-40 min-w-0 flex items-center gap-3 pointer-events-none"
          >
            <h1
              bind:this={heading}
              tabindex="-1"
              class="min-w-0 text-[21px] leading-7 font-semibold tracking-[-0.015em] outline-none"
            >
              <!-- A click shows a long subject whole (another cuts it again), and doesn't close the thread. -->
              <button
                type="button"
                {@attach textCut(subject, (value) => (subjectCut = value))}
                aria-expanded={subjectToggles ? subjectWhole : undefined}
                title={subjectCut ? subject : undefined}
                onclick={() => {
                  if (subjectToggles)
                    subjectWholeFor = subjectWhole ? null : subject;
                }}
                class={[
                  "pointer-events-auto max-w-full text-left cursor-default outline-none",
                  subjectWhole ? "break-words" : "block truncate",
                ]}
              >
                {subject}
              </button>
            </h1>
            {@render labelChips()}
          </div>
          <ThreadHeaderActions
            {row}
            actions={menuActions}
            room={actionRoom}
            words={actionWords}
            icons={ACTION_ICONS}
            onRun={runAction}
            bind:menuOpen={moreOpen}
          />
        </div>
      {/if}

      {#if emailTodo !== null}
        <div
          class={[
            "border-t border-separator px-3 sm:px-6 py-3 max-sm:mx-1 max-sm:px-4 max-sm:border-t-0 max-sm:bg-surface max-sm:rounded-t-xl",
            items[0]?.kind !== "message" && "max-sm:rounded-b-xl",
          ]}
        >
          {#key emailTodo.id}
            <EmailTodoLine todo={emailTodo} editable />
          {/key}
        </div>
      {/if}

      {#if detail}
        {#each items as item, index (item.kind === "local"
          ? `local:${item.draft.localId}`
          : item.message.id)}
          {#if item.kind === "message"}
            <!-- On a phone each run of messages is one card, joined to the todo above it. -->
            <div
              data-message={item.message.id}
              class={[
                "sm:scroll-mt-[calc(4rem+var(--thread-header)+env(safe-area-inset-top))] max-sm:scroll-mt-14 max-sm:mx-1 max-sm:bg-surface max-sm:overflow-hidden",
                startsCard(index) &&
                  "max-sm:rounded-t-xl max-sm:[&>*]:border-t-0",
                items[index + 1]?.kind !== "message" && "max-sm:rounded-b-xl",
              ]}
            >
              <MessageItem
                message={item.message}
                expanded={expanded.has(item.message.id)}
                last={item.message.id === detail.messages.at(-1)?.id}
                onToggle={() => void toggle(item.message.id)}
              />
            </div>
          {:else if item.kind === "draft"}
            <DraftItem
              snippet={item.message.snippet}
              sending={false}
              onOpen={() => void hub.openDraftMessage(item.message.id)}
            />
          {:else}
            <DraftItem
              snippet={item.draft.snippet}
              sending={item.sending}
              onOpen={() => void hub.openLocalDraft(item.draft.localId)}
            />
          {/if}
        {/each}
        {#if replying}
          {#key replying.localId}
            {#await import("./composer.svelte") then { default: Composer }}
              <Composer composer={replying} variant="inline" />
            {/await}
          {/key}
        {:else if detail.messages.length > 0}
          <QuickReply {detail} />
        {/if}
      {:else if mail.detailError && phoneWidth.current}
        <div
          role="alert"
          class="pt-16 px-6 flex flex-col items-center gap-3 text-center text-text-secondary"
        >
          {mail.detailError}
          <button
            type="button"
            onclick={mail.retryDetail}
            class="h-12 flex items-center rounded-full"
          >
            <span
              class="px-5 py-2 rounded-full bg-accent-soft text-accent-strong font-semibold"
              >Retry</span
            >
          </button>
        </div>
      {:else if mail.detailError}
        <div
          role="alert"
          class="border-t border-separator px-3 sm:px-6 py-5 flex flex-col items-start gap-3 text-text-secondary"
        >
          {mail.detailError}
          <button
            type="button"
            onclick={mail.retryDetail}
            class="hit-target h-9 flex items-center px-3.5 rounded-full border border-border bg-surface text-[13px] font-medium text-text hover:bg-surface-hover transition-colors"
          >
            Retry
          </button>
        </div>
      {:else if phoneWidth.current}
        <div class="pt-16 flex justify-center" aria-busy="true">
          <LoaderCircle
            class="w-10 h-10 text-accent animate-spin motion-reduce:animate-none"
            aria-label="Loading"
          />
        </div>
      {:else}
        <div
          class="border-t border-separator px-3 sm:px-6 py-5 flex flex-col gap-2.5"
          aria-busy="true"
        >
          <div
            class="h-3.5 w-2/5 rounded bg-surface-muted animate-shimmer"
          ></div>
          <div
            class="h-3.5 w-4/5 rounded bg-surface-muted animate-shimmer"
          ></div>
          <div
            class="h-3.5 w-3/5 rounded bg-surface-muted animate-shimmer"
          ></div>
        </div>
      {/if}
    </article>
  </div>
</div>
