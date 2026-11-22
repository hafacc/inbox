// How a mail list is used: which rows it shows in what order, keyboard focus, the open thread, the
// open bundle and its paging, selection, the scroll anchor, rows on their way out, and undo. One
// pure transition (`listStep`) takes every event; web and Android keep no focus or open state of
// their own. Android ports it to :core (ListState.kt); both run shared/testdata/list-state-fixtures.json.
// The rules it keeps are in docs/design.md, "The list".
import { selectRange, toggleSelected } from "./selection";

/** Threads an open bundle shows before "Show N more". */
export const GROUP_PREVIEW = 5;

/** A thread as a layout gives it: its id and what the app draws it from. */
export type LayoutThread<Thread> = {
  /** Gmail thread id */
  id: string;
  /** the app's own data for the row */
  data: Thread;
};

/** A todo as a layout gives it: its id and what the app draws it from. */
export type LayoutTodo<Todo> = {
  /** todo id */
  id: string;
  /** the app's own data for the row */
  data: Todo;
};

/** One item of a bundle: a thread or a todo. */
export type LayoutItem<Thread, Todo> =
  | ({ kind: "thread" } & LayoutThread<Thread>)
  | ({ kind: "todo" } & LayoutTodo<Todo>);

/** One entry of a layout section: a thread, a todo, or a bundle of one label's threads and todos. */
export type LayoutEntry<Thread, Todo> =
  | LayoutItem<Thread, Todo>
  | {
      kind: "bundle";
      labelId: string;
      /** its threads and todos, in the order it shows them */
      items: readonly LayoutItem<Thread, Todo>[];
      /** whether Gmail has more of the label's inbox threads than were fetched */
      more: boolean;
      /** whether a next page is on its way */
      loading: boolean;
    };

/** One section of a layout: what the app's data says the list holds, in order. */
export type LayoutSection<Thread, Todo> = {
  /** stable section key */
  key: string;
  /** heading */
  title: string;
  /** whether the heading offers "Done all" */
  doneAll: boolean;
  /** entries in order */
  entries: readonly LayoutEntry<Thread, Todo>[];
};

/** A row of the list, in visual order. */
export type ListRow<Thread, Todo> =
  | {
      kind: "section";
      key: string;
      section: string;
      title: string;
      doneAll: boolean;
    }
  | {
      kind: "thread";
      key: string;
      id: string;
      section: string;
      /** the open bundle it shows in, or null for a row of its own */
      bundle: string | null;
      data: Thread;
    }
  | {
      kind: "todo";
      key: string;
      id: string;
      section: string;
      /** the open bundle it shows in, or null for a row of its own */
      bundle: string | null;
      data: Todo;
    }
  | {
      kind: "bundle";
      key: string;
      labelId: string;
      section: string;
      /** all its threads and todos, shown or not, in order */
      items: readonly LayoutItem<Thread, Todo>[];
      /** all its threads, shown or not */
      threads: readonly LayoutThread<Thread>[];
      /** all its todos, shown or not */
      todos: readonly LayoutTodo<Todo>[];
      open: boolean;
      more: boolean;
      loading: boolean;
    }
  | {
      kind: "more";
      key: string;
      labelId: string;
      section: string;
      /** loaded threads and todos the preview leaves out */
      hidden: number;
      more: boolean;
      loading: boolean;
    };

/** The open bundle. */
export type OpenBundle<Thread> = {
  labelId: string;
  /** whether it shows every loaded thread, not just the first {@link GROUP_PREVIEW} */
  showAll: boolean;
  /** while a page the keyboard asked for loads: the row focus sat on, which new rows follow */
  awaiting: string | null;
  /** its threads as last shown */
  threads: readonly LayoutThread<Thread>[];
};

/** Who moved focus last: the keyboard (it scrolls into view and leaving a bundle closes it) or a pointer. */
export type FocusSource = "keyboard" | "pointer";

/** Rows an action acts on. */
export type ListTarget = {
  /** the open thread, the selection, or the focused row */
  from: "thread" | "selection" | "row";
  /** thread ids */
  threads: readonly string[];
  /** todo ids */
  todos: readonly string[];
  /** the label of a focused bundle, whose threads `threads` lists and whose todos `todos` does */
  bundle: string | null;
};

/** Work the app does after a step. */
export type ListEffect =
  | { kind: "loadMore"; labelId: string }
  | { kind: "act"; action: string; target: ListTarget }
  | { kind: "edit"; todoId: string };

/** What undo puts back: the rows an action took away and where focus was. */
export type ListUndo = {
  keys: readonly string[];
  focus: string | null;
};

/** The whole list as one value. */
export type ListState<Thread, Todo> = {
  /** the layout last given */
  layout: readonly LayoutSection<Thread, Todo>[];
  /** rows in visual order */
  rows: readonly ListRow<Thread, Todo>[];
  /** key of the focused row */
  focus: string | null;
  /** who moved focus last */
  focusBy: FocusSource | null;
  /** bumped whenever focus should scroll into view */
  reveal: number;
  /** a row focus goes to once a layout lists it (an opened thread or an undone row) */
  pendingFocus: string | null;
  /** the open thread */
  open: string | null;
  /** the open bundle */
  bundle: OpenBundle<Thread> | null;
  /** the open menu or dropdown, the layer above the open thread and bundle */
  menu: string | null;
  /** selected row keys, in the order picked */
  selection: readonly string[];
  /** the last row picked one at a time, where a range starts */
  selectAnchor: string | null;
  /** the row the viewport is pinned to, and its offset from the viewport top in pixels */
  anchor: { key: string; offset: number } | null;
  /** keys an action took out, hidden until the layout drops them too */
  removing: ReadonlySet<string>;
  /** what undo puts back */
  undo: ListUndo | null;
  /** work the last step asks of the app */
  effect: ListEffect | null;
};

