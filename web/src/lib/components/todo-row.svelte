<!-- @component One todo in a list: its select dot (a tap selects the row, as a thread's avatar does; a hold opens a half disc with its own Pin and Delete around the finger, a right click around the dot), its text (edited in place on a click or Enter), its repeat and snooze, and actions on hover or focus. On touch, a swipe right snoozes (or makes it active again) and left marks it done (or deletes a done one); a long press anywhere on it opens the same half disc around the finger. While anything is selected, a click selects or unselects it, shift-click selects a range, and a swipe still acts on this todo alone. -->
<script lang="ts" module>
  import { MediaQuery } from "svelte/reactivity";

  // One for every row: each instance would add its own media listener.
  const touch = new MediaQuery("hover: none");
</script>

<script lang="ts">
  import { EditorView } from "@codemirror/view";
  import { rowHoldWedges } from "@inbox/shared/hold-menu";
  import { rowTodoId, todoRowKey } from "@inbox/shared/list-state";
  import { isoToEpoch } from "@inbox/shared/local-time";
  import { type TodoSwipeAction, todoSwipeAction } from "@inbox/shared/swipe";
  import {
    formatListDate,
    formatWakeTime,
    lapsedMarker,
  } from "@inbox/shared/time-format";
  import {
    isSnoozed,
    type RepeatDraft,
    repeatText,
    suggestTags,
    type Todo,
    todoListDate,
  } from "@inbox/shared/todos";
  import AlarmClockOff from "@lucide/svelte/icons/alarm-clock-off";
  import Check from "@lucide/svelte/icons/check";
  import Clock from "@lucide/svelte/icons/clock";
  import Pin from "@lucide/svelte/icons/pin";
  import PinOff from "@lucide/svelte/icons/pin-off";
  import RepeatIcon from "@lucide/svelte/icons/repeat";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import Undo2 from "@lucide/svelte/icons/undo-2";
  import { flushSync, onDestroy, untrack } from "svelte";
  import type { Attachment } from "svelte/attachments";
  import type { ClassValue } from "svelte/elements";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { HoldPress } from "../hold-menu";
  import { type HoldMenuItem, holdMenu } from "../hold-menu.svelte";
  import { inputMode } from "../input-mode.svelte";
  import { hasModifier, isEditableTarget } from "../keyboard";
  import { getMailState } from "../mail-state.svelte";
  import { keyLabel, keyTitle } from "../shortcuts";
  import type { TodoEditorHandle } from "../todos/todo-editor";
  import {
    iconButton,
    pinnedColors,
    quietColors,
    snoozeOpenColors,
    toneColors,
  } from "./icon-button";
  import { RowSwipe } from "./row-swipe.svelte";
  import SnoozePopover from "./snooze-popover.svelte";
  import StyledText from "./styled-text.svelte";
  import SwipeBackdrop, { type SwipeLook } from "./swipe-backdrop.svelte";
  import TodoEditor from "./todo-editor.svelte";

  let {
    todo,
    class: className = "",
  }: {
    /** the todo shown */
    todo: Todo;
    /** classes added to the row */
    class?: ClassValue;
  } = $props();

  const SWIPE_STYLE: Record<TodoSwipeAction, SwipeLook> = {
    snooze: {
      soft: "bg-snooze-soft text-on-snooze-soft",
      strong: "bg-snooze text-on-snooze",
      label: "Snooze",
      icon: Clock,
    },
    reactivate: {
      soft: "bg-accent-soft text-accent-strong",
      strong: "bg-accent text-on-accent",
      label: "Restore",
      icon: Undo2,
    },
    done: {
      soft: "bg-done-soft text-done",
      strong: "bg-done text-on-done",
      label: "Done",
      icon: Check,
    },
    delete: {
      soft: "bg-danger-soft text-danger",
      strong: "bg-danger text-on-danger",
      label: "Delete",
      icon: Trash2,
    },
  };

  const mail = getMailState();
  const hub = getComposeHub();
  const focusKey = $derived(todoRowKey(todo.id));
  const editing = $derived(mail.todos.editingId === todo.id);
  const focused = $derived(mail.focusKey === focusKey);
  const keyboardFocused = $derived(focused && inputMode.keyboard && !editing);
  const menuOpen = $derived(mail.snoozeMenuFor === focusKey);
  const isDone = $derived(todo.state === "DONE");
  const snoozed = $derived(isSnoozed(todo, mail.now));
  const woke = $derived(!isDone && !snoozed && todo.snoozeUntil !== null);
  const doneLabel = $derived(isDone ? "Mark not done" : "Mark done");
  const selecting = $derived(mail.selected.length > 0);
  const selected = $derived(selecting && mail.selected.includes(focusKey));

  // While the editor has focus its text is kept here, so a list update can't move the caret.
  let draft = $state("");
  let editorFocused = $state(false);
  let hovered = $state(false);
  let focusWithin = $state(false);
  let editor = $state<TodoEditorHandle | null>(null);
  let element = $state<HTMLDivElement>();
  let staticText = $state<HTMLDivElement>();
  // Where a press on the text landed, and the row's top then: opening this todo can end another's
  // edit and move that one, shifting this row before its editor places the caret.
  let pendingCaret: { x: number; y: number; top: number } | null = null;
  let dot = $state<HTMLButtonElement>();

  const text = $derived(editing && editorFocused ? draft : todo.text);
  const actionsShown = $derived(
    !touch.current &&
      !selecting &&
      (menuOpen ||
        hovered ||
        (inputMode.keyboard && (focused || focusWithin) && !editing)),
  );
  const swipeRow = $derived({ done: isDone, snoozed });
  const startAction = $derived(todoSwipeAction("start", swipeRow));
  const endAction = $derived(todoSwipeAction("end", swipeRow));
  // Under a slid-off row stays the move it was let go with until it snaps back: the action
  // changes the todo at once (Done would turn the move under it into Delete). A long press
  // anywhere on it, but in the open editor (the browser's text selection there), opens the dot's
  // menu, the finger sliding to a wedge from where it is.
  const swipe = new RowSwipe<TodoSwipeAction>(
    () => ({ start: startAction, end: endAction }),
    (action) => runSwipe(action),
    (pointerId, press) => openHoldMenu(press, pointerId, press, false),
    () => !editing,
  );
  const startStyle = $derived(
    swipe.shown.start && SWIPE_STYLE[swipe.shown.start],
  );
  const endStyle = $derived(swipe.shown.end && SWIPE_STYLE[swipe.shown.end]);

  // Runs when the row starts editing and again once its editor exists, whichever comes last.
  $effect(() => {
    const handle = editor;
    if (!editing || !handle) return;
    untrack(() => {
      const pending = pendingCaret;
      pendingCaret = null;
      const hint =
        mail.todos.caretHint?.id === todo.id ? mail.todos.caretHint : null;
      mail.todos.caretHint = null;
      if (hint && !pending) {
        handle.focusAtEdge(hint.x, hint.edge);
      } else {
        handle.focus(
          (pending &&
            handle.posAtCoords(pending.x, pending.y + rowShift(pending.top))) ??
            "end",
        );
      }
    });
  });

  // DOM focus follows the list's, without scrolling: only the keyboard scrolls, by the list.
  $effect(() => {
    if (!focused || editing || !staticText) return;
    if (!isEditableTarget(document.activeElement)) {
      staticText.focus({ preventScroll: true });
    }
  });

  function pick(range: boolean) {
    mail.dispatch({ type: "pick", key: focusKey, range });
  }

  function startEditing() {
    mail.dispatch({ type: "focus", key: focusKey, by: "pointer" });
    mail.todos.setEditing(todo.id);
  }

  // A press on the row around the text (above, below, beside it) opens it with the caret at the
  // end, as on Android; the text itself puts the caret where it was pressed.
  function onRowMouseDown(event: MouseEvent) {
    const target = event.target as Element;
    if (editing || staticText?.contains(target)) return;
    if (target.closest("button, a, input, [role=menu]")) return;
    if (event.button !== 0 || swipe.guarding) return;
    if (selecting || event.shiftKey) return;
    event.preventDefault();
    pendingCaret = null;
    startEditing();
    flushSync();
  }

  // Ends a drag selecting the editor's text that began on the static text.
  let endTextDrag: (() => void) | null = null;

  // The native mousedown focus would land on the static text, which the editor replaces mid-event.
  function onTextMouseDown(event: MouseEvent) {
    if (event.button !== 0 || swipe.guarding) return;
    // Selecting, a click picks the row rather than opening its text.
    if (selecting || event.shiftKey) {
      event.preventDefault();
      return;
    }
    event.preventDefault();
    const start = {
      x: event.clientX,
      y: event.clientY,
      top: element?.getBoundingClientRect().top ?? 0,
    };
    pendingCaret = start;
    startEditing();
    // Mount and focus the editor inside this event: iOS raises the keyboard only for a focus made during the tap.
    flushSync();
    // The editor mounts under the pointer mid-drag, so its selection is extended by hand.
    const onMove = (move: MouseEvent) => {
      const anchor = editor?.posAtCoords(
        start.x,
        start.y + rowShift(start.top),
      );
      const head = editor?.posAtCoords(move.clientX, move.clientY);
      if (editor && anchor != null && head != null) editor.select(anchor, head);
    };
    endTextDrag?.();
    const onUp = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      endTextDrag = null;
    };
    endTextDrag = onUp;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }

  // How far the row has moved on screen since its top was `top`.
  function rowShift(top: number): number {
    return (element?.getBoundingClientRect().top ?? top) - top;
  }

  // Leaving the editor ends the edit, as `setEditing` does for every way out. The window losing
  // focus (another tab or app) only blurs it: the edit stays open, its typing saved as the page hides.
  function onEditorBlur() {
    if (!document.hasFocus()) return;
    editorFocused = false;
    if (mail.todos.editingId === todo.id) mail.todos.setEditing(null);
  }

  // Up on the first line or down on the last (wrapped lines count) moves to the next row, as in
  // latr: into its text when it is a todo, keeping the caret's column.
  function onEditorKey(event: KeyboardEvent) {
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
    if (event.shiftKey || hasModifier(event)) return;
    const view = EditorView.findFromDOM(event.target as HTMLElement);
    if (!view) return;
    const { from, to, assoc, head, goalColumn } = view.state.selection.main;
    const up = event.key === "ArrowUp";
    if (up) {
      const caret = view.coordsAtPos(from, assoc || 1);
      const first = view.coordsAtPos(0, 1);
      if (caret && first && caret.top > first.bottom - 1) return;
    } else {
      const caret = view.coordsAtPos(to, assoc || -1);
      const last = view.coordsAtPos(view.state.doc.length, -1);
      if (caret && last && caret.bottom < last.top + 1) return;
    }
    const before = mail.list.focus;
    const next = mail.dispatch({ type: up ? "previous" : "next" }).focus;
    if (next === null || next === before) return;
    event.preventDefault();
    const nextTodo = rowTodoId(next);
    if (nextTodo !== null) {
      const left = view.contentDOM.getBoundingClientRect().left;
      // CodeMirror keeps the column a run of up and down presses started from.
      const x =
        goalColumn !== undefined
          ? left + goalColumn
          : (view.coordsAtPos(head, assoc || 1)?.left ?? left);
      mail.todos.caretHint = { id: nextTodo, x, edge: up ? "last" : "first" };
      mail.todos.setEditing(nextTodo);
    } else {
      view.contentDOM.blur();
    }
  }

  function onPick(
    epoch: number,
    source: Parameters<typeof mail.snoozeTodo>[2],
    key?: string,
  ) {
    mail.snoozeTodo(todo.id, epoch, source, key);
  }

  function onRepeat(rule: RepeatDraft) {
    mail.setTodoRepeat(todo.id, rule);
  }

  function toggleSnoozeMenu() {
    if (menuOpen) mail.closeSnoozeMenu();
    else mail.openSnoozeMenu(focusKey);
  }

  function runSwipe(action: TodoSwipeAction) {
    // As on Android, a swipe while editing saves the text first, as leaving the editor does.
    if (editing) editor?.blur();
    switch (action) {
      case "snooze":
        mail.openSnoozeMenu(focusKey);
        break;
      case "reactivate":
        mail.wakeTodo(todo.id);
        break;
      case "done":
        mail.toggleTodoDone(todo.id);
        break;
      case "delete":
        mail.deleteTodos([todo.id]);
        break;
    }
  }

  // A press on the select dot that may become a hold, opening the todo's own Pin and Delete.
  const dotPress = new HoldPress();
  let held = $state(false);

  onDestroy(() => {
    dotPress.cancel();
    endTextDrag?.();
  });

  // The middle of the select dot, where the menu key and a right click open its menu.
  function middleOf(control: HTMLElement): { x: number; y: number } {
    const rect = control.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  }

  function openHoldMenu(
    center: { x: number; y: number },
    pointerId: number | null,
    press: { x: number; y: number },
    focusFirst: boolean,
  ) {
    const { x, y } = center;
    const id = todo.id;
    const items: HoldMenuItem[] = rowHoldWedges(
      [isDone ? null : "pin", "delete"] as const,
      x,
      y,
      window.innerWidth,
      window.innerHeight,
    ).map((wedge) =>
      wedge.id === "pin"
        ? {
            ...wedge,
            label: todo.pinned ? "Unpin" : "Pin",
            icon: todo.pinned ? PinOff : Pin,
          }
        : { ...wedge, label: "Delete", icon: Trash2, tone: "danger" },
    );
    held = true;
    holdMenu.open({
      x,
      y,
      items,
      pill: todo.text.split("\n")[0]?.trim() || "Todo",
      label: "Todo actions",
      pointerId,
      press,
      focusFirst,
      onPick: (action) => {
        // The action then sees the text as typed, as leaving the editor saves it.
        if (editing) editor?.blur();
        if (action === "pin") mail.toggleTodoPin(id);
        else mail.deleteTodos([id]);
      },
      onClose: () => {
        held = false;
        swipe.endHold();
      },
    });
  }

  function onDotPointerDown(
    event: PointerEvent & { currentTarget: HTMLButtonElement },
  ) {
    // The row's own swipe and long press stay out of a press on the dot.
    event.stopPropagation();
    if (event.button !== 0) return;
    const control = event.currentTarget;
    const pointerId = event.pointerId;
    const press = { x: event.clientX, y: event.clientY };
    try {
      control.setPointerCapture(pointerId);
    } catch {
      // A pointer the browser no longer tracks can't be captured; the press still works.
    }
    dotPress.start(pointerId, press.x, press.y, () =>
      openHoldMenu(press, pointerId, press, false),
    );
  }

  // A press on the dot never scrolls the list, so sliding up to a wedge stays the menu's. The
  // browser decides whether a touch may scroll when it starts, so this listener must be there
  // before, not added once the menu opens, and not passive (as Svelte makes `ontouchmove`).
  const holdsTouch: Attachment<HTMLElement> = (element) => {
    const block = (event: TouchEvent) => {
      if (event.cancelable) event.preventDefault();
    };
    element.addEventListener("touchmove", block, { passive: false });
    return () => element.removeEventListener("touchmove", block);
  };

  function onDotPointerMove(event: PointerEvent) {
    dotPress.move(event.pointerId, event.clientX, event.clientY);
  }

  function onDotContextMenu(
    event: MouseEvent & { currentTarget: HTMLButtonElement },
  ) {
    event.preventDefault();
    event.stopPropagation();
    // A touch held this long already opened the menu.
    if (dotPress.pending || holdMenu.current !== null) return;
    // The menu key sends no position.
    const fromKeyboard = event.clientX === 0 && event.clientY === 0;
    openHoldMenu(
      middleOf(event.currentTarget),
      null,
      { x: event.clientX, y: event.clientY },
      fromKeyboard,
    );
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<!-- biome-ignore lint/a11y/noStaticElementInteractions: touch swipes and hover only; every action here is also a button -->
<div
  bind:this={element}
  data-todo-id={todo.id}
  data-row-key={focusKey}
  onpointerdown={swipe.down}
  onpointermove={swipe.move}
  onpointerup={swipe.up}
  onpointercancel={swipe.cancel}
  oncontextmenu={(event) => {
    // The menu key sends no position: on a focused row it opens the dot's menu, as on the dot.
    const fromKeyboard = event.clientX === 0 && event.clientY === 0;
    if (fromKeyboard && !editing && dot) {
      event.preventDefault();
      if (holdMenu.current === null)
        openHoldMenu(middleOf(dot), null, { x: 0, y: 0 }, true);
    } else if (swipe.holding || held) {
      // A long touch is opening the hold menu, not asking for the browser's.
      event.preventDefault();
    }
  }}
  onmouseenter={() => (hovered = true)}
  onmouseleave={() => (hovered = false)}
  onfocusin={() => (focusWithin = true)}
  onfocusout={(event) => {
    const within = element?.contains(event.relatedTarget as Node | null);
    // Focus also leaves while the row is torn down, when state can't change yet.
    queueMicrotask(() => {
      focusWithin = within ?? false;
    });
  }}
  onclickcapture={(event) => {
    if (
      !swipe.clickCapture(event) &&
      (selecting || event.shiftKey) &&
      !editing
    ) {
      event.preventDefault();
      event.stopPropagation();
      pick(event.shiftKey);
    }
  }}
  class={[
    "group/row relative touch-pan-y",
    swipe.dx !== 0 && "overflow-hidden",
    menuOpen && "z-20",
    className,
  ]}
>
  <SwipeBackdrop
    dx={swipe.dx}
    width={swipe.width}
    start={startStyle}
    end={endStyle}
  />

  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <!-- biome-ignore lint/a11y/noStaticElementInteractions: a press around the text opens it, as a press on the text (a textbox) does -->
  <div
    style:transform={swipe.dx !== 0 ? `translateX(${swipe.dx}px)` : null}
    ontransitionend={() => (swipe.settling = false)}
    onmousedown={onRowMouseDown}
    class={[
      "relative flex items-center gap-3.5 px-5 py-1 rounded-[inherit]",
      swipe.dx !== 0
        ? "bg-surface-raised shadow-raised"
        : selected
          ? "bg-accent-soft"
          : editing || held
            ? "bg-surface-hover"
            : "bg-surface hover:bg-surface-hover",
      keyboardFocused && "shadow-[inset_0_0_0_2px_var(--color-focus)]",
      swipe.settling
        ? "transition-transform duration-200 ease-out"
        : "transition-colors duration-150",
    ]}
  >
    <button
      type="button"
      tabindex="-1"
      data-todo-dot
      onclick={(event) => pick(event.shiftKey)}
      onpointerdown={onDotPointerDown}
      onpointermove={onDotPointerMove}
      bind:this={dot}
      onpointerup={() => dotPress.cancel()}
      onpointercancel={() => dotPress.cancel()}
      oncontextmenu={onDotContextMenu}
      {@attach holdsTouch}
      aria-label={`${selected ? "Unselect" : "Select"} todo`}
      aria-pressed={selected}
      aria-haspopup="menu"
      title={selected
        ? "Unselect (hold for Pin and Delete)"
        : "Select (hold for Pin and Delete)"}
      class="group/check hit-target w-9 h-9 shrink-0 self-start mt-1.5 flex items-center justify-center rounded-full select-none touch-none [-webkit-touch-callout:none] hover:bg-surface-muted transition-colors"
    >
      {#if selected}
        <span
          class="w-[22px] h-[22px] rounded-full bg-accent flex items-center justify-center"
        >
          <span class="w-2 h-2 rounded-full bg-on-accent"></span>
        </span>
      {:else if isDone}
        <span
          class="w-5 h-5 rounded-full bg-done text-on-done flex items-center justify-center"
        >
          <Check class="w-3.5 h-3.5" strokeWidth={3} />
        </span>
      {:else if snoozed}
        <Clock class="w-5 h-5 text-snooze" />
      {:else if todo.pinned}
        <Pin class="w-5 h-5 text-accent" />
      {:else}
        <span
          class="w-2 h-2 rounded-full bg-border-strong transition-transform duration-100 group-hover/check:scale-[1.4]"
        ></span>
      {/if}
    </button>

    <div class="flex-1 min-w-0 flex flex-col gap-[3px] py-[13px]">
      {#if editing}
        <!-- svelte-ignore a11y_no_static_element_interactions -->
        <!-- biome-ignore lint/a11y/noStaticElementInteractions: catches the arrow keys leaving the editor inside it -->
        <div onkeydown={onEditorKey} class="flex min-w-0 cursor-text">
          <TodoEditor
            bind:handle={editor}
            value={text}
            onChange={(value) => {
              draft = value;
              mail.todos.edit(todo.id, value);
            }}
            onFocus={() => {
              draft = todo.text;
              editorFocused = true;
            }}
            onBlur={onEditorBlur}
            onEnter={(view) => {
              // Enter on a todo with text starts the next one above it; on an empty one it ends.
              if (draft.trim() !== "" && mail.newTodoAbove(todo.id)) {
                editorFocused = false;
              } else {
                view.contentDOM.blur();
              }
            }}
            tagSuggestions={(prefix) =>
              suggestTags(
                mail.todos.tagCounts,
                todo.text,
                prefix,
                undefined,
                mail.labelTagNames,
              )}
            tagColor={mail.tagColor}
            onTagOffer={(offer) => (mail.todos.tagOffer = offer)}
            multiline
            label="Todo"
            enterKeyHint={mail.startsTodoAbove(todo.id) ? "next" : "done"}
            class={[
              "flex-1 min-w-0 text-[14.5px] leading-[22px]",
              isDone ? "line-through text-text-secondary" : "text-text",
            ].join(" ")}
          />
        </div>
      {:else}
        <!-- Focusable so j, k and the action keys reach it; Enter or a click opens it to edit. -->
        <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
        <!-- biome-ignore lint/a11y/useSemanticElements: styled text that turns into the editor when it opens -->
        <div
          bind:this={staticText}
          role="textbox"
          aria-multiline="true"
          aria-readonly="true"
          aria-label="Todo"
          tabindex="0"
          data-shortcuts
          onmousedown={onTextMouseDown}
          class={[
            "todo-text min-w-0 whitespace-pre-wrap break-words outline-none cursor-text text-[14.5px] leading-[22px] pointer-coarse:select-none",
            isDone ? "line-through text-text-secondary" : "text-text",
          ]}
        >
          {#if text === ""}
            {"​"}
          {:else}
            <StyledText {text} />
          {/if}
        </div>
      {/if}
      {#if !isDone && todo.repeat}
        <div
          class="flex items-center gap-1.5 text-[13px] leading-[18px] text-text-secondary"
        >
          <RepeatIcon class="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
          <span class="min-w-0 truncate">{repeatText(todo.repeat)}</span>
        </div>
      {/if}
    </div>

    <div
      class={[
        "self-start mt-[13px] h-[22px] flex items-center gap-2 shrink-0 transition-opacity duration-150",
        actionsShown && "opacity-0",
      ]}
    >
      {#if snoozed && todo.snoozeUntil}
        <span
          class="flex items-center gap-1.5 text-xs font-semibold text-on-snooze-soft tabular-nums"
        >
          <Clock class="w-3.5 h-3.5" aria-hidden="true" />
          {formatWakeTime(isoToEpoch(todo.snoozeUntil), mail.now)}
        </span>
      {:else if woke && todo.snoozeUntil}
        <span
          class="h-[22px] flex items-center gap-1.5 px-2 rounded-full bg-snooze-soft text-on-snooze-soft text-xs font-medium"
        >
          <Clock class="w-3.5 h-3.5" aria-hidden="true" />
          {lapsedMarker(isoToEpoch(todo.snoozeUntil), mail.now)}
        </span>
      {:else if isDone}
        <span class="text-xs tabular-nums text-text-secondary">
          {formatListDate(todoListDate(todo), mail.now)}
        </span>
      {/if}
    </div>

    {#if actionsShown}
      <div
        class="absolute right-3 top-1/2 -translate-y-1/2 flex items-center gap-1 rounded-xl bg-surface-raised shadow-pop p-0.5 animate-fade-in"
      >
        {#if snoozed}
          <button
            type="button"
            data-action="wake"
            onclick={() => mail.wakeTodo(todo.id)}
            class="h-8 flex items-center gap-1.5 px-2.5 rounded-full border border-border bg-surface text-[13px] font-medium hover:bg-surface-hover transition-colors"
          >
            <AlarmClockOff class="w-4 h-4" aria-hidden="true" />
            Wake now
          </button>
        {/if}
        {#if !isDone && !snoozed}
          <button
            type="button"
            data-action="pin"
            aria-label={keyLabel(
              todo.pinned ? "Unpin" : "Pin",
              "p",
              hub.settings.shortcuts,
            )}
            title={keyTitle(
              todo.pinned ? "Unpin" : "Pin",
              "p",
              hub.settings.shortcuts,
            )}
            aria-pressed={todo.pinned}
            onclick={() => mail.toggleTodoPin(todo.id)}
            class={iconButton(
              "small",
              todo.pinned ? pinnedColors : toneColors.pin,
            )}
          >
            {#if todo.pinned}
              <PinOff class="w-[18px] h-[18px]" />
            {:else}
              <Pin class="w-[18px] h-[18px]" />
            {/if}
          </button>
        {/if}
        {#if !isDone}
          <div class="relative">
            <button
              type="button"
              data-action="snooze"
              aria-label={keyLabel(
                snoozed ? "Change snooze time" : "Snooze",
                "b",
                hub.settings.shortcuts,
              )}
              title={keyTitle(
                snoozed ? "Change snooze time" : "Snooze",
                "b",
                hub.settings.shortcuts,
              )}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onclick={toggleSnoozeMenu}
              class={iconButton(
                "small",
                menuOpen ? snoozeOpenColors : toneColors.snooze,
              )}
            >
              <Clock class="w-[18px] h-[18px]" />
            </button>
            {#if menuOpen && !touch.current}
              <SnoozePopover
                {onPick}
                repeat={todo.repeat}
                {onRepeat}
                onClose={mail.closeSnoozeMenu}
              />
            {/if}
          </div>
        {/if}
        <button
          type="button"
          data-action="delete"
          aria-label={keyLabel("Delete", "#", hub.settings.shortcuts)}
          title={keyTitle("Delete", "#", hub.settings.shortcuts)}
          onclick={() => mail.deleteTodos([todo.id])}
          class={iconButton("small", toneColors.danger)}
        >
          <Trash2 class="w-[18px] h-[18px]" />
        </button>
        <button
          type="button"
          data-action="done"
          aria-label={keyLabel(doneLabel, "e", hub.settings.shortcuts)}
          title={keyTitle(doneLabel, "e", hub.settings.shortcuts)}
          onclick={() => mail.toggleTodoDone(todo.id)}
          class={iconButton("small", isDone ? quietColors : toneColors.done)}
        >
          {#if isDone}
            <Undo2 class="w-[18px] h-[18px]" />
          {:else}
            <Check class="w-[18px] h-[18px]" />
          {/if}
        </button>
      </div>
    {/if}
  </div>

  {#if menuOpen && touch.current}
    <div class="relative flex justify-end px-3">
      <SnoozePopover
        {onPick}
        repeat={todo.repeat}
        {onRepeat}
        onClose={mail.closeSnoozeMenu}
      />
    </div>
  {/if}
</div>
