<!-- @component One thread in a list: sender, subject and snippet, its email's todo as a line under them, its date or snooze, and actions on hover or focus. Its avatar selects it (flipping to a check); a long press on touch opens its round menu where the finger is, with Pin and Delete as a todo's (`rowHoldActions`); while anything is selected, a click anywhere on the row selects or unselects it, and shift-click selects a range. On touch, a swipe right snoozes (or wakes a snoozed thread) and left marks it done (or moves a thread out of the inbox back to it), as on Android. -->
<script lang="ts" module>
  import { MediaQuery } from "svelte/reactivity";

  // One for every row: each instance would add its own media listener.
  const touch = new MediaQuery("hover: none");
</script>

<script lang="ts">
  import { isImportant } from "@inbox/shared/focus";
  import { rowHoldWedges } from "@inbox/shared/hold-menu";
  import { threadRowKey } from "@inbox/shared/list-state";
  import {
    type ThreadSwipeAction,
    threadSwipeAction,
  } from "@inbox/shared/swipe";
  import {
    formatListDate,
    formatWakeTime,
    lapsedMarker,
  } from "@inbox/shared/time-format";
  import type { EmailTodo } from "@inbox/shared/todos";
  import {
    rowHoldActions,
    type ThreadAction,
    threadActions,
  } from "@inbox/shared/view-filter";
  import Check from "@lucide/svelte/icons/check";
  import Clock from "@lucide/svelte/icons/clock";
  import Lock from "@lucide/svelte/icons/lock";
  import Mail from "@lucide/svelte/icons/mail";
  import Pin from "@lucide/svelte/icons/pin";
  import { untrack } from "svelte";
  import type { ClassValue } from "svelte/elements";
  import { holdMenu } from "../hold-menu.svelte";
  import { inputMode } from "../input-mode.svelte";
  import { isEditableTarget } from "../keyboard";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { rowText } from "../pgp/message-crypto";
  import {
    ACTION_ICONS,
    threadActionTone,
    threadActionWords,
  } from "../thread-action-look";
  import { isInInbox, isPinned, LABEL, sendersLabel } from "../thread-summary";
  import Avatar from "./avatar.svelte";
  import EmailTodoLine from "./email-todo-line.svelte";
  import ImportantMark from "./important-mark.svelte";
  import LabelChip from "./label-chip.svelte";
  import RowActions from "./row-actions.svelte";
  import { RowSwipe } from "./row-swipe.svelte";
  import StyledText from "./styled-text.svelte";
  import SwipeBackdrop, { type SwipeLook } from "./swipe-backdrop.svelte";
  import { runThreadAction } from "./thread-action-run";

  let {
    row,
    compact = false,
    showLabels = false,
    todo = null,
    todoLook = false,
    class: className = "",
  }: {
    /** the thread shown */
    row: Row;
    /** the shorter row of a label group */
    compact?: boolean;
    /** whether its user labels show as chips */
    showLabels?: boolean;
    /** its email's todo, a line under the snippet */
    todo?: EmailTodo | null;
    /** whether it shows only its todo's text, as a todo's row, with a mail icon: the Todos view's row */
    todoLook?: boolean;
    /** classes added to the row */
    class?: ClassValue;
  } = $props();

  const SWIPE_COLORS: Record<
    ThreadSwipeAction,
    Pick<SwipeLook, "soft" | "strong">
  > = {
    snooze: {
      soft: "bg-snooze-soft text-on-snooze-soft",
      strong: "bg-snooze text-on-snooze",
    },
    wake: {
      soft: "bg-accent-soft text-accent-strong",
      strong: "bg-accent text-on-accent",
    },
    done: { soft: "bg-done-soft text-done", strong: "bg-done text-on-done" },
    inbox: {
      soft: "bg-accent-soft text-accent-strong",
      strong: "bg-accent text-on-accent",
    },
  };
  // What a swipe shows under the row: the action's colors, name and icon.
  const swipeLook = (action: ThreadSwipeAction): SwipeLook => ({
    ...SWIPE_COLORS[action],
    label: threadActionWords(action, false),
    icon: ACTION_ICONS[action] ?? Check,
  });
  const mail = getMailState();
  // A todo still being written (blank) shows only at the top of the open email.
  const shownTodo = $derived(
    todo !== null && todo.text.trim() !== "" ? todo : null,
  );
  const thread = $derived(row.thread);
  const asTodo = $derived(todoLook && shownTodo !== null);
  const key = $derived(threadRowKey(thread.id));
  const focused = $derived(mail.focusKey === key);
  // A ring and actions for keyboard focus only; after a click they would just linger.
  const keyboardFocused = $derived(focused && inputMode.keyboard);
  const menuOpen = $derived(
    mail.snoozeMenuFor === thread.id || mail.labelMenuFor === thread.id,
  );
  const selecting = $derived(mail.selected.length > 0);
  const selected = $derived(selecting && mail.selected.includes(key));
  const firstSender = $derived(thread.senders[0] ?? { name: "", email: "" });
  const senders = $derived(sendersLabel(thread.senders, mail.email));
  const labels = $derived(showLabels ? mail.labelsOf(thread) : []);
  const text = $derived(
    rowText(thread.subject, thread.snippet, mail.decryptedSubjectOf(thread.id)),
  );
  const subject = $derived(text.subject || "(no subject)");
  const encrypted = $derived(
    text.encrypted || (mail.encryptedSearch?.matches.has(thread.id) ?? false),
  );
  // A newer message a Gmail filter kept out of the inbox, which doesn't end a snooze.
  const quietNewMail = $derived(
    mail.view.kind === "snoozed" &&
      row.status === "snoozed" &&
      mail.skippedInbox.has(thread.id),
  );
  const snoozed = $derived(row.status === "snoozed");
  const spam = $derived(thread.labelIds.includes(LABEL.spam));
  // What its round menu offers.
  const holdActions = $derived(
    rowHoldActions(
      threadActions({
        labelIds: thread.labelIds,
        snoozed,
        unread: thread.unread,
        todo: null,
        hasSender: false,
      }),
    ),
  );
  const swipeRow = $derived({
    status: row.status,
    inInbox: isInInbox(thread),
    spamOrTrash: spam || thread.labelIds.includes(LABEL.trash),
    inSnoozedView: mail.view.kind === "snoozed",
  });
  const startAction = $derived(threadSwipeAction("start", swipeRow));
  const endAction = $derived(threadSwipeAction("end", swipeRow));
  // Screen readers can't swipe or hold: the row offers its swipes' actions and its round menu's.
  const screenReaderActions = $derived(
    [endAction, startAction, ...holdActions].filter(
      (action): action is ThreadAction => action !== null,
    ),
  );
  let hovered = $state(false);
  let focusWithin = $state(false);
  // The actions are only built for the row they can show on; touch has no hover to show them.
  const actionsShown = $derived(
    !selecting &&
      (menuOpen ||
        (hovered && !touch.current) ||
        (inputMode.keyboard && (focused || focusWithin))),
  );
  let element: HTMLDivElement;
  let button: HTMLButtonElement;
  // Under a slid-off row stays the move it was let go with until it snaps back: the action changes
  // the thread at once (Move to inbox would turn the move under it into Done). A long press opens
  // the round menu around the finger, as a todo's does.
  const swipe = new RowSwipe<ThreadSwipeAction>(
    () => ({ start: startAction, end: endAction }),
    (action) => runThreadAction(mail, thread.id, action),
    (pointerId, press) => openRowMenu(pointerId, press),
  );

  function openRowMenu(pointerId: number, press: { x: number; y: number }) {
    const id = thread.id;
    const snoozedNow = row.status === "snoozed";
    holdMenu.open({
      x: press.x,
      y: press.y,
      items: rowHoldWedges(
        holdActions,
        press.x,
        press.y,
        window.innerWidth,
        window.innerHeight,
      ).map((wedge) => ({
        ...wedge,
        label: threadActionWords(wedge.id, snoozedNow),
        icon: ACTION_ICONS[wedge.id] ?? Check,
        tone: threadActionTone(wedge.id),
      })),
      // The Todos view's row names its todo, as it shows.
      pill:
        asTodo && shownTodo
          ? shownTodo.text.split("\n")[0]?.trim() || "Todo"
          : subject,
      label: "Email actions",
      pointerId,
      press,
      focusFirst: false,
      onPick: (picked) => runThreadAction(mail, id, picked as ThreadAction),
      onClose: () => swipe.endHold(),
    });
  }

  function onPointerDown(event: PointerEvent & { currentTarget: HTMLElement }) {
    // A press is likely an open: its saved body is read meanwhile.
    mail.prefetchDetail(thread.id);
    swipe.down(event);
  }

  $effect(() => {
    const enter = () => {
      hovered = true;
      mail.prefetchDetail(thread.id);
    };
    const leave = () => {
      hovered = false;
    };
    const focusIn = () => {
      focusWithin = true;
    };
    const focusOut = (event: FocusEvent) => {
      const within = element.contains(event.relatedTarget as Node | null);
      // Focus also leaves while the row is torn down, when state can't change yet.
      queueMicrotask(() => {
        focusWithin = within;
      });
    };
    element.addEventListener("mouseenter", enter);
    element.addEventListener("mouseleave", leave);
    element.addEventListener("focusin", focusIn);
    element.addEventListener("focusout", focusOut);
    return () => {
      element.removeEventListener("mouseenter", enter);
      element.removeEventListener("mouseleave", leave);
      element.removeEventListener("focusin", focusIn);
      element.removeEventListener("focusout", focusOut);
    };
  });

  // DOM focus follows the list's, without scrolling: only the keyboard scrolls, by the list.
  $effect(() => {
    if (focused && !isEditableTarget(document.activeElement)) {
      button.focus({ preventScroll: true });
    }
  });

  // The focused row is the one the keyboard opens next.
  $effect(() => {
    if (focused) untrack(() => mail.prefetchDetail(thread.id));
  });
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<!-- biome-ignore lint/a11y/noStaticElementInteractions: touch swipes and long presses only; every action here is also a button -->
<div
  bind:this={element}
  data-thread-id={thread.id}
  data-row-key={key}
  onpointerdown={onPointerDown}
  onpointermove={swipe.move}
  onpointerup={swipe.up}
  onpointercancel={swipe.cancel}
  oncontextmenu={swipe.contextMenu}
  onclickcapture={swipe.clickCapture}
  class={[
    "group/row relative touch-pan-y pointer-coarse:select-none pointer-coarse:[-webkit-touch-callout:none]",
    swipe.dx !== 0 && "overflow-hidden",
    menuOpen && "z-20",
    className,
  ]}