/** Where an event came from: keys, or a pointer or touch. */
export type ListInput = FocusSource;

/** Everything that changes a list. */
export type ListEvent<Thread, Todo> =
  /** the app's data changed: a refetch, new mail, a bundle's page, pinned or focus mode, todos from another device */
  | { type: "layout"; sections: readonly LayoutSection<Thread, Todo>[] }
  /** j or ↓ */
  | { type: "next" }
  /** k or ↑ */
  | { type: "previous" }
  /** Enter or o on the focused row */
  | { type: "enter" }
  /** Escape: closes the top layer, else steps back one level */
  | { type: "back" }
  /** a click or tap outside the top layer (the open menu, else thread, else bundle): closes that layer */
  | { type: "dismiss" }
  /** a menu or dropdown opened */
  | { type: "openMenu"; id: string }
  /** a menu or dropdown closed */
  | { type: "closeMenu"; id: string }
  /** x: select or unselect the focused row */
  | { type: "select" }
  /** an action key: resolves its target into an `act` effect */
  | { type: "command"; action: string }
  /** a row took focus (a click, a tap, Tab) */
  | { type: "focus"; key: string; by: ListInput }
  /** a thread or bundle row was clicked or tapped open (a bundle toggles); with a layer open, a tap outside it closes it */
  | { type: "tap"; key: string }
  /** a row the user just made (a new todo): focus goes to it and it scrolls into view, now or once a layout lists it */
  | { type: "reveal"; key: string }
  /** open a thread from outside the list: a notification, a link */
  | { type: "openThread"; id: string }
  /** close the open thread */
  | { type: "closeThread" }
  /** close the open bundle (a tap outside it, its header's close) */
  | { type: "closeBundle" }
  /** show every loaded thread of the open bundle */
  | { type: "showAll" }
  /** select or unselect one row, or (`range`) every row from the last one picked */
  | { type: "pick"; key: string; range: boolean }
  /** select every selectable row, or none when all are */
  | { type: "pickAll" }
  /** drop the selection */
  | { type: "clearSelection" }
  /** an action was applied: `removes` are the rows it takes out of this list */
  | {
      type: "act";
      removes: readonly string[];
      from: "row" | "thread" | "selection" | "notification";
      undoable: boolean;
      clearSelection: boolean;
    }
  /** undo the last action */
  | { type: "undo" }
  /** the undo offer ran out */
  | { type: "undoExpired" }
  /** a write failed and its rows come back */
  | { type: "restore"; keys: readonly string[] }
  /** where the viewport sits, measured just before a layout */
  | { type: "anchor"; key: string; offset: number }
  /** another view: nothing carries over but undo */
  | { type: "reset" };

const TODO_PREFIX = "todo:";
const BUNDLE_PREFIX = "bundle:";
const SECTION_PREFIX = "section:";
const MORE_PREFIX = "more:";

/** A thread's row key: its id. */
export function threadRowKey(id: string): string {
  return id;
}

/** A todo's row key. */
export function todoRowKey(id: string): string {
  return TODO_PREFIX + id;
}

/** A bundle's row key. */
export function bundleRowKey(labelId: string): string {
  return BUNDLE_PREFIX + labelId;
}

/** A bundle item's row key: its thread's or its todo's. */
export function itemRowKey<Thread, Todo>(
  item: LayoutItem<Thread, Todo>,
): string {
  return item.kind === "thread" ? threadRowKey(item.id) : todoRowKey(item.id);
}

/** A section heading's row key. */
export function sectionRowKey(key: string): string {
  return SECTION_PREFIX + key;
}

/** The todo a row key names, or null. */
export function rowTodoId(key: string | null): string | null {
  return key?.startsWith(TODO_PREFIX) ? key.slice(TODO_PREFIX.length) : null;
}

/** The thread a row key names, or null. */
export function rowThreadId(key: string | null): string | null {
  if (key === null) return null;
  for (const prefix of [
    TODO_PREFIX,
    BUNDLE_PREFIX,
    SECTION_PREFIX,
    MORE_PREFIX,
  ]) {
    if (key.startsWith(prefix)) return null;
  }
  return key;
}

/** Whether a row takes focus: threads, todos and bundles do, headings and "more" don't. */
export function isFocusable<Thread, Todo>(row: ListRow<Thread, Todo>): boolean {
  return row.kind === "thread" || row.kind === "todo" || row.kind === "bundle";
}

/** Whether a row can be selected: threads and todos. */
export function isSelectable<Thread, Todo>(
  row: ListRow<Thread, Todo>,
): boolean {
  return row.kind === "thread" || row.kind === "todo";
}

/** An empty list. */
export function emptyListState<Thread, Todo>(): ListState<Thread, Todo> {
  return {
    layout: [],
    rows: [],
    focus: null,
    focusBy: null,
    reveal: 0,
    pendingFocus: null,
    open: null,
    bundle: null,
    menu: null,
    selection: [],
    selectAnchor: null,
    anchor: null,
    removing: new Set(),
    undo: null,
    effect: null,
  };
}

/** The keys of the rows that take focus, in visual order. */
export function focusableKeys<Thread, Todo>(
  state: ListState<Thread, Todo>,
): string[] {
  return state.rows.filter(isFocusable).map((row) => row.key);
}

/** The keys of the rows that can be selected, in visual order. */
export function selectableKeys<Thread, Todo>(
  state: ListState<Thread, Todo>,
): string[] {
  return state.rows.filter(isSelectable).map((row) => row.key);
}

/** The row with `key`, or null. */
export function rowOf<Thread, Todo>(
  state: ListState<Thread, Todo>,
  key: string | null,
): ListRow<Thread, Todo> | null {
  if (key === null) return null;
  return state.rows.find((row) => row.key === key) ?? null;
}

