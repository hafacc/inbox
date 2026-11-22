import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  baseOutboxId,
  copyNumber,
  copyOutboxId,
  isCopyId,
  nextCopyNumbers,
  sendGroup,
} from "./outbox-copies";

type Fixtures = {
  copyIds: { base: string; copy: number; expect: string }[];
  ids: { id: string; copy: number | null; base: string }[];
  groups: {
    name: string;
    ids: string[];
    base: string;
    group: string[];
    count: number;
    next: number[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/outbox-copy-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

for (const fixture of fixtures.copyIds) {
  test(`copyOutboxId: ${fixture.expect} (must match Android)`, () => {
    expect(copyOutboxId(fixture.base, fixture.copy)).toBe(fixture.expect);
  });
}

for (const fixture of fixtures.ids) {
  test(`copyNumber, baseOutboxId: ${fixture.id} (must match Android)`, () => {
    expect(copyNumber(fixture.id)).toBe(fixture.copy);
    expect(isCopyId(fixture.id)).toBe(fixture.copy !== null);
    expect(baseOutboxId(fixture.id)).toBe(fixture.base);
  });
}

for (const fixture of fixtures.groups) {
  test(`sendGroup, nextCopyNumbers: ${fixture.name} (must match Android)`, () => {
    const docs = fixture.ids.map((id) => ({ id }));
    expect(sendGroup(docs, fixture.base).map((doc) => doc.id)).toEqual(
      fixture.group,
    );
    expect(nextCopyNumbers(fixture.ids, fixture.base, fixture.count)).toEqual(
      fixture.next,
    );
  });
}
