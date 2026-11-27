<!-- @component The signed-in app: sidebar, search, the current mailbox or the settings, compose, undo, the keyboard, and the address setup after a first sign-in. -->
<script lang="ts">
  import { unsyncedChanges } from "@inbox/shared/account-queue";
  import {
    rowOf,
    rowThreadId,
    rowTodoId,
    todoRowKey,
  } from "@inbox/shared/list-state";
  import { signOutWarning } from "@inbox/shared/sign-out";
  import { isSnoozed } from "@inbox/shared/todos";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import Focus from "@lucide/svelte/icons/focus";
  import ListPlus from "@lucide/svelte/icons/list-plus";
  import Menu from "@lucide/svelte/icons/menu";
  import PenLine from "@lucide/svelte/icons/pen-line";
  import Search from "@lucide/svelte/icons/search";
  import WifiOff from "@lucide/svelte/icons/wifi-off";
  import { flushSync, onMount, tick, untrack } from "svelte";
  import { getAuthState } from "../auth.svelte";
  import { backToPreviousPlace, followLayer } from "../back-stack.svelte";
  import { ComposeHub, setComposeHub } from "../compose/compose-hub.svelte";
  import { clearDeviceData } from "../device-data";
  import { focusMode } from "../focus.svelte";
  import { holdMenu } from "../hold-menu.svelte";
  import {
    hasModifier,
    isCommandChord,
    leavesShortcuts,
    todoChordAllowed,
  } from "../keyboard";
  import { MailState, setMailState } from "../mail-state.svelte";
  import { provideMenuLayer } from "../menu-layer";
  import {
    NotificationState,
    setNotificationState,
  } from "../notifications.svelte";
  import { followPlaces } from "../place-history.svelte";
  import { COMMAND_KEYS, keyLabel, keyTitle } from "../shortcuts";
  import { getThemeState } from "../theme.svelte";
  import { VIEW_TITLES, type View } from "../views";
  import AddressNotice from "./address-notice.svelte";
  import AddressSetup from "./address-setup.svelte";
  import CornerButton from "./corner-button.svelte";
  import UnlockDialog from "./encryption/unlock-dialog.svelte";
  import HoldMenu from "./hold-menu.svelte";
  import LabelSettingsButton from "./label-settings-button.svelte";
  import NavDrawer from "./nav-drawer.svelte";
  import OutboxList from "./outbox-list.svelte";
  import { isAppScroll } from "./page-scroll";
  import { phoneWidth } from "./phone";
  import SearchBar from "./search-bar.svelte";
  import SelectionBar from "./selection-bar.svelte";
  import ShortcutsDialog from "./shortcuts-dialog.svelte";
  import Sidebar from "./sidebar.svelte";
  import SignOutDialog from "./sign-out-dialog.svelte";
  import TagBar from "./tag-bar.svelte";
  import ThreadList from "./thread-list.svelte";
  import TodoList from "./todo-list.svelte";
  import UndoSnackbar from "./undo-snackbar.svelte";
  import ViewName from "./view-name.svelte";

  let { uid }: { uid: string } = $props();

  const SIDEBAR_COLLAPSED_KEY = "inbox:sidebar-collapsed";
  const SETUP_DONE_KEY = "inbox:address-setup-done:";
  // How long after g the second key of a go-to chord counts.
  const CHORD_MS = 1_500;
  const GO_TO: Record<string, View> = {
    i: { kind: "inbox" },
    s: { kind: "snoozed" },
    d: { kind: "done" },
    t: { kind: "sent" },
    k: { kind: "todos" },
  };

  const auth = getAuthState();
  // The parent remounts this component for another account, so the first uid is the only one.
  const mail = setMailState(
    new MailState(
      untrack(() => uid),
      auth.email,
      auth.tokens,
    ),
  );

  const hub = setComposeHub(new ComposeHub(mail));
  const theme = getThemeState();
  followPlaces(mail);
  $effect(() => () => {
    hub.dispose();
    mail.dispose();
  });

  const notifications = setNotificationState(
    new NotificationState(untrack(() => uid)),
  );
  $effect(() => () => notifications.dispose());

  // Lost Gmail access signs out: clear the account as on any sign-out, but keep its unsent work
  // for when it signs in again.
  $effect(() =>
    auth.onForcedSignOut(async () => {
      hub.dispose();
      await notifications
        .forget()
        .catch((error) => console.error("notification sign-out failed", error));
      await mail.parkAndClear().catch(() => {});
    }),
  );

  let collapsed = $state(false);
  let shortcutsOpen = $state(false);
  // The phone side panel.
  let drawerOpen = $state(false);
  // The phone header shows the search field in place of the title.
  let phoneSearching = $state(false);
  const phoneSearch = $derived(phoneSearching || mail.view.kind === "search");
  // What sign-out would lose, while it asks first.
  let signOutQuestion = $state<string | null>(null);
  // Whether the address setup shows: decided once, when the account's settings first load.
  let setupShown = $state<boolean | null>(null);
  let search = $state<SearchBar>();

  // Todos follow the Show todos setting; turning it off leaves their view.
  $effect(() => {
    const on = hub.settings.loaded && hub.settings.showTodos;
    untrack(() => {
      mail.todos.setEnabled(on);
      if (!on && mail.view.kind === "todos") mail.setView({ kind: "inbox" });
    });
  });

  // The account's color theme, also when another device changes it.
  $effect(() => {
    if (hub.settings.loaded) theme.setColorTheme(hub.settings.colorTheme);
  });

  $effect(() => {
    if (!hub.settings.loaded) return;
    untrack(() => {
      if (setupShown !== null) return;
      setupShown =
        hub.settings.address === null &&
        localStorage.getItem(SETUP_DONE_KEY + uid) !== "1";
    });
  });

  // Controls, menus and the thread itself keep their clicks; a selection being made isn't a click away.
  const KEEPS_CLICK =
    "[data-thread-view], [data-bundle], button, a, input, textarea, select, label, [role=button], [role=menu], [role=dialog], [contenteditable=true]";

  let content = $state<HTMLDivElement>();
  let scrolled = $state(false);
  // The floating Compose and New todo buttons step aside while the list scrolls down.
  let buttonsHidden = $state(false);
  let lastScrollY = 0;
  // On a phone the selection bar sits at the bottom of the screen, in the thumb's reach.
  const selectionAtBottom = $derived(
    mail.selected.length > 0 && phoneWidth.current,
  );
  const selectionInHeader = $derived(
    mail.selected.length > 0 && !phoneWidth.current,
  );
  // The mailbox shows, not the address setup or the settings.
  const inMailbox = $derived(!setupShown && mail.view.kind !== "settings");
  const buttonsShown = $derived(
    mail.openId === null && mail.selected.length === 0,
  );
  // On a phone every message being written is the full-screen composer, a reply too, drawn here
  // whether open or parked, so parking and opening it again keep it as it was.
  const shownComposer = $derived(
    phoneWidth.current
      ? (hub.floating ?? hub.inline ?? hub.parked)
      : hub.floating,
  );
  // Wider than a phone there is no strip: a parked message opens again.
  $effect(() => {
    if (!phoneWidth.current && hub.parked !== null) untrack(hub.unpark);
  });

  function onScroll() {
    const y = window.scrollY;
    scrolled = y > 4;
    if (isAppScroll(y)) {
      lastScrollY = y;
      return;
    }
    if (y <= 0 || y < lastScrollY - 4) buttonsHidden = false;
    else if (y > lastScrollY + 4) buttonsHidden = true;
    if (Math.abs(y - lastScrollY) > 4 || y <= 0) lastScrollY = y;
  }

  async function openPhoneSearch() {
    phoneSearching = true;
    await tick();
    search?.focus();
  }

  function closePhoneSearch() {
    phoneSearching = false;
    leaveSearch();
  }

  // Back to the view search was started from: the entry before, which a Back to a search can't
  // know otherwise.
  function leaveSearch() {
    if (mail.view.kind !== "search") return;
    if (!backToPreviousPlace()) mail.closeSearch();
  }

  // Layers Back closes, the one opened last first; the open thread is a place of its own, and
  // menus, sheets and dialogs add their own entries.
  followLayer(
    () => mail.list.selection.length > 0,
    () => mail.dispatch({ type: "clearSelection" }),
  );
  followLayer(
    () => mail.list.bundle !== null,
    () => mail.dispatch({ type: "closeBundle" }),
  );
  followLayer(
    () => mail.todos.editingId !== null,
    () => {
      // Leaving the editor saves its text, as a tap away does.
      const active = document.activeElement;
      if (active instanceof HTMLElement && active.closest("[data-todo-id]")) {
        active.blur();
      }
      mail.todos.setEditing(null);
    },
  );
  followLayer(
    () => drawerOpen,
    () => (drawerOpen = false),
  );
  followLayer(
    () => phoneSearching,
    () => {
      phoneSearching = false;
      if (document.activeElement instanceof HTMLElement) {
        document.activeElement.blur();
      }
    },
  );
  const viewLabel = $derived.by(() => {
    const view = mail.view;
    if (view.kind !== "label") return null;
    return mail.userLabels.find((label) => label.id === view.labelId) ?? null;
  });

  function closeOnClickAway(event: MouseEvent) {
    if (!(event.target instanceof Element)) return;
    if (!content?.contains(event.target)) return;
    if (event.target.closest(KEEPS_CLICK)) return;
    if (window.getSelection()?.isCollapsed === false) return;
    mail.dispatch({ type: "dismiss" });
  }

  $effect(() => provideMenuLayer(mail.menus));

  function closeSetup() {
    localStorage.setItem(SETUP_DONE_KEY + uid, "1");
    setupShown = false;
  }
  let chordStartedAt = 0;

  const title = $derived.by(() => {
    const view = mail.view;
    if (view.kind === "label") {
      return (
        mail.userLabels.find((label) => label.id === view.labelId)?.name ??
        "Label"
      );
    }
    if (view.kind === "search") return `Search: ${view.query}`;
    if (view.kind === "settings") return "Settings";
    return VIEW_TITLES[view.kind];
  });

  onMount(() => {
    if (localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "1") collapsed = true;
    if (!("serviceWorker" in navigator)) return;
    function onWorkerMessage(event: MessageEvent) {
      const data = event.data as {
        type?: string;
        threadId?: string;
        doneThreadId?: string;
      } | null;
      if (data?.type === "open-thread" && data.threadId) {
        void mail.openFromNotification(data.threadId);
      } else if (data?.type === "mail-changed") {
        if (data.doneThreadId) mail.noteDoneElsewhere(data.doneThreadId);
        // History says what changed; a refresh already running takes this one after it.
        void mail.refresh(false);
      }
    }
    navigator.serviceWorker.addEventListener("message", onWorkerMessage);
    return () =>
      navigator.serviceWorker.removeEventListener("message", onWorkerMessage);
  });

  function toggleCollapsed() {
    collapsed = !collapsed;
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? "1" : "0");
  }

  // Asks first while changes or drafts haven't reached Gmail, as sign-out deletes them. A discard
  // still open to undo counts as a draft not synced, and stays open until sign-out is confirmed.
  function askSignOut() {
    const warning = signOutWarning(
      unsyncedChanges(mail.queue, mail.accountQueue),
      hub.unsyncedDrafts,
    );
    if (warning === null) void signOut();
    else signOutQuestion = warning;
  }

  // Sign-out leaves nothing about the account in this browser; the page starts over.
  async function signOut() {
    await hub.finishDiscards();
    await notifications
      .forget()
      .catch((error) => console.error("notification sign-out failed", error));
    await mail.clearDevice().catch(() => {});
    await auth.signOut();
    await clearDeviceData("signOut");
    location.reload();
  }

  // Throws when the server couldn't delete the account; then nothing on the device changes.
  async function deleteAccount() {
    await auth.deleteAccount();
    await notifications.forget().catch(() => {});
    await mail.clearDevice().catch(() => {});
    await auth.signOut().catch(() => {});
    await clearDeviceData("everything");
    location.reload();
  }

  function answer(mode: "reply" | "replyAll" | "forward"): boolean {
    const target = mail.openId ?? rowThreadId(mail.focusKey);
    if (target === null) return false;
    hub.answerThread(target, mode);
    return true;
  }

  // Escape: one level back, if there is one to go back from.
  function back(): boolean {
    const list = mail.list;
    if (
      list.open === null &&
      list.selection.length === 0 &&
      list.bundle === null &&
      list.focus === null
    ) {
      return false;
    }
    mail.dispatch({ type: "back" });
    return true;
  }

  // ⌘D done, ⌘S snooze or wake, ⌘K pin and ⌘⌫ delete, as in latr: on the todo being written, else
  // the focused one, else the one under the mouse; never while typing in another field.
  function onTodoChord(event: KeyboardEvent) {
    if (!mail.todos.enabled || !isCommandChord(event) || event.shiftKey) return;
    if (event.altKey || mail.openId !== null) return;
    const key = event.key.toLowerCase();
    if (!["d", "s", "k", "backspace"].includes(key)) return;
    const hovered = document
      .querySelector("[data-todo-id]:hover")
      ?.getAttribute("data-todo-id");
    const id =
      key === "backspace"
        ? hovered
        : (mail.todos.editingId ?? rowTodoId(mail.focusKey) ?? hovered);
    const todo = id ? mail.todos.get(id) : undefined;
    if (!todo || !todoChordAllowed(event.target, todo.id)) return;
    event.preventDefault();
    event.stopPropagation();
    switch (key) {
      case "d":
        mail.toggleTodoDone(todo.id);
        break;
      case "s":
        if (isSnoozed(todo, Date.now())) {
          mail.wakeTodo(todo.id);
        } else {
          mail.openSnoozeMenu(todoRowKey(todo.id));
        }
        break;
      case "k":
        mail.toggleTodoPin(todo.id);
        break;
      default:
        mail.deleteTodos([todo.id]);
    }
  }

  function onKey(event: KeyboardEvent) {
    if (shortcutsOpen || setupShown || event.defaultPrevented) return;
    if (drawerOpen || signOutQuestion !== null) return;
    if (mail.encryption.unlockRequested) return;
    if (leavesShortcuts(event.target, event.key) || hasModifier(event)) return;
    const key = event.key;
    // With shortcuts off, only Escape still works (undo is handled apart).
    if (!hub.settings.shortcuts && key !== "Escape") return;
    if (Date.now() - chordStartedAt < CHORD_MS) {
      chordStartedAt = 0;
      const view = GO_TO[key];
      if (view && (view.kind !== "todos" || mail.todos.enabled)) {
        event.preventDefault();
        mail.setView(view);
        return;
      }
    }
    const action = COMMAND_KEYS[key];
    if (action !== undefined) {
      if (mail.dispatch({ type: "command", action }).effect !== null) {
        event.preventDefault();
      }
      return;
    }
    let handled = true;
    switch (key) {
      case "j":
      case "ArrowDown":
        mail.dispatch({ type: "next" });
        break;
      case "k":
      case "ArrowUp":
        mail.dispatch({ type: "previous" });
        break;
      case "o":
      case "Enter":
        // On the open thread, Enter is the thread's own.
        if (mail.focusKey === null || mail.focusKey === mail.openId) {
          handled = false;
        } else mail.dispatch({ type: "enter" });
        break;
      case "Escape":
        handled = back();
        break;
      case "x": {
        const row = rowOf(mail.list, mail.focusKey);
        if (row?.kind === "thread" || row?.kind === "todo") {
          mail.dispatch({ type: "select" });
        } else handled = false;
        break;
      }
      case "i":
        if (mail.view.kind === "inbox") focusMode.toggle();
        else handled = false;
        break;
      case "?":
        shortcutsOpen = true;
        break;
      case "c":
        hub.compose();
        break;
      case "n":
        if (mail.todos.enabled) mail.newTodo();
        else handled = false;
        break;
      case "r":
        handled = answer("reply");
        break;
      case "a":
        handled = answer("replyAll");
        break;
      case "f":
        handled = answer("forward");
        break;
      case "/":
        if (search) search.focus();
        else handled = false;
        break;
      case "[":
        toggleCollapsed();
        break;
      case "g":
        chordStartedAt = Date.now();
        break;
      default:
        handled = false;
    }
    if (handled) event.preventDefault();
  }

  // Captured so undo wins over the browser's own undo in a text field.
  function onUndoKey(event: KeyboardEvent) {
    if (!isCommandChord(event) || event.shiftKey) return;
    if (event.key.toLowerCase() !== "z") return;
    const sendUndo = hub.pill?.kind === "undo";
    const discardUndo = hub.pill?.kind === "discarded";
    if (!sendUndo && !discardUndo && !mail.lastUndo) return;
    event.preventDefault();
    event.stopPropagation();
    if (sendUndo) void hub.undoSend();
    else if (discardUndo) void hub.undoDiscard();
    else mail.undo();
  }