/** The selected thread ids, in the order picked. */
export function selectedThreads<Thread, Todo>(
  state: ListState<Thread, Todo>,
): string[] {
  return state.selection.flatMap((key) => {
    const id = rowThreadId(key);
    return id === null ? [] : [id];
  });
}

/** The selected todo ids, in the order picked. */
export function selectedTodos<Thread, Todo>(
  state: ListState<Thread, Todo>,
): string[] {
  return state.selection.flatMap((key) => {
    const id = rowTodoId(key);
    return id === null ? [] : [id];
  });
}

/** What an action key acts on: the open thread, else the selection, else the focused row. */
export function listTarget<Thread, Todo>(
  state: ListState<Thread, Todo>,
): ListTarget | null {
  if (state.open !== null) {
    return { from: "thread", threads: [state.open], todos: [], bundle: null };
  }
  if (state.selection.length > 0) {
    return {
      from: "selection",
      threads: selectedThreads(state),
      todos: selectedTodos(state),
      bundle: null,
    };
  }
  const row = rowOf(state, state.focus);
  if (row === null) return null;
  switch (row.kind) {
    case "thread":
      return { from: "row", threads: [row.id], todos: [], bundle: null };
    case "todo":
      return { from: "row", threads: [], todos: [row.id], bundle: null };
    case "bundle":
      return {
        from: "row",
        threads: row.threads.map((thread) => thread.id),
        todos: row.todos.map((todo) => todo.id),
        bundle: row.labelId,
      };
    default:
      return null;
  }
}

/** The threads either side of an open one, which a sideways swipe on it opens. */
export type OpenNeighbors = {
  /** the thread above it, or null at the top */
  previous: string | null;
  /** the thread below it, or null at the end */
  next: string | null;
};

/**
 * The threads before and after the open thread, in visual order: inside the open bundle when it
 * shows there, else among the list's own thread rows (todos, bundles and headings are passed
 * over). Both are null with no thread open or its row not listed.
 */
export function openNeighbors<Thread, Todo>(
  state: ListState<Thread, Todo>,
): OpenNeighbors {
  const open =
    state.open === null ? null : rowOf(state, threadRowKey(state.open));
  if (open?.kind !== "thread") return { previous: null, next: null };
  const peers = state.rows.flatMap((row) =>
    row.kind === "thread" && row.bundle === open.bundle ? [row.id] : [],
  );
  const at = peers.indexOf(open.id);
  return { previous: peers[at - 1] ?? null, next: peers[at + 1] ?? null };
}

/** Where the open thread sits in the open bundle: its label and how many of its loaded threads follow it. */
export type OpenBundleTail = {
  /** the bundle's label */
  labelId: string;
  /** its loaded threads after the open one */
  after: number;
};

/**
 * How near the open thread is to the end of the bundle it shows in, so the next page is fetched
 * before a swipe runs out of threads; null with no thread open in a bundle.
 */
export function openBundleTail<Thread, Todo>(
  state: ListState<Thread, Todo>,
): OpenBundleTail | null {
  const open =
    state.open === null ? null : rowOf(state, threadRowKey(state.open));
  if (open?.kind !== "thread" || open.bundle === null) return null;
  const header = state.rows.find(
    (row) => row.kind === "bundle" && row.labelId === open.bundle,
  );
  if (header?.kind !== "bundle") return null;
  const at = header.threads.findIndex((thread) => thread.id === open.id);
  return { labelId: open.bundle, after: header.threads.length - at - 1 };
}

// The first key after `key` in `order` that `alive` keeps, else the last one before it.
function survivor(
  order: readonly string[],
  key: string,
  alive: (key: string) => boolean,
): string | null {
  const index = order.indexOf(key);
  if (index === -1) return null;
  for (let at = index + 1; at < order.length; at++) {
    const candidate = order[at]!;
    if (alive(candidate)) return candidate;
  }
  for (let at = index - 1; at >= 0; at--) {
    const candidate = order[at]!;
    if (alive(candidate)) return candidate;
  }
  return null;
}

// Every key the layout holds anywhere, rows or not: threads (in bundles too), todos, bundles.
function layoutKeys<Thread, Todo>(
  layout: readonly LayoutSection<Thread, Todo>[],
): Set<string> {
  const keys = new Set<string>();
  for (const section of layout) {
    for (const entry of section.entries) {
      if (entry.kind === "thread") keys.add(threadRowKey(entry.id));
      else if (entry.kind === "todo") keys.add(todoRowKey(entry.id));
      else {
        keys.add(bundleRowKey(entry.labelId));
        for (const item of entry.items) keys.add(itemRowKey(item));
      }
    }
  }
  return keys;
}

// The row of a thread or todo, on its own (`bundle` null) or inside the open bundle.
function itemRow<Thread, Todo>(
  item: LayoutItem<Thread, Todo>,
  section: string,
  bundle: string | null,
): ListRow<Thread, Todo> {
  const place = { key: itemRowKey(item), id: item.id, section, bundle };
  return item.kind === "thread"
    ? { kind: "thread", ...place, data: item.data }
    : { kind: "todo", ...place, data: item.data };
}

// Whether `row` is a thread or todo shown inside the open bundle of `labelId`.
function insideBundle<Thread, Todo>(
  row: ListRow<Thread, Todo> | undefined,
  labelId: string | undefined,
): boolean {
  return (
    (row?.kind === "thread" || row?.kind === "todo") &&
    labelId !== undefined &&
    row.bundle === labelId
  );
}

type Built<Thread, Todo> = {
  rows: ListRow<Thread, Todo>[];
  bundle: OpenBundle<Thread> | null;
};

