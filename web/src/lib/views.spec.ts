import { expect, test } from "bun:test";
import {
  DONE_QUERY,
  filteredView,
  listOptions,
  sameView,
  searchLabelName,
  searchOriginAfter,
  type View,
  viewKey,
} from "./views";

test("each view has its own key and Gmail query", () => {
  expect(viewKey({ kind: "label", labelId: "Label_1" })).toBe("label:Label_1");
  expect(sameView({ kind: "inbox" }, { kind: "inbox" })).toBe(true);
  expect(sameView({ kind: "inbox" }, { kind: "done" })).toBe(false);
  expect(listOptions({ kind: "inbox" })).toEqual({ labelIds: ["INBOX"] });
  expect(listOptions({ kind: "snoozed" })).toBeNull();
  expect(listOptions({ kind: "done" })).toEqual({ q: DONE_QUERY });
  expect(DONE_QUERY).toBe("-in:inbox -in:spam -in:trash -in:sent -in:draft");
  expect(listOptions({ kind: "spam" })).toEqual({
    labelIds: ["SPAM"],
    includeSpamTrash: true,
  });
  expect(listOptions({ kind: "search", query: "from:maya" })).toEqual({
    q: "from:maya",
  });
  expect(listOptions({ kind: "search", query: "is:snoozed lease" })).toEqual({
    q: "lease",
  });
  expect(listOptions({ kind: "search", query: "is:snoozed" })).toBeNull();
  expect(listOptions({ kind: "search", query: "is:todo milk" })).toBeNull();
  expect(listOptions({ kind: "search", query: "-is:todo" })).toEqual({
    q: "",
  });
  expect(listOptions({ kind: "search", query: "in:spam lottery" })).toEqual({
    q: "in:spam lottery",
    includeSpamTrash: true,
  });
  expect(listOptions({ kind: "settings", section: "filters" })).toBeNull();
  expect(viewKey({ kind: "settings", section: "filters" })).toBe(
    "settings:filters",
  );
});

test("each view of threads maps to its filter's view, others to none", () => {
  expect(filteredView({ kind: "inbox" })).toEqual({ kind: "inbox" });
  expect(filteredView({ kind: "label", labelId: "Label_1" })).toEqual({
    kind: "label",
    labelId: "Label_1",
  });
  expect(filteredView({ kind: "search", query: "lease" })).toEqual({
    kind: "search",
    query: "lease",
  });
  expect(filteredView({ kind: "todos" })).toBeNull();
  expect(filteredView({ kind: "outbox" })).toBeNull();
});

test("a label's search name is lower case with dashes for spaces and slashes", () => {
  expect(searchLabelName("Work/Big Project")).toBe("work-big-project");
});

test("leaving search returns to the view it was started from", () => {
  const label: View = { kind: "label", labelId: "Label_1" };
  const first: View = { kind: "search", query: "from:maya" };
  const second: View = { kind: "search", query: "lease" };
  let origin: View = { kind: "inbox" };
  origin = searchOriginAfter(label, first, origin);
  expect(origin).toEqual(label);
  origin = searchOriginAfter(first, second, origin);
  expect(origin).toEqual(label);
  origin = searchOriginAfter(second, { kind: "done" }, origin);
  expect(origin).toEqual(label);
  expect(searchOriginAfter({ kind: "done" }, first, origin)).toEqual({
    kind: "done",
  });
});
