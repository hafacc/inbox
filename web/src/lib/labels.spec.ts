import { expect, test } from "bun:test";
import type { GmailLabel } from "./gmail/types";
import {
  categoryMenuItems,
  labelMenuItems,
  labelTree,
  offersCreate,
} from "./labels";

const labels: GmailLabel[] = [
  { id: "INBOX", name: "INBOX", type: "system" },
  { id: "L3", name: "Home/Bills", type: "user" },
  { id: "L1", name: "home", type: "user" },
  { id: "L2", name: "GitHub", type: "user" },
  { id: "L4", name: "Home/Bills/Water", type: "user" },
];

test("orders labels as a tree", () => {
  expect(
    labelTree(labels).map((node) => [node.label.id, node.depth, node.leaf]),
  ).toEqual([
    ["L2", 0, "GitHub"],
    ["L1", 0, "home"],
    ["L3", 1, "Bills"],
    ["L4", 2, "Water"],
  ]);
});

test("the menu offers to create only a new name", () => {
  expect(offersCreate(labels, "Receipts")).toBe(true);
  expect(offersCreate(labels, "GitHub")).toBe(false);
});

test("the menu filters by any part and marks the thread's labels", () => {
  expect(labelMenuItems(labels, "bill", ["L3"])).toEqual([
    { label: labels[1]!, checked: true },
    { label: labels[4]!, checked: false },
  ]);
});

test("category menu: Primary first, the thread's category checked", () => {
  const items = categoryMenuItems(["INBOX", "CATEGORY_PROMOTIONS"]);
  expect(items.map((item) => item.name)).toEqual([
    "Primary",
    "Promotions",
    "Social",
    "Updates",
    "Forums",
  ]);
  expect(items.filter((item) => item.checked).map((item) => item.id)).toEqual([
    "CATEGORY_PROMOTIONS",
  ]);
  expect(categoryMenuItems(["INBOX"])[0]?.checked).toBe(true);
});