// The rows a layout makes, given the open bundle and the rows on their way out. Each thread and
// todo shows once, where it first appears; a heading shows only over rows.
function build<Thread, Todo>(
  layout: readonly LayoutSection<Thread, Todo>[],
  bundle: OpenBundle<Thread> | null,
  removing: ReadonlySet<string>,
  open: string | null,
): Built<Thread, Todo> {
  const seen = new Set<string>();
  const rows: ListRow<Thread, Todo>[] = [];
  let openBundle: OpenBundle<Thread> | null = null;
  // A row key, unless shown already or on its way out.
  const take = (key: string): boolean => {
    if (seen.has(key) || removing.has(key)) return false;
    seen.add(key);
    return true;
  };
  for (const section of layout) {
    const sectionRows: ListRow<Thread, Todo>[] = [];
    for (const entry of section.entries) {
      if (entry.kind === "thread" || entry.kind === "todo") {
        if (!take(itemRowKey<Thread, Todo>(entry))) continue;
        sectionRows.push(itemRow<Thread, Todo>(entry, section.key, null));
      } else {
        const key = bundleRowKey(entry.labelId);
        if (seen.has(key)) continue;
        seen.add(key);
        const isOpen = bundle !== null && bundle.labelId === entry.labelId;
        const items = entry.items.filter((item) => take(itemRowKey(item)));
        if (items.length === 0 && !(isOpen && (entry.more || entry.loading))) {
          continue;
        }
        const threads = items.flatMap((item) =>
          item.kind === "thread" ? [{ id: item.id, data: item.data }] : [],
        );
        sectionRows.push({
          kind: "bundle",
          key,
          labelId: entry.labelId,
          section: section.key,
          items,
          threads,
          todos: items.flatMap((item) =>
            item.kind === "todo" ? [{ id: item.id, data: item.data }] : [],
          ),
          open: isOpen,
          more: entry.more,
          loading: entry.loading,
        });
        if (!isOpen) continue;
        const holdsOpen =
          open !== null && threads.some((thread) => thread.id === open);
        const showAll = bundle.showAll || holdsOpen;
        openBundle = { ...bundle, showAll, threads };
        const shown = showAll ? items : items.slice(0, GROUP_PREVIEW);
        for (const item of shown) {
          sectionRows.push(itemRow(item, section.key, entry.labelId));
        }
        const hidden = items.length - shown.length;
        if (hidden > 0 || entry.more || entry.loading) {
          sectionRows.push({
            kind: "more",
            key: MORE_PREFIX + entry.labelId,
            labelId: entry.labelId,
            section: section.key,
            hidden,
            more: entry.more,
            loading: entry.loading,
          });
        }
      }
    }
    if (sectionRows.length > 0) {
      rows.push({
        kind: "section",
        key: sectionRowKey(section.key),
        section: section.key,
        title: section.title,
        doneAll: section.doneAll,
      });
      rows.push(...sectionRows);
    }
  }
  return { rows, bundle: openBundle };
}

