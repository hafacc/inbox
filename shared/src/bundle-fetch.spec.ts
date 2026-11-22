import { describe, expect, test } from "bun:test";
import {
  BUNDLE_FIRST_PAGE,
  BUNDLE_PAGE_SIZE,
  bundlesToFetch,
  bundlesToRemember,
  inboxExclusion,
  inboxNextPage,
  inboxThreadIds,
  mergeFirstPage,
  PINNED_LABEL,
  PINNED_PAGE,
  type QueryLabel,
} from "./bundles";

// The Gmail calls the inbox and its bundles make, played against a fake mailbox the way the
// apps make them: the inbox's pages of 25 load until the screen holds a dozen rows, each bundle
// label they show (or the device remembers from last session) fetches its own first page before
// the next inbox page, inbox pages leave out bundles whose list came back, and once they leave any
// out the inbox's pinned threads come from their own list. Android's :core runs the same calls
// (BundleFetchTest), so both apps keep to this budget.

const INBOX_PAGE = 25;
const SCREEN_ROWS = 12;
const MIN_GROUP = 3;

const LABELS: QueryLabel[] = [
  { id: "CATEGORY_PROMOTIONS", name: "Promotions" },
  { id: "Label_gh", name: "GitHub" },
];

type Thread = { id: string; labels: string[] };

// A GitHub thread pinned far below the bundle's first ten.
const DEEP_PINNED = "t282";

// 300 inbox threads, newest first: most promotions, some GitHub (or none), a few plain.
function mailbox(github = true): Thread[] {
  return Array.from({ length: 300 }, (_, index) => {
    const slot = index % 20;
    const labels =
      slot < 2
        ? ["INBOX"]
        : slot === 2 && github
          ? ["INBOX", "Label_gh"]
          : ["INBOX", "CATEGORY_PROMOTIONS"];
    const id = `t${index}`;
    if (id === DEEP_PINNED && github) labels.push(PINNED_LABEL);
    return { id, labels };
  });
}

type ListCall = {
  labelIds: string[];
  q: string;
  max: number;
  token: string | null;
};

class FakeGmail {
  lists = 0;
  reads = 0;
  // Labels whose own list Gmail fails.
  readonly failing = new Set<string>();
  readonly #threads: Thread[];
  constructor(threads: Thread[]) {
    this.#threads = threads;
  }

  list(call: ListCall): { ids: string[]; next: string | null } {
    this.lists++;
    if (call.labelIds.some((label) => this.failing.has(label))) {
      throw new Error("503");
    }
    const left = call.q.split(" ").filter((term) => term !== "");
    const matching = this.#threads.filter(
      (thread) =>
        call.labelIds.every((label) => thread.labels.includes(label)) &&
        !left.some((term) =>
          thread.labels.some((label) =>
            inboxExclusion(LABELS.filter((entry) => entry.id === label))
              .split(" ")
              .includes(term),
          ),
        ),
    );
    let start = 0;
    if (call.token !== null) {
      const [query, offset] = call.token.split("|");
      expect(query).toBe(call.q);
      start = Number(offset);
    }
    const end = start + call.max;
    return {
      ids: matching.slice(start, end).map((thread) => thread.id),
      next: end < matching.length ? `${call.q}|${end}` : null,
    };
  }

  labelsOf(id: string): string[] {
    return this.#threads.find((thread) => thread.id === id)?.labels ?? [];
  }
}

type BundleState = { ids: string[]; next: string | null; paged: boolean };

class Client {
  readonly gmail: FakeGmail;
  readonly summaries = new Set<string>();
  inbox: string[] = [];
  inboxNext: string | null = null;
  tokenQuery = "";
  pinned: string[] = [];
  pinnedFresh = false;
  // Bundle lists asked for this session; only those that came back leave the inbox.
  readonly requested = new Set<string>();
  readonly bundles = new Map<string, BundleState>();
  readonly stale = new Set<string>();
  readonly #remembered: ReadonlySet<string>;

  constructor(
    gmail: FakeGmail,
    cached: Iterable<string> = [],
    remembered: Iterable<string> = [],
  ) {
    this.gmail = gmail;
    for (const id of cached) this.summaries.add(id);
    this.#remembered = new Set(remembered);
  }

  // What the device keeps for the next session.
  remember(): string[] {
    return bundlesToRemember(
      [...this.bundles].map(([labelId, bundle]) => ({
        labelId,
        ids: bundle.ids,
        more: bundle.next !== null,
      })),
    );
  }

