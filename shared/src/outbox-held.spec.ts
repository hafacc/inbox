import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { HELD_SEND_STATUS, heldSendIds, type QueuedSend } from "./outbox-held";

type Fixtures = {
  cases: {
    name: string;
    sends: QueuedSend[];
    docIds: string[];
    expect: string[];
  }[];
  status: string;
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/outbox-held-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("heldSendIds (must match Android)", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      expect(heldSendIds(fixture.sends, fixture.docIds)).toEqual(
        fixture.expect,
      );
    });
  }
});

test("a held send's status reads the same on both apps", () => {
  expect(HELD_SEND_STATUS).toBe(fixtures.status);
});