// The state with its rows rebuilt and everything that points at rows made to point at rows that
// exist: focus (to the next row, else the previous), the open thread, selection, the anchor.
function settle<Thread, Todo>(
  before: ListState<Thread, Todo>,
  next: ListState<Thread, Todo>,
): ListState<Thread, Todo> {
  const built = build(next.layout, next.bundle, next.removing, next.open);
  const rows = built.rows;
  const keys = new Set(rows.map((row) => row.key));
  const focusable = new Set(rows.filter(isFocusable).map((row) => row.key));
  const beforeFocusable = before.rows.filter(isFocusable).map((row) => row.key);
  let { focus, pendingFocus, open, reveal, focusBy, effect } = next;
  let bundle = built.bundle;

  // An open thread whose row left closes; focus goes where its row was.
  if (open !== null && !keys.has(open) && pendingFocus !== open) {
    if (focus === null || focus === open) focus = open;
    open = null;
  }
  // A row waited for that came back inside a closed bundle is found at the bundle.
  const holder =
    pendingFocus === null ||
    pendingFocus === open ||
    focusable.has(pendingFocus)
      ? null
      : rows.find(
          (row) =>
            row.kind === "bundle" &&
            !row.open &&
            row.items.some((item) => itemRowKey(item) === pendingFocus),
        );
  if (holder !== undefined && holder !== null) pendingFocus = holder.key;
  if (pendingFocus !== null && focusable.has(pendingFocus)) {
    focus = pendingFocus;
    pendingFocus = null;
    reveal += 1;
  }
  if (bundle?.awaiting != null) {
    const awaiting = bundle.awaiting;
    const known = new Set(
      (before.bundle?.threads ?? []).map((thread) => thread.id),
    );
    const bundleRow = rows.find(
      (row) => row.kind === "bundle" && row.labelId === bundle!.labelId,
    );
    // The page lands after the bundle's last known thread, which may come before the row focus
    // left (an older todo the page's threads sort above); new mail at the bundle's top doesn't count.
    const inside = rows.filter((row) => insideBundle(row, bundle!.labelId));
    const left = inside.findIndex((row) => row.key === awaiting);
    const lastKnown = inside.findLastIndex(
      (row) => row.kind === "thread" && known.has(row.id),
    );
    const from =
      left === -1 || lastKnown === -1
        ? Math.max(left, lastKnown)
        : Math.min(left, lastKnown);
    const fresh = inside
      .slice(from + 1)
      .find((row) => row.kind === "thread" && !known.has(row.id));
    if (fresh !== undefined) {
      focus = fresh.key;
      reveal += 1;
      bundle = { ...bundle, awaiting: null };
    } else if (
      bundleRow?.kind === "bundle" &&
      !bundleRow.loading &&
      (!bundleRow.more || loadingBefore(before, bundle.labelId))
    ) {
      // Gmail has no more, or a page came and brought nothing to show.
      focus = focusable.has(awaiting) ? awaiting : focus;
      bundle = { ...bundle, awaiting: null };
    }
  }
  if (focus !== null && !focusable.has(focus)) {
    const lost = focus;
    focus = survivor(beforeFocusable, lost, (key) => focusable.has(key));
    // Inside an open bundle focus stays in it: its next thread (one shown in the lost one's place
    // counts), else, once Gmail is known to have no more, its previous one.
    const near =
      bundle !== null && bundleOf(before, lost) === bundle.labelId
        ? bundleNeighbors(before, rows, lost, bundle.labelId)
        : null;
    if (near?.next != null && bundleIn(rows, focus) !== bundle?.labelId) {
      focus = near.next;
    }
    // The open bundle's last shown thread went while Gmail has more: focus waits on its header
    // for the next page rather than leaving the bundle.
    const header = rows.find(
      (row) => row.kind === "bundle" && row.labelId === bundle?.labelId,
    );
    if (
      bundle !== null &&
      bundle.awaiting === null &&
      header?.kind === "bundle" &&
      header.more &&
      bundleOf(before, lost) === bundle.labelId &&
      !rows.some(
        (row) => row.key === focus && insideBundle(row, bundle!.labelId),
      )
    ) {
      focus = header.key;
      reveal += 1;
      bundle = { ...bundle, showAll: true, awaiting: lost };
      if (!header.loading) {
        effect = { kind: "loadMore", labelId: bundle.labelId };
      }
    } else if (
      near?.previous != null &&
      bundleIn(rows, focus) !== bundle?.labelId
    ) {
      focus = near.previous;
    }
  }
  if (bundle?.awaiting != null && focus !== bundleRowKey(bundle.labelId)) {
    bundle = { ...bundle, awaiting: null };
  }
  if (focus === null) focusBy = null;
  // Focus outside the open bundle, by any route, closes it.
  if (bundle !== null && bundleIn(rows, focus) !== bundle.labelId) {
    const shut = bundle.labelId;
    return settle(before, {
      ...next,
      focus,
      focusBy,
      reveal,
      pendingFocus,
      effect,
      bundle: null,
      open: open !== null && bundleIn(rows, open) === shut ? null : open,
    });
  }
  // Rows a refetch dropped are gone for good; the ones still listed stay hidden until it does.
  const listed = layoutKeys(next.layout);
  const removing = new Set([...next.removing].filter((key) => listed.has(key)));
  const selectable = new Set(rows.filter(isSelectable).map((row) => row.key));
  const selection = next.selection.filter((key) => selectable.has(key));
  const selectAnchor =
    next.selectAnchor !== null && selectable.has(next.selectAnchor)
      ? next.selectAnchor
      : null;
  let anchor = next.anchor;
  if (anchor !== null && !keys.has(anchor.key)) {
    const moved = survivor(
      before.rows.map((row) => row.key),
      anchor.key,
      (key) => keys.has(key),
    );
    anchor = moved === null ? null : { key: moved, offset: anchor.offset };
  }
  return {
    ...next,
    rows,
    focus,
    focusBy,
    reveal,
    pendingFocus,
    open,
    bundle,
    effect,
    selection:
      selection.length === next.selection.length ? next.selection : selection,
    selectAnchor,
    anchor,
    removing: removing.size === next.removing.size ? next.removing : removing,
  };
}

// The threads of bundle `labelId` around `lost` that `rows` shows: the first after the last one
// kept before it (one newly shown in its place included), and that last one kept before it.
function bundleNeighbors<Thread, Todo>(
  before: ListState<Thread, Todo>,
  rows: readonly ListRow<Thread, Todo>[],
  lost: string,
  labelId: string,
): { next: string | null; previous: string | null } {
  const inside = (row: ListRow<Thread, Todo> | undefined) =>
    insideBundle(row, labelId);
  const shown = new Map(rows.map((row, index) => [row.key, index]));
  const at = before.rows.findIndex((row) => row.key === lost);
  let previous: string | null = null;
  for (let index = at - 1; index >= 0 && previous === null; index--) {
    const key = before.rows[index]!.key;
    const now = shown.get(key);
    if (now !== undefined && inside(rows[now])) previous = key;
  }
  const floor = shown.get(previous ?? bundleRowKey(labelId)) ?? -1;
  const next =
    floor === -1
      ? undefined
      : rows.slice(floor + 1).find((row) => inside(row) && row.key !== lost);
  return { next: next?.key ?? null, previous };
}

// Whether the open bundle's row showed a page on its way before this step.
function loadingBefore<Thread, Todo>(
  before: ListState<Thread, Todo>,
  labelId: string,
): boolean {
  return before.rows.some(
    (row) => row.kind === "bundle" && row.labelId === labelId && row.loading,
  );
}

// The bundle row holding `key` (its header or one of its shown threads), or null.
function bundleOf<Thread, Todo>(
  state: ListState<Thread, Todo>,
  key: string | null,
): string | null {
  return bundleIn(state.rows, key);
}

// The bundle in `rows` holding `key` (its header, a shown thread or its "more" row), or null.
function bundleIn<Thread, Todo>(
  rows: readonly ListRow<Thread, Todo>[],
  key: string | null,
): string | null {
  const row =
    key === null ? null : (rows.find((row) => row.key === key) ?? null);
  if (row === null) return null;
  if (row.kind === "bundle" || row.kind === "more") return row.labelId;
  if (row.kind === "thread" || row.kind === "todo") return row.bundle;
  return null;
}

// The label of the bundle that lists thread `id`, open or closed, or null.
function bundleListing<Thread, Todo>(
  state: ListState<Thread, Todo>,
  id: string,
): string | null {
  for (const row of state.rows) {
    if (
      row.kind === "bundle" &&
      row.threads.some((thread) => thread.id === id)
    ) {
      return row.labelId;
    }
  }
  return null;
}