  #read(ids: readonly string[]): void {
    for (const id of ids) {
      if (!this.summaries.has(id)) {
        this.gmail.reads++;
        this.summaries.add(id);
      }
    }
  }

  #fetchDueBundles(): void {
    const labels = this.inbox.map((id) => this.gmail.labelsOf(id));
    const due = bundlesToFetch(
      LABELS.map((label) => label.id),
      labels,
      this.requested,
      this.#remembered,
    );
    for (const labelId of due) {
      this.requested.add(labelId);
      this.loadBundle(labelId);
    }
  }

  exclusion(): string {
    return inboxExclusion(
      LABELS.filter((label) => this.bundles.has(label.id)),
      LABELS,
    );
  }

  // A bundle's first page (a paged one keeps its later pages), or with `more` its next page.
  loadBundle(labelId: string, more = false): boolean {
    const current = this.bundles.get(labelId);
    let page: { ids: string[]; next: string | null };
    try {
      page = this.gmail.list({
        labelIds: ["INBOX", labelId],
        q: "",
        max: more ? BUNDLE_PAGE_SIZE : BUNDLE_FIRST_PAGE,
        token: more ? (current?.next ?? null) : null,
      });
    } catch {
      this.stale.add(labelId);
      return false;
    }
    this.stale.delete(labelId);
    this.#read(page.ids);
    const merged =
      !more && current?.paged && page.next !== null
        ? mergeFirstPage(current.ids, page.ids)
        : null;
    this.bundles.set(labelId, {
      ids: more ? [...(current?.ids ?? []), ...page.ids] : (merged ?? page.ids),
      next: merged !== null ? (current?.next ?? null) : page.next,
      paged: more || merged !== null,
    });
    return true;
  }

  loadInbox(more = false): void {
    this.#fetchDueBundles();
    const query = this.exclusion();
    const request = more
      ? inboxNextPage(query, this.inboxNext, this.tokenQuery)
      : { q: query, pageToken: null };
    if (!more) this.pinnedFresh = false;
    if (query !== "" && !this.pinnedFresh) {
      this.pinned = this.gmail.list({
        labelIds: ["INBOX", PINNED_LABEL],
        q: "",
        max: PINNED_PAGE,
        token: null,
      }).ids;
      this.#read(this.pinned);
      this.pinnedFresh = true;
    }
    const page = this.gmail.list({
      labelIds: ["INBOX"],
      q: request.q,
      max: INBOX_PAGE,
      token: request.pageToken,
    });
    this.#read(page.ids);
    this.inbox = more ? [...new Set([...this.inbox, ...page.ids])] : page.ids;
    this.inboxNext = page.next;
    this.tokenQuery = request.q;
    this.#fetchDueBundles();
  }

  // A refresh after history touched bundles: their first page again, failed ones retried.
  refresh(touched: readonly string[]): void {
    for (const labelId of touched) this.stale.add(labelId);
    this.loadInbox();
    for (const labelId of [...this.stale]) this.loadBundle(labelId);
  }

  ids(): string[] {
    return inboxThreadIds(
      this.inbox,
      [
        ...[...this.bundles].map(([labelId, bundle]) => ({
          labelId,
          ids: bundle.ids,
          more: bundle.next !== null,
        })),
        { labelId: PINNED_LABEL, ids: this.pinned, more: false },
      ],
      (id) => this.gmail.labelsOf(id),
    );
  }

  rows(): number {
    const perLabel = new Map<string, number>();
    let singles = 0;
    for (const id of this.ids()) {
      const labels = this.gmail.labelsOf(id);
      const label = labels.find((entry) =>
        LABELS.some((known) => known.id === entry),
      );
      if (label === undefined || labels.includes(PINNED_LABEL)) singles++;
      else perLabel.set(label, (perLabel.get(label) ?? 0) + 1);
    }
    let rows = singles;
    for (const count of perLabel.values()) {
      rows += count >= MIN_GROUP ? 1 : count;
    }
    return rows;
  }

  // The list's end stays in view, so pages load until the screen is full.
  fillScreen(): void {
    this.loadInbox();
    while (this.rows() < SCREEN_ROWS && this.inboxNext !== null) {
      this.loadInbox(true);
    }
  }

  // A bundle opened: its next page, once.
  open(labelId: string): void {
    const bundle = this.bundles.get(labelId);
    if (bundle && !bundle.paged && bundle.next !== null) {
      this.loadBundle(labelId, true);
    }
  }

  // Done all on a bundle Gmail has more of: every unpinned inbox thread with its label, by id.
  doneAllIds(labelId: string): string[] {
    const every = (labelIds: string[]): string[] => {
      const ids: string[] = [];
      let token: string | null = null;
      do {
        const page = this.gmail.list({ labelIds, q: "", max: 500, token });
        ids.push(...page.ids);
        token = page.next;
      } while (token !== null);
      return ids;
    };
    const pinned = new Set(every(["INBOX", labelId, PINNED_LABEL]));
    return every(["INBOX", labelId]).filter((id) => !pinned.has(id));
  }
}

function held(client: Client, labelId: string): number {
  const ids = new Set([
    ...client.inbox.filter((id) => client.gmail.labelsOf(id).includes(labelId)),
    ...(client.bundles.get(labelId)?.ids ?? []),
  ]);
  return ids.size;
}

