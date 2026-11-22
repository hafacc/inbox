import { expect, test } from "bun:test";
import { groupByLabel, type ListEntry } from "./label-groups";

type Thread = { id: string; labels: string[] };

function thread(id: string, ...labels: string[]): Thread {
  return { id, labels };
}

function shape(entries: ListEntry<Thread>[]): string[] {
  return entries.map((entry) =>
    entry.kind === "thread"
      ? entry.thread.id
      : `${entry.labelId}[${entry.threads.map((member) => member.id).join(",")}]`,
  );
}

const labelsOf = (item: Thread) => item.labels;

test("three threads with a user label fold into one group where the newest was", () => {
  const threads = [
    thread("a"),
    thread("b", "github"),
    thread("c"),
    thread("d", "github"),
    thread("e", "github", "INBOX"),
  ];
  expect(shape(groupByLabel(threads, labelsOf, ["github"]))).toEqual([
    "a",
    "github[b,d,e]",
    "c",
  ]);
});

test("one thread stays a row, two fold", () => {
  expect(
    shape(groupByLabel([thread("a", "receipts")], labelsOf, ["receipts"])),
  ).toEqual(["a"]);
  const threads = [thread("a", "receipts"), thread("b", "receipts")];
  expect(shape(groupByLabel(threads, labelsOf, ["receipts"]))).toEqual([
    "receipts[a,b]",
  ]);
});

test("a thread with two labels joins the larger group; ties go to the earlier label", () => {
  const threads = [
    thread("a", "family", "home"),
    thread("b", "home"),
    thread("c", "home"),
    thread("d", "family"),
    thread("f", "home"),
  ];
  expect(shape(groupByLabel(threads, labelsOf, ["family", "home"]))).toEqual([
    "home[a,b,c,f]",
    "d",
  ]);
  const tied = [
    thread("a", "home", "family"),
    thread("b", "home"),
    thread("d", "family"),
  ];
  expect(shape(groupByLabel(tied, labelsOf, ["family", "home"]))).toEqual([
    "family[a,d]",
    "b",
  ]);
});

test("a label left too small after assignment gives its threads back and others regroup", () => {
  // "big" takes a, b and c; "small" keeps only d, so it stays a row.
  const threads = [
    thread("a", "big", "small"),
    thread("b", "big"),
    thread("c", "big", "small"),
    thread("d", "small"),
    thread("f", "big"),
  ];
  expect(shape(groupByLabel(threads, labelsOf, ["big", "small"]))).toEqual([
    "big[a,b,c,f]",
    "d",
  ]);
});

test("system labels never group", () => {
  const threads = [
    thread("a", "INBOX"),
    thread("b", "INBOX"),
    thread("c", "INBOX"),
  ];
  expect(shape(groupByLabel(threads, labelsOf, ["github"]))).toEqual([
    "a",
    "b",
    "c",
  ]);
});
