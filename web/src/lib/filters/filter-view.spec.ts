import { expect, test } from "bun:test";
import { editorToFilter } from "@inbox/shared/filters";
import {
  editorForSender,
  editorFromSearch,
  filterSummary,
  newFilterEditor,
  sameFilter,
  withField,
} from "./filter-view";

const labels = [{ id: "Label_7", name: "Receipts", type: "user" as const }];

test("summarizes a simple filter", () => {
  const summary = filterSummary(
    {
      id: "1",
      criteria: { from: "notifications@github.com" },
      action: { addLabelIds: ["Label_7"], removeLabelIds: ["INBOX"] },
    },
    labels,
  );
  expect(summary.parts).toEqual([
    { text: "From", value: false },
    { text: "notifications@github.com", value: true },
  ]);
  expect(summary.label?.name).toBe("Receipts");
  expect(summary.actions).toEqual(["Skip inbox"]);
  expect(summary.search).toBe("from:notifications@github.com");
});

test("summarizes an any group of one field with OR", () => {
  const summary = filterSummary(
    { criteria: { query: "{subject:receipt subject:order}" }, action: {} },
    labels,
  );
  expect(summary.parts.map((part) => part.text)).toEqual([
    "Subject has",
    "receipt",
    "OR",
    "order",
  ]);
});

test("falls back to the search for raw filters", () => {
  const summary = filterSummary({ criteria: { query: "{a {b c}}" } }, labels);
  expect(summary.parts).toEqual([
    { text: "Matches", value: false },
    { text: "{a {b c}}", value: true },
  ]);
});

test("starting points for the editor", () => {
  expect(newFilterEditor().conditions).toEqual({
    kind: "rows",
    groups: [
      {
        match: "all",
        rows: [{ field: "from", operator: "contains", value: "" }],
      },
    ],
  });
  expect(editorToFilter(editorForSender("news@x.example.com"))).toEqual({
    criteria: { from: "news@x.example.com" },
    action: { removeLabelIds: ["INBOX"] },
  });
  expect(editorFromSearch("from:sam lease").conditions.kind).toBe("rows");
});

test("changing a field keeps text and picks a valid operator", () => {
  expect(
    withField({ field: "from", operator: "contains", value: "x" }, "subject"),
  ).toEqual({
    field: "subject",
    operator: "contains",
    value: "x",
  });
  expect(
    withField({ field: "from", operator: "contains", value: "x" }, "size"),
  ).toEqual({
    field: "size",
    operator: "larger",
    value: "",
  });
});

test("a filter Gmail lists in its own field and label order is the same filter", () => {
  const made = {
    criteria: { from: "a@b.c", query: "invoice" },
    action: { addLabelIds: ["Label_7", "STARRED"], removeLabelIds: ["INBOX"] },
  };
  const listed = {
    id: "f1",
    action: { removeLabelIds: ["INBOX"], addLabelIds: ["STARRED", "Label_7"] },
    criteria: { query: "invoice", from: "a@b.c" },
  };
  expect(sameFilter(listed, made)).toBe(true);
  expect(
    sameFilter(listed, { ...made, action: { addLabelIds: ["Label_7"] } }),
  ).toBe(false);
});
