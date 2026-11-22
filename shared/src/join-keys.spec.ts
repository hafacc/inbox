import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { type JoinKeyMessage, type JoinKeys, joinKeys } from "./join-keys";

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/join-key-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  keys: {
    name: string;
    hiddenSubject: boolean;
    messages: JoinKeyMessage[];
    expect: JoinKeys;
  }[];
};

describe("keys", () => {
  for (const fixture of fixtures.keys) {
    test(fixture.name, () => {
      expect(joinKeys(fixture.messages, fixture.hiddenSubject)).toEqual(
        fixture.expect,
      );
    });
  }
});
