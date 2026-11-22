import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type OpeningMessage,
  type ThreadOpenState,
  threadOpenState,
} from "./thread-open";

type Fixtures = {
  open: {
    name: string;
    messages: OpeningMessage[];
    expect: ThreadOpenState;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/thread-open-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("threadOpenState", () => {
  for (const fixture of fixtures.open) {
    test(fixture.name, () => {
      expect(threadOpenState(fixture.messages)).toEqual(fixture.expect);
    });
  }
});