describe("Gmail calls for the inbox and its bundles", () => {
  test("a cold load lists the inbox twice, each bundle's first ten and the pinned threads", () => {
    const gmail = new FakeGmail(mailbox());
    const client = new Client(gmail);
    client.fillScreen();
    // Inbox page one, a list per bundle, the pinned list, then the inbox again without the bundles.
    expect(gmail.lists).toBe(5);
    expect(gmail.reads).toBe(55);
    expect(held(client, "CATEGORY_PROMOTIONS")).toBe(19);
    expect(held(client, "Label_gh")).toBe(10);
    expect(client.ids()).toContain(DEEP_PINNED);
  });

  test("a warm load leaves remembered bundles out of the first inbox page and reads nothing it has", () => {
    const threads = mailbox();
    const first = new Client(new FakeGmail(threads));
    first.fillScreen();
    expect(first.remember()).toEqual(["CATEGORY_PROMOTIONS", "Label_gh"]);
    const gmail = new FakeGmail(threads);
    const client = new Client(
      gmail,
      threads.map((thread) => thread.id),
      first.remember(),
    );
    client.fillScreen();
    // A list per remembered bundle, the pinned list, then the inbox once, already without them.
    expect(gmail.lists).toBe(4);
    expect(gmail.reads).toBe(0);
    expect(held(client, "CATEGORY_PROMOTIONS")).toBe(10);
    expect(held(client, "Label_gh")).toBe(10);
    expect(client.ids()).toContain(DEEP_PINNED);
  });

  test("a remembered label with no inbox mail left costs one empty list, then is forgotten", () => {
    const threads = mailbox(false);
    const gmail = new FakeGmail(threads);
    const client = new Client(
      gmail,
      threads.map((thread) => thread.id),
      ["CATEGORY_PROMOTIONS", "Label_gh"],
    );
    client.fillScreen();
    expect(gmail.lists).toBe(4);
    expect(client.bundles.get("Label_gh")?.ids).toEqual([]);
    expect(client.remember()).toEqual(["CATEGORY_PROMOTIONS"]);
  });

  test("opening a bundle lists its next 50 once, Show more 50 more", () => {
    const gmail = new FakeGmail(mailbox());
    const client = new Client(gmail);
    client.fillScreen();
    const before = { lists: gmail.lists, reads: gmail.reads };
    client.open("CATEGORY_PROMOTIONS");
    client.open("CATEGORY_PROMOTIONS");
    expect(gmail.lists - before.lists).toBe(1);
    expect(gmail.reads - before.reads).toBeLessThanOrEqual(BUNDLE_PAGE_SIZE);
    expect(client.bundles.get("CATEGORY_PROMOTIONS")?.ids.length).toBe(60);
    client.loadBundle("CATEGORY_PROMOTIONS", true);
    expect(gmail.lists - before.lists).toBe(2);
    expect(client.bundles.get("CATEGORY_PROMOTIONS")?.ids.length).toBe(110);
  });

  test("a failed bundle list leaves its threads in the inbox until a refresh fetches it", () => {
    const gmail = new FakeGmail(mailbox());
    gmail.failing.add("Label_gh");
    const client = new Client(gmail);
    client.fillScreen();
    expect(client.exclusion()).toBe("-category:promotions");
    expect(client.stale).toEqual(new Set(["Label_gh"]));
    expect(held(client, "Label_gh")).toBeGreaterThan(0);
    gmail.failing.clear();
    const before = gmail.lists;
    client.refresh([]);
    // The inbox's first page, the pinned list, and the bundle that failed.
    expect(gmail.lists - before).toBe(3);
    expect(client.stale.size).toBe(0);
    expect(client.exclusion()).toBe("-category:promotions -label:github");
  });

  test("a refresh of an opened bundle lists its first ten and keeps the pages past them", () => {
    const gmail = new FakeGmail(mailbox());
    const client = new Client(gmail);
    client.fillScreen();
    client.open("CATEGORY_PROMOTIONS");
    const before = { lists: gmail.lists, reads: gmail.reads };
    client.refresh(["CATEGORY_PROMOTIONS"]);
    expect(gmail.lists - before.lists).toBe(3);
    expect(gmail.reads - before.reads).toBe(0);
    expect(client.bundles.get("CATEGORY_PROMOTIONS")?.ids.length).toBe(60);
  });

  test("Done all on a bundle Gmail has more of lists its ids without reading a thread", () => {
    const gmail = new FakeGmail(mailbox());
    const client = new Client(gmail);
    client.fillScreen();
    const before = { lists: gmail.lists, reads: gmail.reads };
    const ids = client.doneAllIds("Label_gh");
    expect(gmail.lists - before.lists).toBe(2);
    expect(gmail.reads - before.reads).toBe(0);
    expect(ids.length).toBe(14);
    expect(ids).not.toContain(DEEP_PINNED);
  });
});