function openBundle<Thread, Todo>(
  state: ListState<Thread, Todo>,
  labelId: string,
): OpenBundle<Thread> {
  const row = state.rows.find(
    (candidate) => candidate.kind === "bundle" && candidate.labelId === labelId,
  );
  return {
    labelId,
    showAll: false,
    awaiting: null,
    threads: row?.kind === "bundle" ? row.threads : [],
  };
}

// Focus moved by keys to `key`: it scrolls into view, a bundle it leaves closes, and an open
// thread gives way to the thread focus lands on.
function keyFocus<Thread, Todo>(
  state: ListState<Thread, Todo>,
  key: string,
): ListState<Thread, Todo> {
  const left =
    state.bundle !== null &&
    bundleOf(state, state.focus) === state.bundle.labelId &&
    bundleOf(state, key) !== state.bundle.labelId;
  const target = rowOf(state, key);
  let open = state.open;
  if (open !== null && open !== key) {
    open = target?.kind === "thread" ? key : null;
  }
  return {
    ...state,
    focus: key,
    focusBy: "keyboard",
    reveal: state.reveal + 1,
    pendingFocus: null,
    open,
    bundle: left ? null : state.bundle,
  };
}

function step<Thread, Todo>(
  state: ListState<Thread, Todo>,
  event: ListEvent<Thread, Todo>,
): ListState<Thread, Todo> {
  switch (event.type) {
    case "layout":
      return { ...state, layout: event.sections };
    case "next":
    case "previous": {
      const order = focusableKeys(state);
      if (order.length === 0) return state;
      const index = state.focus === null ? -1 : order.indexOf(state.focus);
      const forward = event.type === "next";
      const bundle = state.bundle;
      const row = rowOf(state, state.focus);
      if (bundle?.awaiting != null && forward) {
        // Still waiting: ask again unless a page is already on its way.
        const header = rowOf(state, bundleRowKey(bundle.labelId));
        return header?.kind === "bundle" && !header.loading && header.more
          ? { ...state, effect: { kind: "loadMore", labelId: bundle.labelId } }
          : state;
      }
      if (forward && bundle !== null && row !== null) {
        const inBundle = state.rows.filter((candidate) =>
          insideBundle(candidate, bundle.labelId),
        );
        const last = inBundle.at(-1);
        const header = state.rows.find(
          (candidate) =>
            candidate.kind === "bundle" && candidate.labelId === bundle.labelId,
        );
        const atEnd =
          (last !== undefined && last.key === row.key) ||
          (last === undefined && header?.key === row.key);
        if (atEnd && header?.kind === "bundle") {
          if (header.items.length > inBundle.length) {
            const shown = settle(state, {
              ...state,
              bundle: { ...bundle, showAll: true },
            });
            const first = header.items[inBundle.length]!;
            return keyFocus(shown, itemRowKey(first));
          }
          if (header.more) {
            return {
              ...keyFocus(state, header.key),
              bundle: { ...bundle, showAll: true, awaiting: row.key },
              effect: header.loading
                ? null
                : { kind: "loadMore", labelId: bundle.labelId },
            };
          }
        }
      }
      const at =
        index === -1
          ? forward
            ? 0
            : order.length - 1
          : Math.min(order.length - 1, Math.max(0, index + (forward ? 1 : -1)));
      const key = order[at]!;
      if (key === state.focus) {
        return { ...state, focusBy: "keyboard", reveal: state.reveal + 1 };
      }
      return keyFocus(state, key);
    }
    case "enter": {
      const row = rowOf(state, state.focus);
      if (row === null) return state;
      if (row.kind === "bundle") return toggleBundle(state, row.labelId);
      if (row.kind === "todo") {
        return { ...state, effect: { kind: "edit", todoId: row.id } };
      }
      if (row.kind !== "thread" || state.open === row.key) return state;
      return openThread(state, row.id);
    }
    case "back": {
      if (state.menu !== null) return { ...state, menu: null };
      if (state.open !== null) {
        return {
          ...state,
          open: null,
          focus: state.open,
          focusBy: "keyboard",
          pendingFocus: null,
        };
      }
      if (state.selection.length > 0) {
        return { ...state, selection: [], selectAnchor: null };
      }
      if (state.bundle !== null) return closeBundle(state);
      if (state.focus !== null) {
        return { ...state, focus: null, focusBy: null, pendingFocus: null };
      }
      return state;
    }
    case "select": {
      const row = rowOf(state, state.focus);
      if (row === null || !isSelectable(row)) return state;
      return {
        ...state,
        selection: toggleSelected(state.selection, row.key),
        selectAnchor: row.key,
      };
    }
    case "command": {
      const target = listTarget(state);
      if (target === null) return state;
      return {
        ...state,
        effect: { kind: "act", action: event.action, target },
      };
    }
    case "focus": {
      const row = rowOf(state, event.key);
      if (row === null || !isFocusable(row) || state.focus === event.key) {
        return state;
      }
      // Tab is the keyboard too: leaving the open bundle closes it. Neither opens a thread.
      const left =
        event.by === "keyboard" &&
        state.bundle !== null &&
        bundleOf(state, state.focus) === state.bundle.labelId &&
        bundleOf(state, event.key) !== state.bundle.labelId;
      return {
        ...state,
        focus: event.key,
        focusBy: event.by,
        pendingFocus: null,
        open: state.open === event.key ? state.open : null,
        bundle: left ? null : state.bundle,
      };
    }
    case "reveal": {
      const row = rowOf(state, event.key);
      if (row === null)
        return { ...state, open: null, pendingFocus: event.key };
      else if (!isFocusable(row)) return state;
      else return { ...keyFocus(state, event.key), focusBy: "pointer" };
    }
    case "tap": {
      const row = rowOf(state, event.key);
      if (row === null) return state;
      // A tap outside the top layer closes that layer rather than acting.
      if (state.menu !== null) return { ...state, menu: null };
      if (
        state.open !== null &&
        state.bundle !== null &&
        bundleOf(state, row.key) !== state.bundle.labelId
      ) {
        return step(state, { type: "closeThread" });
      }
      if (
        state.bundle !== null &&
        bundleOf(state, row.key) !== state.bundle.labelId
      ) {
        return { ...closeBundle(state), focusBy: "pointer" };
      }
      if (row.kind === "bundle") {
        return { ...toggleBundle(state, row.labelId), focusBy: "pointer" };
      }
      if (row.kind === "todo") {
        return step(state, { type: "focus", key: row.key, by: "pointer" });
      }
      if (row.kind !== "thread") return state;
      return { ...openThread(state, row.id), focusBy: "pointer" };
    }
    case "openThread":
      return openThread(state, event.id);
    case "closeThread":
      if (state.open === null) return state;
      return {
        ...state,
        open: null,
        focus: rowOf(state, state.open) === null ? state.focus : state.open,
        pendingFocus:
          state.pendingFocus === state.open ? null : state.pendingFocus,
      };
    case "closeBundle":
      return state.bundle === null ? state : closeBundle(state);
    case "dismiss":
      if (state.menu !== null) return { ...state, menu: null };
      if (state.open !== null) return step(state, { type: "closeThread" });
      return closeBundle(state);
    case "openMenu":
      return state.menu === event.id ? state : { ...state, menu: event.id };
    case "closeMenu":
      return state.menu === event.id ? { ...state, menu: null } : state;
    case "showAll":
      if (state.bundle === null) return state;
      return { ...state, bundle: { ...state.bundle, showAll: true } };
    case "pick": {
      const row = rowOf(state, event.key);
      if (row === null || !isSelectable(row)) return state;
      const selection = event.range
        ? selectRange(
            state.selection,
            selectableKeys(state),
            state.selectAnchor,
            row.key,
          )
        : toggleSelected(state.selection, row.key);
      return { ...state, selection, selectAnchor: row.key };
    }
    case "pickAll": {
      const all = selectableKeys(state);
      const every =
        all.length > 0 && all.every((key) => state.selection.includes(key));
      return { ...state, selection: every ? [] : all, selectAnchor: null };
    }
    case "clearSelection":
      return { ...state, selection: [], selectAnchor: null };
    case "act": {
      const listed = layoutKeys(state.layout);
      const shown = new Set(state.rows.map((row) => row.key));
      // A bundle goes with its threads and todos: it shows again when Gmail has more of them.
      const removes = event.removes
        .flatMap((key) => {
          const row = rowOf(state, key);
          return row?.kind === "bundle" ? row.items.map(itemRowKey) : [key];
        })
        .filter(
          (key) =>
            (rowThreadId(key) !== null || rowTodoId(key) !== null) &&
            (listed.has(key) || shown.has(key)),
        );
      const removing = new Set([...state.removing, ...removes]);
      const closes = state.open !== null && removes.includes(state.open);
      return {
        ...state,
        removing: removes.length === 0 ? state.removing : removing,
        open: closes ? null : state.open,
        focus: closes ? state.open : state.focus,
        pendingFocus:
          state.pendingFocus !== null && removes.includes(state.pendingFocus)
            ? null
            : state.pendingFocus,
        selection:
          event.from === "selection" && event.clearSelection
            ? []
            : state.selection,
        undo: event.undoable
          ? { keys: removes, focus: closes ? state.open : state.focus }
          : state.undo,
      };
    }
    case "undo": {
      const undo = state.undo;
      if (undo === null) return state;
      if (undo.keys.length === 0) return { ...state, undo: null };
      const back = new Set(undo.keys);
      const target =
        undo.focus !== null &&
        (back.has(undo.focus) || layoutKeys(state.layout).has(undo.focus))
          ? undo.focus
          : undo.keys[0]!;
      return {
        ...state,
        removing: new Set([...state.removing].filter((key) => !back.has(key))),
        pendingFocus: target,
        focusBy: "keyboard",
        // Focus goes back to the row undone, so a thread open elsewhere closes.
        open: state.open === target ? state.open : null,
        undo: null,
      };
    }
    case "undoExpired":
      return state.undo === null ? state : { ...state, undo: null };
    case "restore": {
      const back = new Set(event.keys);
      return {
        ...state,
        removing: new Set([...state.removing].filter((key) => !back.has(key))),
      };
    }
    case "anchor":
      return { ...state, anchor: { key: event.key, offset: event.offset } };
    case "reset":
      return {
        ...emptyListState<Thread, Todo>(),
        layout: state.layout,
        undo: state.undo,
        reveal: state.reveal,
      };
  }
}

