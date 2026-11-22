import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { mergeFirstPage } from "./bundles";
import type { HeldPlaces } from "./held-places";
import {
  doneAllIds,
  doneAllTodos,
  focusInboxSections,
  inboxSections,
  type ListThread,
  layoutKeys,
  layoutSections,
  openPlaces,
  plainSections,
  type Row,
  rowInboxAction,
  rowTodo,
  type SectionModel,
  snoozedSections,
} from "./list-model";
import {
  bundleRowKey,
  emptyListState,
  focusableKeys,
  type ListEvent,
  type ListState,
  listProblems,
  listStep,
  todoRowKey,
} from "./list-state";
import { epochToIso } from "./local-time";
import {
  type EmailTodo,
  emailTodosByThread,
  newEmailTodo,
  newTodo,
  ownKey,
  positionAbove,
  type Todo,
} from "./todos";

const NOW = new Date(2026, 10, 2, 11, 50).getTime();
const HOUR = 60 * 60 * 1000;

function row(
  id: string,
  hoursAgo: number,
  labelIds: string[] = ["INBOX"],
): Row {
  const thread: ListThread = { id, labelIds };
  return {
    thread,
    status: "none",
    snoozeUntil: null,
    sortDate: NOW - hoursAgo * HOUR,
  };
}

const NO_MORE = () => ({ more: false, loading: false });

// The list model after `sections` and then `events`, checked against every rule of the list.
function listOf(
  sections: readonly SectionModel[],
  ...events: ListEvent<Row, Todo>[]
): ListState<Row, Todo> {
  let state = listStep(emptyListState<Row, Todo>(), {
    type: "layout",
    sections: layoutSections(sections, NO_MORE),
  });
  for (const event of events) state = listStep(state, event);
  expect(listProblems(state)).toEqual([]);
  return state;
}

test("pinned threads sit on top, then time sections with label groups", () => {
  const rows = [
    row("today-1", 1),
    row("pinned-old", 400, ["INBOX", "STARRED"]),
    row("gh-1", 2, ["INBOX", "Label_gh"]),
    row("gh-2", 3, ["INBOX", "Label_gh"]),
    row("gh-3", 4, ["INBOX", "Label_gh"]),
    row("yesterday", 20),
  ];
  const sections = inboxSections(rows, NOW, ["Label_gh"]);
  expect(sections.map((section) => section.key)).toEqual([
    "pinned",
    "today",
    "yesterday",
  ]);
  expect(sections[0]!.canDoneAll).toBe(false);
  expect(sections[1]!.entries.map((entry) => entry.kind)).toEqual([
    "thread",
    "group",
  ]);
  expect(doneAllIds(sections[1]!)).toEqual(["today-1", "gh-1", "gh-2", "gh-3"]);
});

test("a thread listed twice shows once, at its newest date", () => {
  const rows = [row("a", 30), row("b", 2), row("a", 1)];
  const inbox = inboxSections(rows, NOW, []);
  expect(layoutKeys(layoutSections(inbox, NO_MORE))).toEqual(
    new Set(["a", "b"]),
  );
  expect(inbox.map((section) => section.key)).toEqual(["today"]);
  const plain = plainSections(rows, NOW);
  expect(
    plain.flatMap((section) =>
      section.entries.map((entry) =>
        entry.kind === "thread" ? entry.thread.thread.id : "",
      ),
    ),
  ).toEqual(["a", "b"]);
});

