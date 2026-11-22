import { afterEach, beforeEach, expect, mock, test } from "bun:test";
import { inboxSections, layoutSections } from "@inbox/shared/list-model";
import {
  bundleRowKey,
  emptyListState,
  type ListEvent,
  type ListState,
  listProblems,
  listStep,
} from "@inbox/shared/list-state";
import type { Todo } from "@inbox/shared/todos";
import { JSDOM } from "jsdom";
import type { Row } from "./list-model";
import { ListMenus, provideMenuLayer } from "./menu-layer";
import { pressAway, ROW_KEY } from "./press-away";
import type { ThreadSummary } from "./thread-summary";

// The browser's Back, as popovers register with it: each open entry's close, and how many the
// app took back off itself.
const backEntries: (() => void)[] = [];
let backReleased = 0;
mock.module("./back-stack.svelte", () => ({
  pushBack: (close: () => void) => {
    backEntries.push(close);
    return () => {
      backReleased += 1;
    };
  },
}));
const { popover } = await import("./components/popover");

const NOW = new Date(2026, 10, 2, 11, 50).getTime();
const HOUR = 60 * 60 * 1000;

function row(id: string, hoursAgo: number, labelIds: string[]): Row {
  const thread: ThreadSummary = {
    id,
    historyId: "1",
    subject: id,
    snippet: "",
    senders: [],
    messageCount: 1,
    lastDate: NOW - hoursAgo * HOUR,
    sentDate: NOW - hoursAgo * HOUR,
    labelIds,
    unread: false,
    messageIds: [],
    firstDate: NOW - hoursAgo * HOUR,
  };
  return {
    thread,
    status: "none",
    snoozeUntil: null,
    sortDate: thread.lastDate,
  };
}

// A list of thread a, bundle L of g1–g3 (Gmail holding more of L when `more`), then thread z.
function bundleList(more: boolean): ListState<Row, Todo> {
  const rows = [
    row("a", 1, ["INBOX"]),
    row("g1", 2, ["INBOX", "L"]),
    row("g2", 3, ["INBOX", "L"]),
    row("g3", 4, ["INBOX", "L"]),
    row("z", 5, ["INBOX"]),
  ];
  return listStep(emptyListState<Row, Todo>(), {
    type: "layout",
    sections: layoutSections(inboxSections(rows, NOW, ["L"]), () => ({
      more,
      loading: false,
    })),
  });
}

// A stand-in for the mail state: the list, stepped by each event, and its menu layer.
class TestList {
  list: ListState<Row, Todo>;
  readonly menus = new ListMenus(
    (event) => this.dispatch(event),
    () => this.list.menu,
  );

  constructor(list: ListState<Row, Todo>) {
    this.list = list;
  }

  dispatch(event: ListEvent<Row, Todo>): void {
    const before = this.list;
    this.list = listStep(before, event);
    this.menus.follow(before.menu, this.list.menu);
    expect(listProblems(this.list)).toEqual([]);
  }
}

let document: Document;
let stopLayer: () => void = () => {};

beforeEach(() => {
  document = new JSDOM(`
    <div id="bundle">
      <div id="anchor"><button id="opener" aria-haspopup="menu">Label</button><div id="menu" role="menu"><button id="item">Work</button></div></div>
      <p id="away">Away</p>
    </div>
    <button id="row">Row</button>
    <div id="rest"><div ${ROW_KEY}="a"><button id="row-a">a</button></div><p id="gap">Gap</p></div>
  `).window.document;
});

afterEach(() => stopLayer());

function element(id: string): HTMLElement {
  return document.getElementById(id) as HTMLElement;
}

// Press and release on `target`, as a mouse does; returns whether the click reached it.
function clickOn(target: HTMLElement): boolean {
  const view = document.defaultView as Window & typeof globalThis;
  let clicked = false;
  target.addEventListener("click", () => {
    clicked = true;
  });
  target.dispatchEvent(
    new view.MouseEvent("mousedown", { bubbles: true, cancelable: true }),
  );
  target.dispatchEvent(
    new view.MouseEvent("click", { bubbles: true, cancelable: true }),
  );
  return clicked;
}

// Open a menu in an open bundle; returns the list and a flag for whether the menu is still showing.
function menuInBundle(): { list: TestList; shown: () => boolean } {
  const list = new TestList(bundleList(false));
  list.dispatch({ type: "tap", key: bundleRowKey("L") });
  stopLayer = provideMenuLayer(list.menus);
  let shown = true;
  const detach = popover({
    onClose: () => {
      shown = false;
      detach?.();
    },
  })(element("menu"));
  return { list, shown: () => shown };
}

test("a click away from a menu inside an open bundle closes only the menu", () => {
  const { list, shown } = menuInBundle();
  expect(list.list.menu).not.toBeNull();
  expect(clickOn(element("away"))).toBe(false);
  expect(shown()).toBe(false);
  expect(list.list.menu).toBeNull();
  expect(list.list.bundle?.labelId).toBe("L");
});

test("the second click away, with no menu left, closes the bundle", () => {
  const { list } = menuInBundle();
  clickOn(element("away"));
  list.dispatch({ type: "dismiss" });
  expect(list.list.bundle).toBeNull();
});