function openThread<Thread, Todo>(
  state: ListState<Thread, Todo>,
  id: string,
): ListState<Thread, Todo> {
  const key = threadRowKey(id);
  const holder = bundleListing(state, id);
  let bundle = state.bundle;
  if (holder === null) bundle = null;
  else if (bundle?.labelId !== holder) {
    bundle = { ...openBundle(state, holder), showAll: true };
  }
  const listed = state.rows.some((row) => row.key === key) || holder !== null;
  return {
    ...state,
    open: id,
    bundle,
    focus: listed ? key : state.focus,
    pendingFocus: listed ? null : key,
  };
}

function toggleBundle<Thread, Todo>(
  state: ListState<Thread, Todo>,
  labelId: string,
): ListState<Thread, Todo> {
  const key = bundleRowKey(labelId);
  if (state.bundle?.labelId === labelId) {
    return { ...closeBundle(state), open: null, focus: key };
  }
  return {
    ...state,
    open: null,
    bundle: openBundle(state, labelId),
    focus: key,
    pendingFocus: null,
  };
}

// Close the open bundle (and a thread open in it); focus inside it goes to its header.
function closeBundle<Thread, Todo>(
  state: ListState<Thread, Todo>,
): ListState<Thread, Todo> {
  const bundle = state.bundle;
  if (bundle === null) return state;
  const inside = bundleOf(state, state.focus) === bundle.labelId;
  const openInside =
    state.open !== null && bundleOf(state, state.open) === bundle.labelId;
  return {
    ...state,
    bundle: null,
    open: openInside ? null : state.open,
    focus: inside ? bundleRowKey(bundle.labelId) : state.focus,
  };
}

