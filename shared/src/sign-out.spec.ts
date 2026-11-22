import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { signOutWarning } from "./sign-out";

type Case = {
  name: string;
  changes: number;
  drafts: number;
  expect: string | null;
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/sign-out-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { warning: Case[] };

describe("signOutWarning", () => {
  for (const entry of fixtures.warning) {
    test(entry.name, () => {
      expect(signOutWarning(entry.changes, entry.drafts)).toBe(entry.expect);
    });
  }
});