</script>

<svelte:head>
  <title>{title === "Inbox" ? title : `${title} · Inbox`}</title>
</svelte:head>

<svelte:window
  onkeydown={onKey}
  onkeydowncapture={(event) => {
    // An open hold menu takes every key; its own listener comes later, so this one waits.
    if (holdMenu.current !== null) return;
    onUndoKey(event);
    if (!event.defaultPrevented) onTodoChord(event);
  }}
  onclick={closeOnClickAway}
  onscroll={onScroll}
/>

<UnlockDialog bind:open={mail.encryption.unlockRequested} />

{#if setupShown}
  <AddressSetup onClose={closeSetup} />
{:else if mail.view.kind === "settings"}
  {@const section = mail.view.section}
  {#await import(
    "./settings/settings-page.svelte"
  ) then { default: SettingsPage }}
    <SettingsPage {section} onDeleteAccount={deleteAccount} />
  {/await}
{:else}
  <div class="min-h-dvh">
    <Sidebar
      {collapsed}
      onToggleCollapsed={toggleCollapsed}
      onSignOut={askSignOut}
      onSettings={() => mail.setView({ kind: "settings", section: "general" })}
    />
    <NavDrawer
      open={drawerOpen}
      onClose={() => (drawerOpen = false)}
      onSignOut={askSignOut}
      onSettings={() => mail.setView({ kind: "settings", section: "general" })}
    />
    <main
      style:--sidebar-reserved={collapsed ? "4rem" : "15rem"}
      class="relative flex flex-col min-h-dvh min-w-0 sm:pl-[var(--sidebar-reserved)] transition-[padding-left] duration-200 ease-out"
    >
      <header
        data-app-header
        class={[
          "sticky top-0 z-10 h-[calc(4rem+env(safe-area-inset-top))] pt-safe shrink-0 flex items-center px-safe-2 sm:px-12 bg-bg/80 backdrop-blur-xl backdrop-saturate-150 transition-shadow duration-200",
          scrolled && "shadow-[0_6px_18px_-12px_rgb(0_0_0/0.35)]",
        ]}
      >
        {#if selectionInHeader}
          <div class="w-full max-w-[800px] mx-auto flex items-center">
            <SelectionBar />
          </div>
        {/if}
        <div
          class={[
            "w-full max-w-[800px] mx-auto flex items-center gap-1 sm:gap-3",
            selectionInHeader && "hidden",
          ]}
        >
          {#if phoneSearch}
            <button
              type="button"
              aria-label="Close search"
              onclick={closePhoneSearch}
              class="sm:hidden w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text hover:bg-surface-hover"
            >
              <ArrowLeft class="w-5 h-5" aria-hidden="true" />
            </button>
          {:else}
            <button
              type="button"
              aria-label="Menu"
              aria-haspopup="dialog"
              aria-expanded={drawerOpen}
              onclick={() => (drawerOpen = true)}
              class="sm:hidden w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text hover:bg-surface-hover"
            >
              <Menu class="w-5 h-5" aria-hidden="true" />
            </button>
            <div class="sm:hidden flex-1 min-w-0 flex">
              <ViewName {title} />
            </div>
          {/if}
          <div
            class={[
              "flex-1 min-w-0 sm:flex sm:pl-2",
              phoneSearch ? "flex pr-2" : "hidden",
            ]}
          >
            <SearchBar
              bind:this={search}
              onSearch={() => (phoneSearching = false)}
            />
          </div>
          {#if !phoneSearch}
            <button
              type="button"
              aria-label="Search"
              title={keyTitle("Search", "/", hub.settings.shortcuts)}
              onclick={openPhoneSearch}
              class="sm:hidden w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text hover:bg-surface-hover"
            >
              <Search class="w-5 h-5" aria-hidden="true" />
            </button>
          {/if}
          {#if viewLabel && !phoneSearch}
            <LabelSettingsButton label={viewLabel} name={viewLabel.name} />
          {/if}
          {#if mail.view.kind === "inbox" && !phoneSearch}
            <button
              type="button"
              aria-pressed={focusMode.on}
              aria-label={keyLabel(
                "Focus: show only pinned mail and todos",
                "i",
                hub.settings.shortcuts,
              )}
              title={keyTitle(
                focusMode.on
                  ? "Focus is on: showing only pinned mail and todos"
                  : "Focus: show only pinned mail and todos",
                "i",
                hub.settings.shortcuts,
              )}
              onclick={focusMode.toggle}
              aria-keyshortcuts={hub.settings.shortcuts ? "i" : undefined}
              class={[
                "hit-target h-9 shrink-0 flex items-center gap-1.5 px-3 rounded-full text-[13px] font-medium transition-colors",
                focusMode.on
                  ? "bg-accent-soft text-accent-strong hover:brightness-95"
                  : "text-text-secondary hover:bg-surface-hover hover:text-text",
              ]}
            >
              <Focus class="w-4 h-4" aria-hidden="true" />
              {focusMode.on ? "Focus on" : "Focus"}
            </button>
          {/if}
          {#if mail.offline}
            {@const waiting = mail.queue.length}
            <span
              role="status"
              class={[
                "h-8 flex items-center gap-1.5 px-3 mr-2 sm:mr-0 rounded-full bg-surface-muted text-[13px] text-text-secondary shrink-0",
                phoneSearch && "max-sm:hidden",
              ]}
              title={waiting > 0
                ? `Offline. ${waiting} change${waiting === 1 ? "" : "s"} waiting to send`
                : "Offline. Changes are sent when you're back online"}
            >
              <WifiOff class="w-4 h-4" aria-hidden="true" />
              <span class={waiting > 0 ? "hidden sm:inline" : ""}>Offline</span>
              {#if waiting > 0}
                <span class="tabular-nums">
                  <span class="hidden sm:inline">·</span>
                  {waiting}
                  <span class="hidden sm:inline"
                    >change{waiting === 1 ? "" : "s"}</span
                  >
                  waiting<span class="hidden sm:inline">&nbsp;to send</span>
                </span>
              {/if}
            </span>
          {/if}
        </div>
      </header>
      <div
        class="flex-1 px-safe-4 sm:px-12 pt-4 pb-safe-44 sm:pb-32"
        bind:this={content}
      >
        <div class="w-full max-w-[800px] mx-auto">
          {#if mail.view.kind === "outbox"}
            <OutboxList />
          {:else if mail.view.kind === "todos"}
            <TodoList />
          {:else}
            {#if mail.view.kind === "inbox"}
              <AddressNotice />
            {/if}
            <ThreadList />
          {/if}
        </div>
      </div>
      {#if selectionAtBottom}
        <div
          class="fixed z-20 inset-x-0 bottom-0 h-[calc(3.5rem+var(--safe-bottom))] pb-safe px-safe-2 flex items-center bg-surface border-t border-border shadow-[0_-6px_18px_-12px_rgb(0_0_0/0.35)]"
        >
          <SelectionBar />
        </div>
      {/if}
      <TagBar />
      {#if buttonsShown}
        <CornerButton
          label="Compose"
          title={keyTitle("Compose", "c", hub.settings.shortcuts)}
          icon={PenLine}
          bottom="calc(1rem + var(--safe-bottom))"
          hidden={buttonsHidden}
          onTap={() => hub.compose()}
        >
          {#snippet above()}
            {#if mail.todos.enabled}
              <button
                type="button"
                aria-label="New todo"
                title={keyTitle("New todo", "n", hub.settings.shortcuts)}
                onclick={() => {
                  mail.newTodo();
                  // Its editor mounts and takes focus inside the tap, or iOS won't raise the keyboard.
                  flushSync();
                }}
                class="w-12 h-12 mx-1 flex items-center justify-center"
              >
                <span
                  class="w-10 h-10 flex items-center justify-center rounded-full bg-surface text-accent-strong shadow-pop"
                >
                  <ListPlus class="w-5 h-5" aria-hidden="true" />
                </span>
              </button>
            {/if}
          {/snippet}
        </CornerButton>
      {/if}
    </main>
  </div>
{/if}

<!-- Over the settings too, where a message can still be sent or a change undone. -->
<UndoSnackbar
  --sidebar-reserved={setupShown
    ? "0px"
    : collapsed && mail.view.kind !== "settings"
      ? "4rem"
      : "15rem"}
  besideCompose={inMailbox && buttonsShown && !buttonsHidden}
  above={!inMailbox
    ? null
    : selectionAtBottom
      ? "selectionCorner"
      : mail.openId !== null
        ? "corner"
        : null}
/>
<HoldMenu />

{#if signOutQuestion !== null}
  <SignOutDialog
    warning={signOutQuestion}
    onCancel={() => (signOutQuestion = null)}
    onSignOut={() => {
      signOutQuestion = null;
      void signOut();
    }}
  />
{/if}

{#if shownComposer}
  {@const shown = shownComposer}
  {#key shown.localId}
    {#await import("./composer.svelte") then { default: Composer }}
      <Composer
        composer={shown}
        variant="floating"
        parked={shown === hub.parked}
      />
    {/await}
  {/key}
{/if}
<ShortcutsDialog bind:open={shortcutsOpen} />
