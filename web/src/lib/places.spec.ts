import { describe, expect, test } from "bun:test";
import {
  type Place,
  placeFromUrl,
  placeStep,
  placeUrl,
  threadCloseStep,
} from "./places";

const BASE = new URL("https://inbox.hafa.cc/");
const inbox: Place = { view: { kind: "inbox" }, thread: null };
const done: Place = { view: { kind: "done" }, thread: null };

describe("place addresses", () => {
  const places: [Place, string][] = [
    [inbox, "https://inbox.hafa.cc/"],
    [done, "https://inbox.hafa.cc/?view=done"],
    [
      { view: { kind: "label", labelId: "Label_7" }, thread: "t1" },
      "https://inbox.hafa.cc/?label=Label_7&thread=t1",
    ],
    [
      {
        view: { kind: "search", query: "from:ann has:attachment" },
        thread: null,
      },
      "https://inbox.hafa.cc/?q=from%3Aann+has%3Aattachment",
    ],
    [
      { view: { kind: "settings", section: "filters" }, thread: null },
      "https://inbox.hafa.cc/?settings=filters",
    ],
    [
      { view: { kind: "inbox" }, thread: "abc" },
      "https://inbox.hafa.cc/?thread=abc",
    ],
  ];
  for (const [place, href] of places) {
    test(href, () => {
      expect(placeUrl(place, BASE).href).toBe(href);
      expect(placeFromUrl(new URL(href)) ?? inbox).toEqual(place);
    });
  }

  test("a new place drops the old one's parameters and keeps others", () => {
    const from = new URL("https://inbox.hafa.cc/?label=L&thread=t&utm=x");
    expect(placeUrl(done, from).href).toBe(
      "https://inbox.hafa.cc/?utm=x&view=done",
    );
  });

  test("unknown views and pages name no place", () => {
    expect(placeFromUrl(new URL("https://inbox.hafa.cc/?view=nope"))).toBe(
      null,
    );
    expect(placeFromUrl(new URL("https://inbox.hafa.cc/?settings=x"))).toBe(
      null,
    );
    expect(placeFromUrl(BASE)).toBe(null);
  });
});

describe("placeStep", () => {
  const thread: Place = { view: { kind: "inbox" }, thread: "a" };
  const other: Place = { view: { kind: "inbox" }, thread: "b" };
  const general: Place = {
    view: { kind: "settings", section: "general" },
    thread: null,
  };
  const labels: Place = {
    view: { kind: "settings", section: "labels" },
    thread: null,
  };

  test("the same place writes nothing", () => {
    expect(placeStep(null, inbox, inbox)).toBe("none");
  });
  test("opening a thread adds an entry; closing it goes back", () => {
    expect(placeStep(null, inbox, thread)).toBe("push");
    expect(placeStep(inbox, thread, inbox)).toBe("back");
  });
  test("another thread in the same view rewrites the entry", () => {
    expect(placeStep(inbox, thread, other)).toBe("replace");
  });
  test("closing a thread opened from the address rewrites the entry", () => {
    expect(placeStep(null, thread, inbox)).toBe("replace");
  });
  test("another view adds an entry, and returning to the one before goes back", () => {
    expect(placeStep(null, inbox, done)).toBe("push");
    expect(placeStep(inbox, done, inbox)).toBe("back");
    expect(placeStep(null, done, inbox)).toBe("push");
  });
  test("settings pages stack, as on Android", () => {
    expect(placeStep(inbox, general, labels)).toBe("push");
    expect(placeStep(general, labels, general)).toBe("back");
  });
  test("one search rewrites another, as on Android", () => {
    const first: Place = { view: { kind: "search", query: "a" }, thread: null };
    const second: Place = {
      view: { kind: "search", query: "b" },
      thread: null,
    };
    expect(placeStep(inbox, first, second)).toBe("replace");
  });
});

describe("threadCloseStep", () => {
  const thread: Place = { view: { kind: "inbox" }, thread: "a" };
  const label: Place = { view: { kind: "label", labelId: "L" }, thread: null };

  test("another view from an open thread goes back past the thread first", () => {
    expect(threadCloseStep(inbox, thread, label)).toBe("back");
  });
  test("a thread opened from the address is rewritten to its view first", () => {
    expect(threadCloseStep(null, thread, label)).toBe("replace");
  });
  test("nothing to close without a thread, or in the same view", () => {
    expect(threadCloseStep(null, inbox, label)).toBe(null);
    expect(threadCloseStep(inbox, thread, inbox)).toBe(null);
  });
});