test("listed rows include threads inside groups, and the groups", () => {
  const rows = [
    row("a", 1),
    row("g1", 2, ["INBOX", "L"]),
    row("g2", 3, ["INBOX", "L"]),
    row("g3", 4, ["INBOX", "L"]),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  expect([...layoutKeys(layoutSections(sections, NO_MORE))].sort()).toEqual(
    ["a", "g1", "g2", "g3", bundleRowKey("L")].sort(),
  );
});

test("j and k walk rows and group headers, entering expanded groups", () => {
  const rows = [
    row("a", 1),
    ...[1, 2, 3, 4, 5, 6, 7].map((index) =>
      row(`g${index}`, 1 + index, ["INBOX", "L"]),
    ),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  const group = bundleRowKey("L");
  expect(focusableKeys(listOf(sections))).toEqual(["a", group]);
  const open = listOf(sections, { type: "tap", key: group });
  expect(focusableKeys(open)).toEqual([
    "a",
    group,
    "g1",
    "g2",
    "g3",
    "g4",
    "g5",
  ]);
  expect(
    focusableKeys(
      listOf(sections, { type: "tap", key: group }, { type: "showAll" }),
    ),
  ).toHaveLength(9);
});

test("a group holding the open thread is open, all its threads in j and k's path", () => {
  const rows = [
    row("a", 1),
    row("g1", 2, ["INBOX", "L"]),
    row("g2", 3, ["INBOX", "L"]),
    row("g3", 4, ["INBOX", "L"]),
    row("g4", 5, ["INBOX", "L"]),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  const group = bundleRowKey("L");
  const inside = listOf(sections, { type: "openThread", id: "g4" });
  expect(focusableKeys(inside)).toEqual(["a", group, "g1", "g2", "g3", "g4"]);
  expect(inside.bundle?.labelId).toBe("L");
  expect(listOf(sections, { type: "openThread", id: "a" }).bundle).toBeNull();
});

test("Snoozed lists the soonest wake first", () => {
  const later = {
    ...row("later", 1),
    status: "snoozed" as const,
    snoozeUntil: NOW + 48 * HOUR,
  };
  const soon = {
    ...row("soon", 1),
    status: "snoozed" as const,
    snoozeUntil: NOW + 2 * HOUR,
  };
  const sections = snoozedSections([later, soon], NOW);
  expect(sections.map((section) => section.title)).toEqual([
    "Today",
    "This Wednesday",
  ]);
});

type ThreadFixture = { id: string; date: string; labels: string[] };

type HeldFixture = {
  threads: Record<string, { date: string; pinned: boolean; single: boolean }>;
  bundles: Record<string, string>;
  pins?: Record<string, boolean>;
};

type GroupFixture = {
  name: string;
  bundleLabels: string[];
  todoThreads?: string[];
  held?: HeldFixture;
  exhausted?: string[];
  threads: ThreadFixture[];
  sections: { key: string; entries: string[] }[];
};

type PlainFixture = {
  name: string;
  held: HeldFixture;
  threads: ThreadFixture[];
  sections: { key: string; entries: string[] }[];
};

type PreviewFixture = {
  name: string;
  threads: number;
  showAll: boolean;
  expect: { shown: number; hidden: number };
};

const groupFixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/label-group-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  now: string;
  inbox: GroupFixture[];
  plain: PlainFixture[];
  preview: PreviewFixture[];
};

function fixtureRows(threads: ThreadFixture[]): Row[] {
  return threads.map(({ id, date, labels }) => {
    const base = row(id, 0, labels);
    const sortDate = new Date(date).getTime();
    return { ...base, sortDate };
  });
}

function heldPlaces(held: HeldFixture | undefined): HeldPlaces {
  return {
    threads: new Map(
      Object.entries(held?.threads ?? {}).map(([id, place]) => [
        id,
        { ...place, date: new Date(place.date).getTime() },
      ]),
    ),
    bundles: new Map(
      Object.entries(held?.bundles ?? {}).map(([labelId, date]) => [
        labelId,
        new Date(date).getTime(),
      ]),
    ),
    pins: new Map(Object.entries(held?.pins ?? {})),
  };
}

function shape(sections: SectionModel[]): { key: string; entries: string[] }[] {
  return sections.map((section) => ({
    key: section.key,
    entries: section.entries.map((entry) => {
      if (entry.kind === "thread") return entry.thread.thread.id;
      else if (entry.kind === "todo") return `todo:${entry.todo.id}`;
      else
        return `${entry.labelId}[${entry.threads.map((member) => member.thread.id).join(",")}]`;
    }),
  }));
}

describe("label group fixtures (must match Android)", () => {
  for (const fixture of groupFixtures.preview) {
    test(`open bundle preview: ${fixture.name}`, () => {
      const rows = Array.from({ length: fixture.threads }, (_, index) =>
        row(`g${index}`, 1 + index, ["INBOX", "L"]),
      );
      const sections = inboxSections(rows, NOW, ["L"], {
        threads: new Map(),
        bundles: new Map([["L", NOW]]),
        pins: new Map(),
      });
      const events: ListEvent<Row, Todo>[] = [
        { type: "tap", key: bundleRowKey("L") },
      ];
      if (fixture.showAll) events.push({ type: "showAll" });
      const shown = listOf(sections, ...events).rows.filter(
        (listed) => listed.kind === "thread" && listed.bundle === "L",
      ).length;
      expect({ shown, hidden: fixture.threads - shown }).toEqual(
        fixture.expect,
      );
    });
  }
  const now = new Date(groupFixtures.now).getTime();
  for (const fixture of groupFixtures.inbox) {
    test(fixture.name, () => {
      const sections = inboxSections(
        fixtureRows(fixture.threads),
        now,
        fixture.bundleLabels,
        heldPlaces(fixture.held),
        [],
        new Set(fixture.exhausted ?? []),
        emailTodos(...(fixture.todoThreads ?? [])),
      );
      expect(shape(sections)).toEqual(fixture.sections);
    });
  }
  for (const fixture of groupFixtures.plain) {
    test(`plainSections: ${fixture.name}`, () => {
      const sections = plainSections(
        fixtureRows(fixture.threads),
        now,
        heldPlaces(fixture.held),
      );
      expect(shape(sections)).toEqual(fixture.sections);
    });
  }
});

test("a bundle spanning sections is one stop for j and k, under its newest thread's section", () => {
  const rows = [
    row("a", 1),
    row("g1", 2, ["INBOX", "L"]),
    row("b", 20),
    row("g2", 21, ["INBOX", "L"]),
    row("g3", 400, ["INBOX", "L"]),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  const group = bundleRowKey("L");
  expect(focusableKeys(listOf(sections, { type: "tap", key: group }))).toEqual([
    "a",
    group,
    "g1",
    "g2",
    "g3",
    "b",
  ]);
});

test("the open thread and bundle are held where the list shows them", () => {
  const rows = [
    row("a", 1),
    row("p", 2, ["INBOX", "STARRED"]),
    row("g1", 3, ["INBOX", "L"]),
    row("g2", 4, ["INBOX", "L"]),
    row("g3", 30, ["INBOX", "L"]),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  const single = openPlaces(sections, null, "p");
  expect([...single.threads]).toEqual([
    ["p", { date: NOW - 2 * HOUR, pinned: true, single: true }],
  ]);
  expect(single.bundles.size).toBe(0);
  const inside = openPlaces(sections, "L", "g2");
  expect([...inside.threads]).toEqual([
    ["g2", { date: NOW - 4 * HOUR, pinned: false, single: false }],
  ]);
  expect([...inside.bundles]).toEqual([["L", NOW - 3 * HOUR]]);
  expect(inside.pins.size).toBe(0);
});

test("the focused thread not open holds only whether it is pinned now", () => {
  const rows = [
    row("a", 1),
    row("p", 2, ["INBOX", "STARRED"]),
    row("g1", 3, ["INBOX", "L"]),
    row("g2", 4, ["INBOX", "L"]),
  ];
  const sections = inboxSections(rows, NOW, ["L"]);
  const pinned = openPlaces(sections, null, null, "p");
  expect(pinned.threads.size).toBe(0);
  expect([...pinned.pins]).toEqual([["p", true]]);
  expect([...openPlaces(sections, "L", null, "g2").pins]).toEqual([
    ["g2", false],
  ]);
  const open = openPlaces(sections, null, "a", "a");
  expect([...open.threads.keys()]).toEqual(["a"]);
  expect(open.pins.size).toBe(0);
});

function todo(id: string, hoursAgo: number, fields: Partial<Todo> = {}): Todo {
  return { ...newTodo(id, id, NOW - hoursAgo * HOUR), ...fields };
}

function entryIds(section: SectionModel): string[] {
  return section.entries.map((entry) =>
    entry.kind === "thread"
      ? entry.thread.thread.id
      : entry.kind === "todo"
        ? todoRowKey(entry.todo.id)
        : entry.labelId,
  );
}

test("todos sit among inbox threads by time, pinned ones in Pinned", () => {
  const rows = [
    row("new", 1),
    row("pinned", 30, ["INBOX", "STARRED"]),
    row("old", 20),
  ];
  const todos = [
    todo("between", 2),
    todo("pinned-todo", 5, { pinned: true }),
    todo("yesterday-todo", 21),
  ];
  const sections = inboxSections(rows, NOW, [], undefined, todos);
  expect(sections.map(entryIds)).toEqual([
    ["todo:pinned-todo", "pinned"],
    ["new", "todo:between"],
    ["old", "todo:yesterday-todo"],
  ]);
  expect(doneAllTodos(sections[1]!).map((item) => item.id)).toEqual([
    "between",
  ]);
  expect(doneAllIds(sections[1]!)).toEqual(["new"]);
  expect(focusableKeys(listOf(sections))).toEqual([
    "todo:pinned-todo",
    "pinned",
    "new",
    "todo:between",
    "old",
    "todo:yesterday-todo",
  ]);
  expect(openPlaces(sections, null, "new").threads.size).toBe(1);
});

test("todos with the same time keep their keys' order among threads, whatever order they come in", () => {
  const first = todo("first", 2);
  const next = todo("next", 0, {
    position: positionAbove(ownKey(first), "next"),
  });
  const later = todo("later", 1);
  const rows = [row("mail", 3)];
  for (const todos of [
    [next, first, later],
    [first, later, next],
  ]) {
    expect(
      inboxSections(rows, NOW, [], undefined, todos).map(entryIds),
    ).toEqual([["todo:later", "todo:next", "todo:first", "mail"]]);
    expect(plainSections(rows, NOW, undefined, todos).map(entryIds)).toEqual([
      ["todo:later", "todo:next", "todo:first", "mail"],
    ]);
  }
});

test("snoozed todos wake in order with threads, done ones by when they were done", () => {
  const snoozedRow = { ...row("later", 1), snoozeUntil: NOW + 3 * HOUR };
  const soon = todo("soon", 1, { snoozeUntil: epochToIso(NOW + HOUR) });
  expect(snoozedSections([snoozedRow], NOW, [soon]).map(entryIds)).toEqual([
    ["todo:soon", "later"],
  ]);
  const done = todo("done", 2, { state: "DONE" });
  expect(
    plainSections([row("mail", 1), row("older", 3)], NOW, undefined, [
      done,
    ]).map(entryIds),
  ).toEqual([["mail", "todo:done", "older"]]);
});

test("a search's todos of every kind sit among its threads by their list date", () => {
  const active = todo("active", 2);
  const done = todo("done", 4, { state: "DONE" });
  const woken = todo("woken", 30, { snoozeUntil: epochToIso(NOW - 3 * HOUR) });
  expect(
    plainSections([row("mail", 1), row("older", 5)], NOW, undefined, [
      done,
      woken,
      active,
    ]).map(entryIds),
  ).toEqual([["mail", "todo:active", "todo:woken", "todo:done", "older"]]);
});

test("a row offers Done in the inbox or snoozed, Move to inbox elsewhere, neither in spam", () => {
  expect(rowInboxAction(row("inbox", 1))).toBe("done");
  expect(rowInboxAction({ ...row("asleep", 1, []), status: "snoozed" })).toBe(
    "done",
  );
  expect(rowInboxAction(row("archived", 1, ["Label_1"]))).toBe("inbox");
  expect(rowInboxAction(row("junk", 1, ["SPAM"]))).toBeNull();
});

function emailTodos(...threadIds: string[]): ReadonlyMap<string, EmailTodo> {
  return emailTodosByThread(
    threadIds.map((threadId) => newEmailTodo(threadId, `on ${threadId}`, NOW)),
  );
}

test("an email with a todo keeps a row of its own and no todo row, pinned or not", () => {
  const rows = [
    row("new", 1),
    row("pinned", 30, ["INBOX", "STARRED"]),
    row("g1", 2, ["INBOX", "L"]),
    row("g2", 3, ["INBOX", "L"]),
    row("g3", 4, ["INBOX", "L"]),
  ];
  const sections = inboxSections(
    rows,
    NOW,
    ["L"],
    undefined,
    [todo("own", 8)],
    undefined,
    emailTodos("pinned", "g1", "archived"),
  );
  expect(sections.map(entryIds)).toEqual([
    ["pinned"],
    ["new", "g1", "L", "todo:own"],
  ]);
});

test("Done all takes only the todos of their own", () => {
  const [section] = inboxSections(
    [row("a", 1), row("b", 3)],
    NOW,
    [],
    undefined,
    [todo("loose", 2), todo("older", 4)],
    undefined,
    emailTodos("a"),
  );
  expect(doneAllTodos(section!).map((item) => item.id)).toEqual([
    "loose",
    "older",
  ]);
});

test("focus mode keeps the focused thread, unpinned, in Pinned while its pinning is held", () => {
  const rows = [row("unpinned", 1), row("other", 2)];
  const held: HeldPlaces = {
    threads: new Map(),
    bundles: new Map(),
    pins: new Map([["unpinned", true]]),
  };
  const { sections, hidden } = focusInboxSections(
    inboxSections(rows, NOW, [], held),
    null,
    held,
  );
  expect(sections.map((section) => section.key)).toEqual(["pinned"]);
  expect(sections.map(entryIds)).toEqual([["unpinned"]]);
  expect(hidden).toBe(1);
});

test("each thread shows in one place, however many lists name it", () => {
  const rows = [
    row("a", 1),
    row("pinned", 2, ["INBOX", "STARRED", "L"]),
    row("g1", 3, ["INBOX", "L"]),
    row("g1", 30, ["INBOX", "L"]),
    row("g2", 4, ["INBOX", "L"]),
    row("g3", 5, ["INBOX", "L"]),
    row("held", 6, ["INBOX", "L"]),
  ];
  const held: HeldPlaces = {
    threads: new Map([
      ["held", { date: NOW - 6 * HOUR, pinned: false, single: true }],
    ]),
    bundles: new Map(),
    pins: new Map(),
  };
  const sections = inboxSections(
    rows,
    NOW,
    ["L"],
    held,
    [],
    undefined,
    emailTodos("a"),
  );
  const list = listOf(
    sections,
    { type: "tap", key: bundleRowKey("L") },
    { type: "openThread", id: "held" },
  );
  const threadRows = list.rows.flatMap((listed) =>
    listed.kind === "thread" ? [listed.id] : [],
  );
  expect(threadRows.sort()).toEqual(["a", "held", "pinned"]);
  expect(
    list.rows
      .filter((listed) => listed.kind === "todo")
      .map((listed) => listed.key),
  ).toEqual([]);
  const bundle = list.rows.find((listed) => listed.kind === "bundle");
  expect(
    bundle?.kind === "bundle" ? bundle.threads.map((thread) => thread.id) : [],
  ).toEqual(["g1", "g2", "g3"]);
});

test("a refresh merged into a bundle's loaded pages keeps the open bundle's rows, focus and place", () => {
  const rows = Array.from({ length: 30 }, (_, index) =>
    row(`g${index}`, 1 + index, ["INBOX", "L"]),
  );
  const held: HeldPlaces = {
    threads: new Map(),
    bundles: new Map([["L", NOW - HOUR]]),
    pins: new Map(),
  };
  const paging = (more: boolean) => () => ({ more, loading: false });
  let list = listStep(emptyListState<Row, Todo>(), {
    type: "layout",
    sections: layoutSections(
      inboxSections(rows, NOW, ["L"], held),
      paging(true),
    ),
  });
  for (const event of [
    { type: "tap", key: bundleRowKey("L") },
    { type: "showAll" },
    { type: "focus", key: "g20", by: "pointer" },
    { type: "anchor", key: "g18", offset: 120 },
  ] as ListEvent<Row, Todo>[]) {
    list = listStep(list, event);
  }
  const before = list;
  // The data layer merges a refreshed first page into the pages already loaded.
  const merged = mergeFirstPage(
    rows.map((listed) => listed.thread.id),
    rows.slice(0, 10).map((listed) => listed.thread.id),
  );
  list = listStep(list, {
    type: "layout",
    sections: layoutSections(
      inboxSections(
        rows.filter((listed) => merged!.includes(listed.thread.id)),
        NOW,
        ["L"],
        held,
      ),
      paging(true),
    ),
  });
  expect(listProblems(list)).toEqual([]);
  expect(list.rows.map((listed) => listed.key)).toEqual(
    before.rows.map((listed) => listed.key),
  );
  expect(list.focus).toBe("g20");
  expect(list.reveal).toBe(before.reveal);
  expect(list.anchor).toEqual({ key: "g18", offset: 120 });
  expect(list.bundle?.labelId).toBe("L");
});

test("a joined row's todo is that of the first of its threads with one, lead first", () => {
  const joined: Row = { ...row("lead", 2), members: ["lead", "reply"] };
  expect(rowTodo(emailTodos("reply"), joined)?.threadId).toBe("reply");
  expect(rowTodo(emailTodos("reply", "lead"), joined)?.threadId).toBe("lead");
  expect(rowTodo(emailTodos("other"), joined)).toBeNull();
  expect(rowTodo(emailTodos("lead"), row("lead", 2))?.threadId).toBe("lead");
});