>
  <SwipeBackdrop
    dx={swipe.dx}
    width={swipe.width}
    start={swipe.shown.start && swipeLook(swipe.shown.start)}
    end={swipe.shown.end && swipeLook(swipe.shown.end)}
  />
  <div
    style:transform={swipe.dx !== 0 ? `translateX(${swipe.dx}px)` : null}
    ontransitionend={() => (swipe.settling = false)}
    class={[
      "relative rounded-[inherit]",
      swipe.dx !== 0
        ? "bg-surface-raised shadow-raised"
        : selected
          ? "bg-accent-soft"
          : "hover:bg-surface-hover",
      keyboardFocused && "shadow-[inset_0_0_0_2px_var(--color-focus)]",
      swipe.settling
        ? "transition-transform duration-200 ease-out"
        : "transition-colors duration-150",
    ]}
  >
    <button
      bind:this={button}
      type="button"
      data-shortcuts
      class="absolute inset-0 w-full rounded-[inherit] outline-none"
      aria-label={selecting
        ? `${selected ? "Unselect" : "Select"} ${subject}, from ${senders}`
        : asTodo
          ? `Open the email ${subject}, from ${senders}, for its todo`
          : `Open ${subject}, from ${senders}`}
      aria-pressed={selecting ? selected : undefined}
      onclick={(event) => {
        if (selecting || event.shiftKey) {
          mail.dispatch({ type: "pick", key, range: event.shiftKey });
        } else {
          mail.dispatch({ type: "tap", key });
        }
      }}
    ></button>
    <!-- Clicks fall through to the open button beneath, but for the controls that take them back. -->
    <div
      class={[
        "relative flex items-center gap-3.5 pointer-events-none",
        compact
          ? "h-16 px-4"
          : asTodo
            ? "min-h-[56px] py-1 px-5"
            : shownTodo
              ? "min-h-[68px] py-3 px-5"
              : "h-[68px] px-5",
      ]}
    >
      <button
        type="button"
        tabindex="-1"
        aria-label={`${selected ? "Unselect" : "Select"} ${subject}`}
        aria-pressed={selected}
        onclick={(event) =>
          mail.dispatch({ type: "pick", key, range: event.shiftKey })}
        class="pointer-events-auto relative shrink-0 rounded-full [perspective:480px] outline-none pointer-coarse:after:absolute pointer-coarse:after:-inset-2 pointer-coarse:after:content-['']"
      >
        <span
          class={[
            "relative block transition-transform duration-300 ease-out [transform-style:preserve-3d]",
            selected && "[transform:rotateY(180deg)]",
          ]}
        >
          <span class="block [backface-visibility:hidden]">
            {#if asTodo}
              <span
                class="w-9 h-9 rounded-full bg-accent-soft text-accent-strong flex items-center justify-center"
              >
                <Mail class="w-[18px] h-[18px]" aria-hidden="true" />
              </span>
            {:else}
              <Avatar
                person={firstSender}
                size={compact ? 32 : 36}
                text={compact ? "text-xs" : "text-[13px]"}
              />
            {/if}
          </span>
          <span
            class="absolute inset-0 rounded-full bg-accent text-on-accent flex items-center justify-center [backface-visibility:hidden] [transform:rotateY(180deg)]"
          >
            <Check
              class={compact ? "w-4 h-4" : "w-[18px] h-[18px]"}
              strokeWidth={3}
            />
          </span>
        </span>
      </button>
      {#if asTodo && shownTodo}
        <div
          class="todo-text flex-1 min-w-0 py-[13px] whitespace-pre-wrap break-words text-[14.5px] leading-[22px] text-text pointer-events-none"
        >
          <StyledText text={shownTodo.text} />
        </div>
      {:else}
        <div class="flex-1 min-w-0 flex flex-col gap-[3px] pointer-events-none">
          <div class="flex items-center gap-2 min-w-0">
            <span
              class={[
                "truncate text-[14.5px] text-text",
                thread.unread ? "font-semibold" : "font-medium",
              ]}
            >
              {senders}
            </span>
            {#if thread.messageCount > 1}
              <span class="shrink-0 text-xs text-muted tabular-nums">
                {thread.messageCount}
              </span>
            {/if}
            {#if isImportant(thread.labelIds)}
              <ImportantMark
                class="w-3.5 h-3.5 shrink-0 text-accent"
                label="Important"
              />
            {/if}
            {#if isPinned(thread)}
              <Pin
                class="w-3.5 h-3.5 shrink-0 text-accent"
                aria-label="Pinned"
              />
            {/if}
            <span class="flex-1"></span>
            <div
              class={[
                "flex items-center gap-2 shrink-0 transition-opacity duration-150",
                actionsShown && "opacity-0",
              ]}
            >
              {#each labels as label (label.id)}
                <LabelChip {label} />
              {/each}
              {#if quietNewMail}
                <span class="text-xs text-text-secondary whitespace-nowrap">
                  New message · still snoozed
                </span>
              {/if}
              {#if row.status === "snoozed" && row.snoozeUntil !== null}
                <span
                  class="flex items-center gap-1.5 text-xs font-semibold text-on-snooze-soft tabular-nums"
                >
                  <Clock class="w-3.5 h-3.5" aria-hidden="true" />
                  {formatWakeTime(row.snoozeUntil, mail.now)}
                </span>
              {:else if row.status === "lapsed" && row.snoozeUntil !== null}
                <span
                  class="h-[22px] flex items-center gap-1.5 px-2 rounded-full bg-snooze-soft text-on-snooze-soft text-xs font-medium"
                >
                  <Clock class="w-3.5 h-3.5" aria-hidden="true" />
                  {lapsedMarker(row.snoozeUntil, mail.now)}
                </span>
              {:else}
                <span
                  class={[
                    "text-xs tabular-nums",
                    thread.unread
                      ? "font-semibold text-text"
                      : "text-text-secondary",
                  ]}
                >
                  {formatListDate(thread.lastDate, mail.now)}
                </span>
              {/if}
            </div>
          </div>
          <div class="truncate text-sm">
            {#if encrypted}
              <Lock
                class="inline w-3.5 h-3.5 -mt-0.5 mr-1 text-done"
                role="img"
                aria-label="Encrypted"
              />
            {/if}
            <span
              class={thread.unread
                ? "font-semibold text-text"
                : "text-text-secondary"}
              >{subject}</span
            >
            {#if text.snippet}
              <span class="text-muted">&ensp;{text.snippet}</span>
            {/if}
          </div>
          {#if shownTodo}
            <EmailTodoLine
              todo={shownTodo}
              class="mt-[2px] pt-[5px] border-t border-dashed border-border"
            />
          {/if}
        </div>
      {/if}
      {#if actionsShown}
        <!-- At phone width the open menu is a bottom sheet, moved out of the tray, so the tray would only cover the row. -->
        <div
          class={[
            "pointer-events-auto absolute right-3 top-1/2 -translate-y-1/2 flex items-center rounded-full bg-surface-raised shadow-pop p-0.5 animate-fade-in",
            menuOpen && "max-sm:invisible",
          ]}
        >
          <RowActions {row} />
        </div>
      {:else if !selecting}
        <!-- The row's actions for screen readers, which can't hover or swipe: its swipes and its round menu's. The tray replaces them once it shows. -->
        <div class="sr-only">
          {#each screenReaderActions as action (action)}
            <button
              type="button"
              tabindex="-1"
              onclick={() => runThreadAction(mail, thread.id, action)}
            >
              {threadActionWords(action, snoozed)}
            </button>
          {/each}
        </div>
      {/if}
    </div>
  </div>
</div>
