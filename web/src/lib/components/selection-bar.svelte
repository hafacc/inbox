<!-- @component The bar (at the top, at the bottom at phone width) while threads or todos are selected: how many, a way to clear them, Select all, and the actions that apply to all of them at once (at phone width on the corner button just above the bar, placed by `selectionDisc`): threads and todos together get only Snooze, Pin, Delete and Done; Label, Mark read, Spam and Move to inbox need threads alone. Delete moves threads to the trash and deletes todos, or, with only threads all in the trash, moves them to the inbox. Done takes the threads in the inbox or snoozed and Move to inbox the rest, both showing when the selection holds both. -->
<script lang="ts">
  import {
    type SelectionAction,
    type SelectionDiscAction,
    selectionActions,
    selectionDisc,
    selectionPlan,
    todoSelectionPlan,
  } from "@inbox/shared/selection";
  import AlarmClockOff from "@lucide/svelte/icons/alarm-clock-off";
  import Check from "@lucide/svelte/icons/check";
  import Clock from "@lucide/svelte/icons/clock";
  import EllipsisVertical from "@lucide/svelte/icons/ellipsis-vertical";
  import Inbox from "@lucide/svelte/icons/inbox";
  import ListChecks from "@lucide/svelte/icons/list-checks";
  import ListX from "@lucide/svelte/icons/list-x";
  import Mail from "@lucide/svelte/icons/mail";
  import MailCheck from "@lucide/svelte/icons/mail-check";
  import MailOpen from "@lucide/svelte/icons/mail-open";
  import OctagonAlert from "@lucide/svelte/icons/octagon-alert";
  import Pin from "@lucide/svelte/icons/pin";
  import PinOff from "@lucide/svelte/icons/pin-off";
  import Tag from "@lucide/svelte/icons/tag";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import X from "@lucide/svelte/icons/x";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState, SELECTION_MENU } from "../mail-state.svelte";
  import { keyLabel, keyTitle, shiftKey } from "../shortcuts";
  import CornerButton from "./corner-button.svelte";
  import { iconButton, openColors } from "./icon-button";
  import type { IconType } from "./icons";
  import LabelMenu from "./label-menu.svelte";
  import { phoneWidth } from "./phone";
  import SnoozePopover from "./snooze-popover.svelte";

  const mail = getMailState();
  const hub = getComposeHub();
  const threads = $derived(mail.selectedThreads(mail.selectedThreadIds));
  const plan = $derived(selectionPlan(threads));
  const todoPlan = $derived(
    todoSelectionPlan(threads, mail.selectedTodos(mail.selectedTodoIds)),
  );
  // Threads and todos selected together offer only what both take (see `selectionActions`).
  const actions = $derived(
    selectionActions(threads.length, mail.selectedTodoIds.length),
  );
  const offers = (action: SelectionAction) => actions.includes(action);
  const anyThread = $derived(offers("label"));
  const canSnooze = $derived(
    offers("snooze") && (threads.length > 0 || todoPlan.snooze.length > 0),
  );
  const canPin = $derived(
    offers("pin") &&
      todoPlan.pin.threads.length + todoPlan.pin.todos.length > 0,
  );
  const inboxPlan = $derived(mail.selectionInboxPlan());
  const canDone = $derived(
    offers("done") && inboxPlan.done.length + todoPlan.done.length > 0,
  );
  const canInbox = $derived(offers("inbox") && inboxPlan.inbox.length > 0);
  const canDelete = $derived(
    offers("delete") &&
      todoPlan.delete.threads.length + todoPlan.delete.todos.length > 0,
  );
  const deleteWords = $derived(
    todoPlan.delete.untrash ? "Move to inbox" : "Delete",
  );
  const allWords = $derived(mail.allSelected ? "Select none" : "Select all");
  const snoozeOpen = $derived(mail.snoozeMenuFor === SELECTION_MENU);
  const labelOpen = $derived(mail.labelMenuFor === SELECTION_MENU);
  const shortcuts = $derived(hub.settings.shortcuts);
  const quietButton = iconButton("medium");
  const openButton = iconButton("medium", openColors);
  const pinWords = $derived(todoPlan.pin.on ? "Pin" : "Unpin");
  const readWords = $derived(plan.unread.on ? "Mark unread" : "Mark read");
  const readKey = $derived(shiftKey(plan.unread.on ? "U" : "I"));
  const spamWords = $derived(plan.spam.on ? "Report spam" : "Not spam");

  // On a phone every action is on the corner button, in the order of `selectionDisc`.
  const available = $derived(
    (
      [
        canDone && "done",
        canInbox && "inbox",
        canSnooze && "snooze",
        offers("wake") && plan.wake.length > 0 && "wake",
        canPin && "pin",
        anyThread && "label",
        anyThread && "read",
        anyThread && "spam",
        canDelete && "delete",
      ] as const
    ).filter((action): action is SelectionDiscAction => action !== false),
  );

  function discItem(action: SelectionDiscAction): {
    id: string;
    label: string;
    icon: IconType;
    tone?: "danger" | "done" | "on";
  } {
    switch (action) {
      case "done":
        return { id: action, label: "Done", icon: Check, tone: "done" };
      case "inbox":
        return { id: action, label: "Move to inbox", icon: Inbox };
      case "snooze":
        return { id: action, label: "Snooze", icon: Clock };
      case "wake":
        return { id: action, label: "Wake now", icon: AlarmClockOff };
      case "pin":
        return todoPlan.pin.on
          ? { id: action, label: pinWords, icon: Pin }
          : { id: action, label: pinWords, icon: PinOff, tone: "on" };
      case "label":
        return { id: action, label: "Label", icon: Tag };
      case "read":
        return {
          id: action,
          label: readWords,
          icon: plan.unread.on ? Mail : MailOpen,
        };
      case "spam":
        return plan.spam.on
          ? { id: action, label: spamWords, icon: OctagonAlert, tone: "danger" }
          : { id: action, label: spamWords, icon: MailCheck };
      case "delete":
        return todoPlan.delete.untrash
          ? { id: action, label: deleteWords, icon: Inbox }
          : { id: action, label: deleteWords, icon: Trash2, tone: "danger" };
    }
  }

  function discMenu() {
    const { inner, outer } = selectionDisc(available, threads.length === 0);
    return { inner: inner.map(discItem), outer: outer.map(discItem) };
  }

  function runDisc(id: string) {
    const action = id as SelectionDiscAction;
    if (action === "snooze") mail.openSnoozeMenu(SELECTION_MENU);
    else if (action === "label") mail.toggleLabelMenu(SELECTION_MENU);
    else if (action === "read") mail.actOnSelection("unread");
    else mail.actOnSelection(action);
  }