test("a press away that ends in no click lets the next click through", () => {
  const { list } = menuInBundle();
  const view = document.defaultView as Window & typeof globalThis;
  element("away").dispatchEvent(
    new view.MouseEvent("mousedown", { bubbles: true, cancelable: true }),
  );
  expect(list.list.menu).toBeNull();
  expect(clickOn(element("row"))).toBe(true);
});

test("clicks inside the menu reach it and leave it open", () => {
  const { list, shown } = menuInBundle();
  expect(clickOn(element("item"))).toBe(true);
  expect(shown()).toBe(true);
  expect(list.list.menu).not.toBeNull();
});

test("Escape with a menu open closes only the menu", () => {
  const { list, shown } = menuInBundle();
  const view = document.defaultView as Window & typeof globalThis;
  const event = new view.KeyboardEvent("keydown", {
    key: "Escape",
    bubbles: true,
    cancelable: true,
  });
  element("item").dispatchEvent(event);
  expect(event.defaultPrevented).toBe(true);
  expect(shown()).toBe(false);
  expect(list.list.menu).toBeNull();
  expect(list.list.bundle?.labelId).toBe("L");
});

test("a step that takes the menu away closes it", () => {
  const { list, shown } = menuInBundle();
  list.dispatch({ type: "back" });
  expect(shown()).toBe(false);
  expect(list.list.bundle?.labelId).toBe("L");
});

test("Done on an open bundle's last shown threads, Gmail holding more, asks for its next page", () => {
  const list = new TestList(bundleList(true));
  list.dispatch({ type: "tap", key: bundleRowKey("L") });
  list.dispatch({ type: "next" });
  list.dispatch({
    type: "act",
    removes: ["g1", "g2", "g3"],
    from: "row",
    undoable: true,
    clearSelection: false,
  });
  expect(list.list.focus).toBe(bundleRowKey("L"));
  expect(list.list.bundle?.awaiting).not.toBeNull();
  expect(list.list.effect).toEqual({ kind: "loadMore", labelId: "L" });
});

// The dimmed rest of `list` (row a and a gap), pressing away while a bundle is open.
function dimmedRest(list: TestList): void {
  pressAway({
    away: () => list.list.bundle !== null,
    dispatch: (event) => list.dispatch(event),
  })(element("rest"));
}

// A click as a browser makes it: a press that isn't cancelled focuses the row it lands in first.
function clickRow(list: TestList, target: HTMLElement): void {
  const view = document.defaultView as Window & typeof globalThis;
  const press = new view.MouseEvent("mousedown", {
    bubbles: true,
    cancelable: true,
  });
  target.dispatchEvent(press);
  const key = target.closest(`[${ROW_KEY}]`)?.getAttribute(ROW_KEY);
  if (!press.defaultPrevented && key != null) {
    list.dispatch({ type: "focus", key, by: "pointer" });
  }
  target.dispatchEvent(
    new view.MouseEvent("click", { bubbles: true, cancelable: true }),
  );
}

test("a click on the dimmed rest closes only the open thread of an open bundle", () => {
  const list = new TestList(bundleList(false));
  list.dispatch({ type: "tap", key: bundleRowKey("L") });
  list.dispatch({ type: "tap", key: "g1" });
  dimmedRest(list);
  clickRow(list, element("row-a"));
  expect(list.list.open).toBeNull();
  expect(list.list.bundle?.labelId).toBe("L");
  clickRow(list, element("row-a"));
  expect(list.list.bundle).toBeNull();
  expect(list.list.open).toBeNull();
  expect(list.list.focus).not.toBe("a");
});

test("a click on the dimmed rest away from any row closes the top layer", () => {
  const list = new TestList(bundleList(false));
  list.dispatch({ type: "tap", key: bundleRowKey("L") });
  dimmedRest(list);
  clickRow(list, element("gap"));
  expect(list.list.bundle).toBeNull();
});

test("with no bundle open, a click on the rest reaches its row", () => {
  const list = new TestList(bundleList(false));
  dimmedRest(list);
  expect(clickOn(element("row-a"))).toBe(true);
});

test("a click on the dimmed rest with a menu open closes only the menu", () => {
  const list = new TestList(bundleList(false));
  list.dispatch({ type: "tap", key: bundleRowKey("L") });
  list.dispatch({ type: "tap", key: "g1" });
  stopLayer = provideMenuLayer(list.menus);
  let shown = true;
  popover({
    onClose: () => {
      shown = false;
    },
  })(element("menu"));
  dimmedRest(list);
  clickRow(list, element("row-a"));
  expect(shown).toBe(false);
  expect(list.list.menu).toBeNull();
  expect(list.list.open).toBe("g1");
  expect(list.list.bundle?.labelId).toBe("L");
});

test("Back closes a popover, and a popover closed otherwise takes its entry back", () => {
  backEntries.length = 0;
  backReleased = 0;
  let shown = true;
  const detach = popover({
    onClose: () => {
      shown = false;
    },
  })(element("menu"));
  expect(backEntries).toHaveLength(1);
  backEntries[0]?.();
  expect(shown).toBe(false);
  detach?.();
  shown = true;
  const again = popover({
    onClose: () => {
      shown = false;
    },
  })(element("menu"));
  const released = backReleased;
  again?.();
  expect(backReleased).toBe(released + 1);
});