/**
 * The list after `event`: the one transition every input goes through.
 *
 * A step applies the event, then rebuilds the rows from the last layout and repairs whatever
 * pointed at a row that is gone: focus moves to the next row in visual order, else the previous;
 * an open thread whose row left closes; selection and the scroll anchor keep only rows that
 * exist. `effect` carries the work the step asks of the app (a page to load, an action to apply,
 * a todo to edit) and is null after any step that asks none. The rules are in docs/design.md, "The
 * list".
 *
 * ```ts
 * let list = listStep(emptyListState(), { type: "layout", sections });
 * list = listStep(list, { type: "next" });
 * ```
 */
export function listStep<Thread, Todo>(
  state: ListState<Thread, Todo>,
  event: ListEvent<Thread, Todo>,
): ListState<Thread, Todo> {
  const next = step({ ...state, effect: null }, event);
  return settle(state, next);
}

/** Every way `state` breaks the list's rules (design.md, "The list"); empty when it keeps them all. */
export function listProblems<Thread, Todo>(
  state: ListState<Thread, Todo>,
): string[] {
  const problems: string[] = [];
  const keys = state.rows.map((row) => row.key);
  const keySet = new Set(keys);
  if (keySet.size !== keys.length) problems.push("a row key repeats");
  const focusable = new Set(focusableKeys(state));
  const selectable = new Set(selectableKeys(state));
  if (state.focus !== null && !focusable.has(state.focus)) {
    problems.push(`focus ${state.focus} is not a row that takes focus`);
  }
  if (state.pendingFocus !== null && keySet.has(state.pendingFocus)) {
    problems.push(`pending focus ${state.pendingFocus} is already listed`);
  }
  if (
    state.open !== null &&
    state.focus !== state.open &&
    state.pendingFocus !== state.open
  ) {
    problems.push(`open thread ${state.open} doesn't hold focus`);
  }
  if (state.open !== null && keySet.has(state.open)) {
    if (rowOf(state, state.open)?.kind !== "thread") {
      problems.push(`open ${state.open} is not a thread row`);
    }
  }
  if (new Set(state.selection).size !== state.selection.length) {
    problems.push("selection repeats a row");
  }
  for (const key of state.selection) {
    if (!selectable.has(key)) problems.push(`selected ${key} is not a row`);
  }
  for (const key of state.removing) {
    if (keySet.has(key)) problems.push(`${key} shows while on its way out`);
  }
  if (state.anchor !== null && !keySet.has(state.anchor.key)) {
    problems.push(`anchor ${state.anchor.key} is not a row`);
  }
  // One place per thread and per todo.
  const places = new Map<string, number>();
  const count = (key: string) => places.set(key, (places.get(key) ?? 0) + 1);
  for (const row of state.rows) {
    if (row.kind === "thread" || row.kind === "todo") {
      if (row.bundle === null) count(row.key);
    } else if (row.kind === "bundle") {
      for (const item of row.items) count(itemRowKey(item));
    }
  }
  for (const [key, times] of places) {
    if (times > 1) problems.push(`${key} shows in ${times} places`);
  }
  const openRows = state.rows.filter(
    (row) => row.kind === "bundle" && row.open,
  );
  if (openRows.length > 1) problems.push("more than one bundle is open");
  const bundle = state.bundle;
  if (bundle !== null) {
    const index = state.rows.findIndex(
      (row) => row.kind === "bundle" && row.labelId === bundle.labelId,
    );
    const header = state.rows[index];
    if (header?.kind !== "bundle" || !header.open) {
      problems.push(`open bundle ${bundle.labelId} has no open row`);
    } else {
      // Its shown threads and todos follow its header, in its order.
      let at = index + 1;
      for (const item of header.items) {
        const row = state.rows[at];
        if (row?.key !== itemRowKey(item)) break;
        at += 1;
      }
      for (const row of state.rows.slice(at)) {
        if (insideBundle(row, bundle.labelId)) {
          problems.push(`${row.key} is apart from its bundle`);
        }
      }
    }
    if (bundleOf(state, state.focus) !== bundle.labelId) {
      problems.push(
        `focus ${state.focus} is outside open bundle ${bundle.labelId}`,
      );
    }
    if (
      bundle.awaiting !== null &&
      state.focus !== bundleRowKey(bundle.labelId)
    ) {
      problems.push("focus left the header of a bundle loading for it");
    }
  } else if (openRows.length > 0) {
    problems.push("a bundle row is open with no open bundle");
  }
  for (const row of state.rows) {
    if (
      (row.kind === "thread" || row.kind === "todo") &&
      row.bundle !== null &&
      row.bundle !== bundle?.labelId
    ) {
      problems.push(`${row.key} shows inside closed bundle ${row.bundle}`);
    }
  }
  // A heading heads at least one row.
  state.rows.forEach((row, index) => {
    const after = state.rows[index + 1];
    if (
      row.kind === "section" &&
      (after === undefined || after.kind === "section")
    ) {
      problems.push(`heading ${row.key} heads nothing`);
    }
  });
  return problems;
}
