import { expect, test } from "bun:test";
import {
  bundleFooter,
  emptyListState,
  type ListLoad,
  missingThreadIds,
  pagesOnItsOwn,
} from "./list-status";

const LOADED: ListLoad = {
  ids: [],
  nextPageToken: null,
  loading: false,
  loaded: true,
  error: null,
  moreError: null,
};

test("an empty list shows a placeholder only while a load can still bring rows", () => {
  expect(emptyListState({ ...LOADED, loaded: false })).toBe("loading");
  expect(emptyListState({ ...LOADED, loading: true })).toBe("loading");
  expect(emptyListState({ ...LOADED, nextPageToken: "next" })).toBe("loading");
  expect(emptyListState(LOADED)).toBe("empty");
  expect(emptyListState({ ...LOADED, ids: ["snoozed"], loading: true })).toBe(
    "empty",
  );
});

test("a failed load shows its error, never the placeholder", () => {
  expect(emptyListState({ ...LOADED, error: "Gmail said: no" })).toBe("error");
  expect(
    emptyListState({
      ...LOADED,
      nextPageToken: "next",
      moreError: "Gmail didn't answer in time.",
    }),
  ).toBe("error");
  expect(
    emptyListState({ ...LOADED, loading: true, error: "You're offline." }),
  ).toBe("error");
});

test("an open bundle's foot: the loaded rest, then fetching, its failure, or the button", () => {
  expect(bundleFooter(3, true, true, "failed")).toBe("all");
  expect(bundleFooter(0, false, false, null)).toBeNull();
  expect(bundleFooter(0, true, true, null)).toBe("loading");
  expect(bundleFooter(0, true, false, "Couldn't load more.")).toBe("retry");
  expect(bundleFooter(0, true, false, null)).toBe("more");
});

test("a list pages on its own only while nothing failed, nothing runs and Gmail has more", () => {
  const more: ListLoad = { ...LOADED, ids: ["a"], nextPageToken: "next" };
  expect(pagesOnItsOwn(more, true)).toBe(true);
  expect(pagesOnItsOwn(more, false)).toBe(false);
  expect(pagesOnItsOwn({ ...more, loading: true }, true)).toBe(false);
  expect(pagesOnItsOwn({ ...more, nextPageToken: null }, true)).toBe(false);
  expect(
    pagesOnItsOwn({ ...more, moreError: "Gmail asked us to slow down." }, true),
  ).toBe(false);
});

test("Retry reads again the listed threads with no row, not those Gmail says are gone", () => {
  const known = new Set(["read"]);
  expect(
    missingThreadIds(
      ["read", "failed", "deleted"],
      (id) => known.has(id),
      new Set(["deleted"]),
    ),
  ).toEqual(["failed"]);
});
