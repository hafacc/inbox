<!-- @component The open thread's buttons in its header on a wide screen, in one line: the ones that don't fit `room` move, from the end of the row (Done or Move to inbox last), into a More menu at its end that names each with its key (`collapsedActions`); they come back as room allows. -->
<script lang="ts">
  import type { ThreadAction } from "@inbox/shared/view-filter";
  import EllipsisVertical from "@lucide/svelte/icons/ellipsis-vertical";
  import { untrack } from "svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { collapsedActions } from "../header-fit";
  import { touchOnly } from "../keyboard";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { keyLabel, keyTitle, threadActionKey } from "../shortcuts";
  import { iconButton, openColors, quietColors } from "./icon-button";
  import type { IconType } from "./icons";
  import ImportantMark from "./important-mark.svelte";
  import LabelMenu from "./label-menu.svelte";
  import { closeMenu } from "./menu-motion";
  import { popover } from "./popover";
  import SnoozePopover from "./snooze-popover.svelte";
  import ThreadButtons from "./thread-buttons.svelte";

  let {
    row,
    actions,
    room,
    words,
    icons,
    onRun,
    menuOpen = $bindable(false),
  }: {
    /** the open thread */
    row: Row;
    /** the actions it offers (`threadActions`) */
    actions: readonly ThreadAction[];
    /** the width the buttons may take, in px */
    room: number;
    /** an action's name */
    words: (action: ThreadAction) => string;
    /** an action's icon */
    icons: Readonly<Record<ThreadAction, IconType | null>>;
    /** take an action */
    onRun: (action: ThreadAction) => void;
    /** whether the More menu is open */
    menuOpen?: boolean;
  } = $props();

  // The header's left-to-right order, which differs from the menu order of `threadActions`.
  const ROW_ORDER: readonly ThreadAction[] = [
    "wake",
    "pin",
    "unpin",
    "snooze",
    "label",
    "read",
    "unread",
    "addTodo",
    "editTodo",
    "important",
    "unimportant",
    "filter",
    "delete",
    "untrash",
    "spam",
    "notSpam",
    "done",
    "inbox",
  ];
  // What each view is for stays in the row longest: Done, Move to inbox, Not spam.
  const LAST_TO_GO: readonly ThreadAction[] = [
    "done",
    "inbox",
    "untrash",
    "notSpam",
  ];
  const PILLS: readonly ThreadAction[] = ["wake", "untrash", "notSpam"];
  const pill =
    "h-9 shrink-0 flex items-center gap-2 px-3.5 rounded-full border border-border text-[13px] font-medium hover:bg-surface-hover transition-colors";
  const donePill =
    "h-9 shrink-0 flex items-center gap-2 pl-3.5 pr-4 rounded-full bg-done-soft text-done text-[13px] font-semibold hover:brightness-95 transition";
  const inboxPill =
    "h-9 shrink-0 flex items-center gap-2 pl-3.5 pr-4 rounded-full border border-border text-[13px] font-semibold hover:bg-surface-hover transition-colors";

  const mail = getMailState();
  const hub = getComposeHub();
  const id = $derived(row.thread.id);
  const ordered = $derived(
    ROW_ORDER.filter((action) => actions.includes(action)),
  );
  let ghost = $state<HTMLDivElement>();
  let widths = $state<ReadonlyMap<ThreadAction, number>>(new Map());
  const collapsed = $derived(
    collapsedActions(ordered, widths, room, LAST_TO_GO),
  );
  const shown = (action: ThreadAction) =>
    actions.includes(action) && !collapsed.includes(action);
  const important = $derived(actions.includes("unimportant"));
  const todo = $derived<ThreadAction>(
    actions.includes("editTodo") ? "editTodo" : "addTodo",
  );
  const keysShown = $derived(hub.settings.shortcuts && !touchOnly());

  // Every button's width, read off an unseen copy of the whole row so hidden ones count too.
  function measure() {
    if (!ghost) return;
    const next = new Map<ThreadAction, number>();
    for (const child of ghost.children) {
      const action = (child as HTMLElement).dataset.action as ThreadAction;
      next.set(action, child.getBoundingClientRect().width);
    }
    const same =
      next.size === widths.size &&
      [...next].every(([action, width]) => widths.get(action) === width);
    if (!same) widths = next;
  }

  $effect(() => {
    void ordered;
    const target = ghost;
    if (!target) return;
    untrack(measure);
    const observer = new ResizeObserver(measure);
    observer.observe(target);
    return () => observer.disconnect();
  });

  $effect(() => {
    if (collapsed.length === 0) menuOpen = false;
  });

  // A header that goes (at phone width) takes its menu with it, so the thread drops its raised layer.
  $effect(() => () => {
    menuOpen = false;
  });

  function title(action: ThreadAction): string {
    const key = threadActionKey(action);
    if (key === null) return words(action);
    else return keyTitle(words(action), key, hub.settings.shortcuts);
  }

  function pick(action: ThreadAction) {
    menuOpen = false;
    onRun(action);
  }

  function focusFirst(menu: HTMLElement) {
    menu.querySelector<HTMLElement>("[role=menuitem]")?.focus();
  }

  function onMenuKey(event: KeyboardEvent) {
    if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
    const items = [
      ...document.querySelectorAll<HTMLElement>(
        "[data-more-menu] [role=menuitem]",
      ),
    ];
    if (items.length === 0) return;
    event.preventDefault();
    event.stopPropagation();
    const at = items.indexOf(document.activeElement as HTMLElement);
    const step = event.key === "ArrowDown" ? 1 : -1;
    items[(at + step + items.length) % items.length]?.focus();
  }