</script>

<div
  role="toolbar"
  aria-label="Selected items"
  class="w-full flex items-center gap-0.5 sm:gap-1 animate-fade-in"
>
  <button
    type="button"
    aria-label="Clear selection (Esc)"
    title="Clear selection  Esc"
    onclick={() => mail.dispatch({ type: "clearSelection" })}
    class={quietButton}
  >
    <X class="w-5 h-5" />
  </button>
  <span
    class="pl-1 text-[15px] font-semibold tabular-nums whitespace-nowrap"
    aria-live="polite"
  >
    {mail.selected.length}
    <span class="max-sm:sr-only">selected</span>
  </span>
  <button
    type="button"
    aria-label={allWords}
    title={allWords}
    onclick={() => mail.dispatch({ type: "pickAll" })}
    class="relative ml-1 h-9 shrink-0 flex items-center gap-1.5 px-2.5 rounded-full text-[13px] font-medium text-text-secondary hover:bg-surface-hover hover:text-text transition-colors pointer-coarse:after:absolute pointer-coarse:after:-inset-y-1.5 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
  >
    {#if mail.allSelected}
      <ListX class="w-[18px] h-[18px]" aria-hidden="true" />
    {:else}
      <ListChecks class="w-[18px] h-[18px]" aria-hidden="true" />
    {/if}
    <span class="hidden sm:inline">{allWords}</span>
  </button>
  <span class="flex-1"></span>
  {#if phoneWidth.current}
    {#if available.length > 0}
      <CornerButton
        label="Selection actions"
        icon={EllipsisVertical}
        bottom="calc(4.25rem + var(--safe-bottom))"
        menu={discMenu}
        onPick={runDisc}
      />
    {/if}
    {#if snoozeOpen}
      <SnoozePopover
        onPick={(epoch, source, key) =>
          mail.snoozeSelection(epoch, source, key)}
        onClose={mail.closeSnoozeMenu}
      />
    {/if}
    {#if labelOpen}
      <LabelMenu
        threadIds={mail.selectedThreadIds}
        onAction={mail.settleSelection}
      />
    {/if}
  {:else}
    {#if offers("wake") && plan.wake.length > 0}
      <button
        type="button"
        onclick={() => mail.actOnSelection("wake")}
        class="relative h-9 shrink-0 flex items-center gap-1.5 px-3 rounded-full border border-border text-[13px] font-medium hover:bg-surface-hover transition-colors pointer-coarse:after:absolute pointer-coarse:after:-inset-y-1.5 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
      >
        <AlarmClockOff class="w-4 h-4" aria-hidden="true" />
        <span class="max-sm:sr-only">Wake now</span>
      </button>
    {/if}
    {#if canSnooze}
      <div class="relative">
        <button
          type="button"
          aria-label={keyLabel("Snooze", "b", shortcuts)}
          title={keyTitle("Snooze", "b", shortcuts)}
          aria-haspopup="menu"
          aria-expanded={snoozeOpen}
          onclick={() => {
            if (snoozeOpen) mail.closeSnoozeMenu();
            else mail.openSnoozeMenu(SELECTION_MENU);
          }}
          class={snoozeOpen ? openButton : quietButton}
        >
          <Clock class="w-[18px] h-[18px]" />
        </button>
        {#if snoozeOpen}
          <SnoozePopover
            onPick={(epoch, source, key) =>
              mail.snoozeSelection(epoch, source, key)}
            onClose={mail.closeSnoozeMenu}
          />
        {/if}
      </div>
    {/if}
    {#if canPin}
      <button
        type="button"
        aria-label={keyLabel(pinWords, "p", shortcuts)}
        title={keyTitle(pinWords, "p", shortcuts)}
        onclick={() => mail.actOnSelection("pin")}
        class={quietButton}
      >
        {#if todoPlan.pin.on}
          <Pin class="w-[18px] h-[18px]" />
        {:else}
          <PinOff class="w-[18px] h-[18px]" />
        {/if}
      </button>
    {/if}
    {#if anyThread}
      <div class="relative">
        <button
          type="button"
          aria-label={keyLabel("Label", "l", shortcuts)}
          title={keyTitle("Label", "l", shortcuts)}
          aria-haspopup="dialog"
          aria-expanded={labelOpen}
          onclick={() => mail.toggleLabelMenu(SELECTION_MENU)}
          class={labelOpen ? openButton : quietButton}
        >
          <Tag class="w-[18px] h-[18px]" />
        </button>
        {#if labelOpen}
          <LabelMenu
            threadIds={mail.selectedThreadIds}
            onAction={mail.settleSelection}
          />
        {/if}
      </div>
      <button
        type="button"
        aria-label={keyLabel(readWords, readKey, shortcuts)}
        title={keyTitle(readWords, readKey, shortcuts)}
        onclick={() => mail.actOnSelection("unread")}
        class={quietButton}
      >
        {#if plan.unread.on}
          <Mail class="w-[18px] h-[18px]" />
        {:else}
          <MailOpen class="w-[18px] h-[18px]" />
        {/if}
      </button>
      <button
        type="button"
        aria-label={keyLabel(spamWords, "!", shortcuts)}
        title={keyTitle(spamWords, "!", shortcuts)}
        onclick={() => mail.actOnSelection("spam")}
        class={quietButton}
      >
        {#if plan.spam.on}
          <OctagonAlert class="w-[18px] h-[18px]" />
        {:else}
          <MailCheck class="w-[18px] h-[18px]" />
        {/if}
      </button>
    {/if}
    {#if canDelete}
      <button
        type="button"
        aria-label={keyLabel(deleteWords, "#", shortcuts)}
        title={keyTitle(deleteWords, "#", shortcuts)}
        onclick={() => mail.actOnSelection("delete")}
        class={quietButton}
      >
        {#if todoPlan.delete.untrash}
          <Inbox class="w-[18px] h-[18px]" />
        {:else}
          <Trash2 class="w-[18px] h-[18px]" />
        {/if}
      </button>
    {/if}
    {#if canInbox}
      <button
        type="button"
        onclick={() => mail.actOnSelection("inbox")}
        title={canDone
          ? "Move to inbox"
          : keyTitle("Move to inbox", "e", shortcuts)}
        class="relative ml-1 h-9 shrink-0 flex items-center gap-2 pl-3.5 pr-3 rounded-full border border-border text-[13px] font-semibold hover:bg-surface-hover transition-colors pointer-coarse:after:absolute pointer-coarse:after:-inset-y-1.5 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
      >
        <Inbox class="w-4 h-4" aria-hidden="true" />
        <span class="max-sm:sr-only">Move to inbox</span>
      </button>
    {/if}
    {#if canDone}
      <button
        type="button"
        onclick={() => mail.actOnSelection("done")}
        title={keyTitle("Done", "e", shortcuts)}
        class="relative ml-1 h-9 shrink-0 flex items-center gap-2 pl-3.5 pr-3 rounded-full bg-done-soft text-done text-[13px] font-semibold hover:brightness-95 transition pointer-coarse:after:absolute pointer-coarse:after:-inset-y-1.5 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
      >
        <Check class="w-4 h-4" aria-hidden="true" />
        <span class="max-sm:sr-only">Done</span>
      </button>
    {/if}
  {/if}
</div>
