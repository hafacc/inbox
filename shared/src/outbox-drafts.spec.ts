import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  draftThreadCount,
  type GmailDraftRef,
  type OutboxDraftDoc,
  onlyInOutbox,
  outboxDrafts,
  type SendingDraft,
} from "./outbox-drafts";

type Fixtures = {
  cases: {
    name: string;
    drafts: GmailDraftRef[];
    docs: OutboxDraftDoc[];
    sending?: SendingDraft[];
    listedFor?: string[];
    labelThreads: number;
    expect: { messageIds: string[]; threadIds: string[]; count: number };
  }[];
  listed: {
    name: string;
    drafts: GmailDraftRef[];
    docs: OutboxDraftDoc[];
    sending?: SendingDraft[];
    listedFor?: string[];
    threadId: string;
    sentOrReceived: number;
    expect: boolean;
  }[];
};

function outboxOf(fixture: {
  drafts: GmailDraftRef[];
  docs: OutboxDraftDoc[];
  sending?: SendingDraft[];
  listedFor?: string[];
}) {
  return outboxDrafts(
    fixture.drafts,
    fixture.docs,
    fixture.sending ?? [],
    new Set(fixture.listedFor ?? []),
  );
}

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/outbox-draft-fixtures.json", import.meta.url),
    "utf8",
  ),
);

describe("outboxDrafts (must match Android)", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      const outbox = outboxOf(fixture);
      expect({
        messageIds: [...outbox.messageIds].sort(),
        threadIds: [...outbox.threadIds].sort(),
        count: draftThreadCount(fixture.labelThreads, outbox),
      }).toEqual(fixture.expect);
    });
  }
});

describe("onlyInOutbox (must match Android)", () => {
  for (const fixture of fixtures.listed) {
    test(fixture.name, () => {
      const outbox = outboxOf(fixture);
      expect(
        onlyInOutbox(fixture.threadId, fixture.sentOrReceived, outbox),
      ).toBe(fixture.expect);
    });
  }
});
