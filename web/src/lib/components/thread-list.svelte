<!-- @component The current mailbox as a card per day, todos among the threads when shown (an email's todo inside its row); an open thread or bundle lifts out of its card while the rest dims, and with a bundle open a tap on the rest closes it. Rows joining or leaving the list grow in or collapse out; pulling down at the top on touch refreshes. -->
<script lang="ts">
  import { bundleRowKey, itemRowKey } from "@inbox/shared/list-state";
  import CheckCheck from "@lucide/svelte/icons/check-check";
  import Focus from "@lucide/svelte/icons/focus";
  import Funnel from "@lucide/svelte/icons/funnel";
  import RotateCw from "@lucide/svelte/icons/rotate-cw";
  import { untrack } from "svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { editorFromSearch } from "../filters/filter-view";
  import { focusMode } from "../focus.svelte";
  import { emptyListState, pagesOnItsOwn } from "../list-status";
  import { getMailState, type MailList } from "../mail-state.svelte";
  import { pressAway } from "../press-away";
  import { searchPlan } from "../search/search-plan";
  import { viewKey } from "../views";
  import { CardSplit } from "./card-split";
  import EmptyInboxArt from "./empty-inbox-art.svelte";
  import LabelGroup from "./label-group.svelte";
  import { ListMotion } from "./list-motion.svelte";
  import NewDraftList from "./new-draft-list.svelte";
  import { closingOpen, unfold } from "./open-motion.svelte";
  import OpenThread from "./open-thread.svelte";
  import PullRefresh from "./pull-refresh.svelte";
  import ThreadRow from "./thread-row.svelte";
  import TodoMore from "./todo-more.svelte";
  import TodoRow from "./todo-row.svelte";

  type ListRow = MailList["rows"][number];
  type ThreadItem = Extract<ListRow, { kind: "thread" }>;
  type TodoItem = Extract<ListRow, { kind: "todo" }>;
  type BundleItem = Extract<ListRow, { kind: "bundle" }>;
  type MoreItem = Extract<ListRow, { kind: "more" }>;
  type CardItem = ThreadItem | TodoItem | BundleItem;

  // What lifts out of a day's card: an open bundle with the threads and todos it shows, or an open thread.
  type Lifted =
    | {
        kind: "bundle";
        key: string;
        bundle: BundleItem;
        shown: (ThreadItem | TodoItem)[];
        more: MoreItem | null;
      }
    | { kind: "open"; key: string; row: ThreadItem };

  type SectionView = {
    key: string;
    title: string;
    doneAll: boolean;
    split: CardSplit<CardItem, Lifted>;
  };

  const mail = getMailState();
  // Rows and closed bundles sit joined in a day's card: shared dividers, rounded only at its ends.
  const JOINED =
    "border-t border-separator first:border-t-0 first:rounded-t-[18px] last:rounded-b-[18px]";
  // A row takes its rounding from the wrapper that joins it to its card.
  const IN_CARD = "rounded-[inherit]";
  const RETRY =
    "hit-target h-9 flex items-center gap-2 px-3.5 rounded-full border border-border bg-surface text-[13px] font-medium hover:bg-surface-hover transition-colors";
  // The heights of a thread's row and a closed bundle's, which an opened one grows out of.
  const ROW_HEIGHT = 68;
  const BUNDLE_HEIGHT = 72;
  const showLabels = $derived(mail.view.kind !== "inbox");
  const plan = $derived(
    mail.view.kind === "search" ? searchPlan(mail.view.query) : null,
  );
  const searchQuery = $derived(plan?.gmailQuery ?? "");
  const resultCount = $derived(
    mail.layout.reduce(
      (count, section) =>
        count +
        section.entries.reduce(
          (inner, entry) =>
            inner +
            (entry.kind === "bundle"
              ? entry.items.filter((item) => item.kind === "thread").length
              : entry.kind === "thread"
                ? 1
                : 0),
          0,
        ),
      0,
    ),
  );
  const focusing = $derived(focusMode.on && mail.view.kind === "inbox");
  const emptyState = $derived(emptyListState(mail.currentList));
  const hub = getComposeHub();
  // Drafts' own rows for new messages not in Gmail yet stand in for its empty text.
  const newDrafts = $derived(
    mail.view.kind === "drafts" ? hub.newDrafts.length : 0,
  );
  const hiddenText = $derived(
    mail.focusHidden === 1
      ? "1 item that isn't pinned"
      : `${mail.focusHidden} items that aren't pinned`,
  );
  let root = $state<HTMLDivElement>();
  let sentinel = $state<HTMLDivElement>();
  let sentinelVisible = $state(false);
  // The page request (view key, query and page token) the last "load more" made, so a load that
  // failed isn't repeated until the sentinel scrolls back into view.
  let loadedFrom: string | null = null;

  const motion = new ListMotion(
    mail,
    () => root,
    () => {
      const keys = new Set<string>();
      for (const row of mail.list.rows) {
        if (row.kind === "section" || row.kind === "more") continue;
        keys.add(row.key);
        if (row.kind === "bundle") {
          for (const item of row.items) keys.add(itemRowKey(item));
        }
      }
      return keys;
    },
    () => sections,
  );
  const { enter: rowIn, leave: rowOut } = motion;
  // The open bundle, and one just closed, which keeps showing its threads while it shrinks closed.
  const closingGroups = closingOpen(() => {
    const labelId = mail.openBundle;
    if (labelId === null) {
      return new Map<string, (ThreadItem | TodoItem)[]>();
    }
    const shown = mail.list.rows.filter(
      (row): row is ThreadItem | TodoItem =>
        (row.kind === "thread" || row.kind === "todo") &&
        row.bundle === labelId,
    );
    return new Map([[bundleRowKey(labelId), shown]]);
  });

  // A day is one card of rows and closed bundles; an open thread or bundle lifts out of it, splitting
  // it. Cards are keyed by their place in the section, so finishing a card's top thread keeps it.
  function sectionViews(rows: readonly ListRow[]): SectionView[] {
    const views: SectionView[] = [];
    let section: SectionView | null = null;
    let bundle: (Lifted & { kind: "bundle" }) | null = null;
    for (const row of rows) {
      if (row.kind === "section") {
        bundle = null;
        section = {
          key: row.section,
          title: row.title,
          doneAll: row.doneAll,
          split: new CardSplit(row.section),
        };
        views.push(section);
      } else if (section === null) {
      } else if (row.kind === "more") {
        if (bundle !== null) bundle.more = row;
      } else if (
        (row.kind === "thread" || row.kind === "todo") &&
        row.bundle !== null
      ) {
        bundle?.shown.push(row);
      } else if (
        row.kind === "bundle" &&
        (row.open || closingGroups.has(row.key))
      ) {
        bundle = {
          kind: "bundle",
          key: row.key,
          bundle: row,
          shown: row.open ? [] : [...(closingGroups.shown(row.key) ?? [])],
          more: null,
        };
        section.split.lift(bundle);
      } else if (row.kind === "thread" && motion.showsOpen(row.id)) {
        bundle = null;
        section.split.lift({ kind: "open", key: `open:${row.id}`, row });
      } else {
        bundle = null;
        section.split.add(row);
      }
    }
    for (const view of views) view.split.done();
    return views;
  }

  const sections = $derived(sectionViews(mail.list.rows));
  // While a thread or bundle is open, everything else dims.
  const spotlight = $derived(mail.openId !== null || mail.openBundle !== null);
  // With a bundle open, a press on the dimmed rest is a tap or click away for the list to settle
  // (closing the open thread, else the bundle) rather than acting, as on Android.
  const awayFromBundle = pressAway({
    away: () => mail.openBundle !== null,
    dispatch: (event) => mail.dispatch(event),
  });

  function retryMore() {
    loadedFrom = null;
    void mail.retryList();
  }

  // A card holding an open menu or a todo being written draws everything, so nothing is clipped.
  function menuWithin(items: CardItem[]): boolean {
    return items.some((item) => {
      if (item.kind === "thread") {
        return item.id === mail.snoozeMenuFor || item.id === mail.labelMenuFor;
      } else if (item.kind === "todo") {
        return (
          item.key === mail.snoozeMenuFor || item.id === mail.todos.editingId
        );
      } else {
        return item.key === mail.snoozeMenuFor;
      }
    });
  }

  $effect(() => {
    const element = sentinel;
    if (!element) {
      sentinelVisible = false;
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.some((entry) => entry.isIntersecting);
        if (visible && !sentinelVisible) loadedFrom = null;
        sentinelVisible = visible;
      },
      { rootMargin: "400px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  });

  // Keeps loading while the sentinel stays in view, as when a page didn't fill the screen.
  $effect(() => {
    const list = mail.currentList;
    if (!pagesOnItsOwn(list, sentinelVisible)) return;
    // Keyed by the request the next page is, not the list's length: a page that adds no new
    // thread (the inbox's query changed and it starts over) still moves on to the next one.
    const from = `${viewKey(mail.view)}:${list.query ?? ""}:${list.nextPageToken}`;
    if (from === loadedFrom) return;
    loadedFrom = from;
    untrack(() => void mail.loadView(mail.view, true));
  });

  const emptyText = $derived.by(() => {
    switch (mail.view.kind) {
      case "inbox":
        return "All done. Nothing in your inbox.";
      case "snoozed":
        return "Nothing snoozed.";
      case "search":
        return "No mail matches that search.";
      default:
        return "Nothing here.";
    }
  });
</script>

<PullRefresh />

<div bind:this={root} class="contents">
  {#key viewKey(mail.view)}
    {#if plan?.todosOnly}
      {#if mail.searchTodoCount === 0}
        <p class="pt-16 text-center text-text-secondary">
          No todos match that search.
        </p>
      {/if}
    {:else if mail.view.kind === "search"}
      <div class="h-9 flex items-end justify-between gap-3 px-1 pb-2">
        <h2 class="text-sm font-semibold text-text-secondary">
          Threads
          {#if mail.currentList.loaded}
            <span class="font-normal"
              >· {resultCount}{mail.currentList.nextPageToken ? "+" : ""}</span
            >
          {/if}
          {#if mail.currentList.local}
            <span class="font-normal">· saved on this device (offline)</span>
          {/if}
          {#if mail.encryptedSearch?.running && mail.view.kind === "search"}
            <span class="font-normal" role="status"
              >· Searching encrypted mail…</span
            >
          {/if}
        </h2>
        {#if searchQuery !== ""}
          <button
            type="button"
            onclick={() => mail.editFilter(editorFromSearch(searchQuery))}
            class="hit-target -mb-1 h-7 flex items-center gap-1.5 px-2 rounded-full text-[13px] font-medium text-text-secondary hover:bg-surface-hover hover:text-text transition-colors"
          >
            <Funnel class="w-4 h-4" aria-hidden="true" />
            Make a filter from this search
          </button>
        {/if}
      </div>
    {/if}

    {#if mail.view.kind === "drafts"}
      <NewDraftList />
    {/if}

    {#if sections.length === 0 &&
      !plan?.todosOnly &&
      !(emptyState === "empty" && newDrafts > 0)}
      <!-- Pages whose threads are all snoozed show nothing; more load by themselves meanwhile. -->
      {#if emptyState === "loading"}
        <div
          class="mt-9 flex flex-col rounded-[18px] bg-surface shadow-card"
          aria-busy="true"
        >
          {#each [0, 1, 2, 3, 4] as placeholder (placeholder)}
            <div
              class="h-[68px] px-5 flex items-center gap-3.5 border-t border-separator first:border-t-0"
            >
              <span
                class="w-9 h-9 rounded-full bg-surface-muted animate-shimmer"
              ></span>
              <span class="flex-1 flex flex-col gap-2">
                <span
                  class="h-3 w-1/4 rounded bg-surface-muted animate-shimmer"
                ></span>
                <span
                  class="h-3 w-3/4 rounded bg-surface-muted animate-shimmer"
                ></span>
              </span>
            </div>
          {/each}
        </div>
      {:else if emptyState === "error"}
        {@const failedFirst = !!mail.currentList.error}
        <div
          class="pt-16 flex flex-col items-center gap-3 text-center"
          role="alert"
        >
          <p class="text-text-secondary">
            {mail.currentList.error ?? mail.currentList.moreError}
          </p>
          <button
            type="button"
            onclick={() =>
              failedFirst ? void mail.loadView(mail.view) : retryMore()}
            class={RETRY}
          >
            <RotateCw class="w-4 h-4" aria-hidden="true" />
            Retry
          </button>
        </div>
      {:else if focusing}
        <div class="pt-16 flex flex-col items-center gap-3 text-center">
          <p class="text-text-secondary">
            {mail.focusHidden > 0
              ? `Nothing pinned right now. Focus is hiding ${hiddenText}.`
              : "Nothing pinned right now."}
          </p>
          <button
            type="button"
            onclick={() => focusMode.set(false)}
            class="hit-target h-9 flex items-center gap-2 px-3.5 rounded-full border border-border bg-surface text-[13px] font-medium hover:bg-surface-hover transition-colors"
          >
            <Focus class="w-4 h-4" aria-hidden="true" />
            Turn off focus
          </button>
        </div>
      {:else if mail.view.kind === "inbox"}
        <div class="pt-12 flex flex-col items-center gap-4 text-center">
          <EmptyInboxArt />
          <p class="text-text-secondary">{emptyText}</p>
        </div>
      {:else}
        <p class="pt-16 text-center text-text-secondary">{emptyText}</p>
      {/if}
    {/if}

    {#each sections as section, sectionIndex (section.key)}
      <div
        {@attach awayFromBundle}
        class={[
          "flex items-end justify-between px-1.5 pb-2.5 transition-opacity duration-200",
          sectionIndex === 0 ? "h-9" : "h-14",
          spotlight && "opacity-45",
        ]}
      >
        <h2 class="text-[13px] font-semibold text-text-secondary">
          {section.title}
        </h2>
        {#if section.doneAll}
          <button
            type="button"
            onclick={() => mail.doneSection(section.key)}
            class="hit-target -mb-1 h-7 flex items-center gap-1.5 px-2.5 rounded-full text-[13px] font-medium text-text-secondary hover:bg-accent-soft hover:text-accent-strong focus-visible:bg-accent-soft focus-visible:text-accent-strong transition-colors"
          >
            <CheckCheck class="w-4 h-4" aria-hidden="true" />
            Done all
          </button>
        {/if}
      </div>
      {#each section.split.chunks as chunk (chunk.key)}
        {#if chunk.kind === "open"}
          <OpenThread
            row={chunk.row.data}
            rowKey={chunk.row.key}
            rowHeight={ROW_HEIGHT}
            closing={motion.thread}
          />
        {:else if chunk.kind === "bundle"}
          <!-- An open bundle that leaves the list (its last thread gone, Gmail having no more) collapses out. -->
          <div
            class="my-4"
            out:rowOut={chunk.key}
            {@attach unfold(
              BUNDLE_HEIGHT,
              () => closingGroups.has(chunk.key),
              false,
            )}
          >
            <LabelGroup
              bundle={chunk.bundle}
              shown={chunk.shown}
              more={chunk.more}
              expanded
              closing={closingGroups.has(chunk.key)}
            />
          </div>
        {:else}
          <div
            {@attach awayFromBundle}
            class={[
              "flex flex-col rounded-[18px] bg-surface shadow-card transition-opacity duration-200 starting:opacity-100",
              spotlight && "opacity-45",
              !menuWithin(chunk.items) &&
                "[content-visibility:auto] [contain-intrinsic-size:auto_300px]",
            ]}
          >
            {#each chunk.items as item (item.key)}
              <div
                class={JOINED}
                data-move-key={item.key}
                in:rowIn={item.key}
                out:rowOut={item.key}
              >
                {#if item.kind === "thread"}
                  <ThreadRow
                    row={item.data}
                    {showLabels}
                    todo={mail.todoOfRow(item.data)}
                    class={IN_CARD}
                  />
                {:else if item.kind === "todo"}
                  <TodoRow
                    todo={mail.todos.get(item.id) ?? item.data}
                    class={IN_CARD}
                  />
                {:else}
                  <LabelGroup
                    bundle={item}
                    shown={[]}
                    more={null}
                    expanded={false}
                    class={IN_CARD}
                  />
                {/if}
              </div>
            {/each}
          </div>
        {/if}
      {/each}
    {/each}

    {#if mail.view.kind === "search" && mail.todosHidden > 0}
      <TodoMore />
    {/if}

    {#if mail.currentList.nextPageToken}
      <div
        bind:this={sentinel}
        class="min-h-16 py-2 flex items-center justify-center gap-3 text-[13px] text-text-secondary"
      >
        {#if mail.currentList.loading}
          Loading…
        {:else if mail.currentList.moreError && sections.length > 0}
          <span role="alert">{mail.currentList.moreError}</span>
          <button type="button" onclick={retryMore} class={RETRY}>
            <RotateCw class="w-4 h-4" aria-hidden="true" />
            Retry
          </button>
        {/if}
      </div>
    {/if}

    {#if focusing && mail.focusHidden > 0 && sections.length > 0}
      <p
        class="pt-6 flex items-center justify-center gap-1 text-[13px] text-text-secondary"
      >
        Focus is hiding {hiddenText}.
        <button
          type="button"
          onclick={() => focusMode.set(false)}
          class="hit-target h-7 px-2 rounded-full font-medium text-accent-strong hover:bg-surface-hover transition-colors"
        >
          Show everything
        </button>
      </p>
    {/if}
  {/key}
</div>