</script>

{#snippet face(action: ThreadAction, size: string)}
  {@const Icon = icons[action]}
  {#if Icon === null}
    <ImportantMark filled={action === "unimportant"} class={size} />
  {:else}
    <Icon class={size} aria-hidden="true" />
  {/if}
{/snippet}

<div
  class="ml-auto shrink-0 flex items-center gap-3 pointer-events-none [&>*]:pointer-events-auto"
>
  <div
    bind:this={ghost}
    aria-hidden="true"
    inert
    class="invisible absolute top-0 right-0 w-max flex gap-3 !pointer-events-none"
  >
    {#each ordered as action (action)}
      {#if PILLS.includes(action)}
        <span data-action={action} class={pill}>
          {@render face(action, "w-4 h-4")}{words(action)}
        </span>
      {:else if action === "done" || action === "inbox"}
        <span
          data-action={action}
          class={action === "done" ? donePill : inboxPill}
        >
          {@render face(action, "w-4 h-4")}{words(action)}
        </span>
      {:else}
        <span data-action={action} class="w-9 h-9 shrink-0"></span>
      {/if}
    {/each}
  </div>
  {#if shown("wake")}
    <button type="button" onclick={() => onRun("wake")} class={pill}>
      {@render face("wake", "w-4 h-4")}
      {words("wake")}
    </button>
  {/if}
  <ThreadButtons
    {row}
    size="medium"
    showPin={shown("pin") || shown("unpin")}
    showSnooze={shown("snooze")}
    showLabel={shown("label")}
    showRead={shown("read") || shown("unread")}
  />
  {#if shown(todo)}
    <button
      type="button"
      aria-label={words(todo)}
      title={words(todo)}
      onclick={() => onRun(todo)}
      class={iconButton("medium")}
    >
      {@render face(todo, "w-[18px] h-[18px]")}
    </button>
  {/if}
  {#if shown("important") || shown("unimportant")}
    <button
      type="button"
      aria-label={keyLabel(
        "Important",
        important ? "-" : "+",
        hub.settings.shortcuts,
      )}
      title={title(important ? "unimportant" : "important")}
      aria-pressed={important}
      onclick={() => onRun(important ? "unimportant" : "important")}
      class={iconButton(
        "medium",
        important ? "text-accent hover:bg-surface-hover" : quietColors,
      )}
    >
      <ImportantMark filled={important} class="w-[18px] h-[18px]" />
    </button>
  {/if}
  {#if shown("filter")}
    <button
      type="button"
      aria-label={words("filter")}
      title={words("filter")}
      onclick={() => onRun("filter")}
      class={iconButton("medium")}
    >
      {@render face("filter", "w-[18px] h-[18px]")}
    </button>
  {/if}
  {#if shown("untrash")}
    <button
      type="button"
      title={title("untrash")}
      onclick={() => onRun("untrash")}
      class={pill}
    >
      {@render face("untrash", "w-4 h-4")}
      {words("untrash")}
    </button>
  {:else if shown("delete")}
    <button
      type="button"
      aria-label={keyLabel("Delete", "#", hub.settings.shortcuts)}
      title={title("delete")}
      onclick={() => onRun("delete")}
      class={iconButton("medium")}
    >
      {@render face("delete", "w-[18px] h-[18px]")}
    </button>
  {/if}
  {#if shown("notSpam")}
    <button
      type="button"
      title={title("notSpam")}
      onclick={() => onRun("notSpam")}
      class={pill}
    >
      {@render face("notSpam", "w-4 h-4")}
      {words("notSpam")}
    </button>
  {:else if shown("spam")}
    <button
      type="button"
      aria-label={keyLabel("Report spam", "!", hub.settings.shortcuts)}
      title={title("spam")}
      onclick={() => onRun("spam")}
      class={iconButton("medium")}
    >
      {@render face("spam", "w-[18px] h-[18px]")}
    </button>
  {/if}
  {#if shown("done")}
    <button
      type="button"
      title={title("done")}
      onclick={() => onRun("done")}
      class={donePill}
    >
      {@render face("done", "w-4 h-4")}
      {words("done")}
    </button>
  {:else if shown("inbox")}
    <button
      type="button"
      title={title("inbox")}
      onclick={() => onRun("inbox")}
      class={inboxPill}
    >
      {@render face("inbox", "w-4 h-4")}
      {words("inbox")}
    </button>
  {/if}
  {#if collapsed.length > 0}
    <div class="relative">
      <button
        type="button"
        aria-label="More"
        title="More"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onclick={() => {
          menuOpen = !menuOpen;
        }}
        class={iconButton("medium", menuOpen ? openColors : quietColors)}
      >
        <EllipsisVertical class="w-[18px] h-[18px]" />
      </button>
      {#if menuOpen}
        <div
          role="menu"
          aria-label="More actions"
          data-more-menu
          out:closeMenu
          {@attach popover({
            onClose: () => {
              menuOpen = false;
            },
            onKey: onMenuKey,
            fit: true,
          })}
          {@attach focusFirst}
          class="absolute z-30 right-0 top-full mt-2 w-64 overflow-y-auto p-2 flex flex-col rounded-[20px] bg-surface-raised shadow-pop animate-rise"
        >
          {#each collapsed as action (action)}
            {@const key = threadActionKey(action)}
            <button
              type="button"
              role="menuitem"
              onclick={() => pick(action)}
              class="w-full h-9 shrink-0 flex items-center gap-2.5 px-2.5 rounded-full text-sm text-text hover:bg-surface-hover focus-visible:bg-surface-hover outline-none transition-colors"
            >
              <span class="text-text-secondary">
                {@render face(action, "w-4 h-4")}
              </span>
              <span class="flex-1 text-left truncate">{words(action)}</span>
              {#if keysShown && key !== null}
                <kbd class="font-sans text-xs text-text-secondary">{key}</kbd>
              {/if}
            </button>
          {/each}
        </div>
      {/if}
      {#if mail.snoozeMenuFor === id && collapsed.includes("snooze")}
        <SnoozePopover
          onPick={(epoch, source, key) => mail.snooze(id, epoch, source, key)}
          onClose={mail.closeSnoozeMenu}
        />
      {/if}
      {#if mail.labelMenuFor === id && collapsed.includes("label")}
        <LabelMenu threadIds={[id]} />
      {/if}
    </div>
  {/if}
</div>
