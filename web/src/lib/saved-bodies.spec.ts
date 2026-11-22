import { expect, test } from "bun:test";
import { SavedBodies, type SavedBodyStore } from "./saved-bodies";
import type { MessageView, ThreadDetail } from "./thread-detail";

function message(id: string, threadId: string, date: number): MessageView {
  return {
    id,
    threadId,
    from: { name: "Ana", email: "ana@example.com" },
    to: [],
    date,
    snippet: id,
    unread: false,
    body: { html: null, text: id, attachments: [], inline: [], calendar: null },
  };
}

function body(id: string, historyId: string, dates: number[]): ThreadDetail {
  return {
    id,
    historyId,
    subject: id,
    messages: dates.map((date, index) => message(`${id}-m${index}`, id, date)),
    drafts: [],
  };
}

function store(stored: Record<string, ThreadDetail>, saved: string[]) {
  const reads: string[][] = [];
  const source: SavedBodyStore = {
    saved: (id) => saved.includes(id),
    ready: async () => {},
    read: async (ids) => {
      reads.push([...ids]);
      return ids.map((id) => stored[id]);
    },
  };
  return { source, reads };
}

// What opening a row does: the saved copy when it has the row, else Gmail.
async function open(
  bodies: SavedBodies,
  members: string[],
  gmail: string[],
): Promise<ThreadDetail | null> {
  const saved = bodies.peek(members) ?? (await bodies.load(members));
  if (saved === null) gmail.push(...members);
  return saved;
}

test("a saved thread opens from the device without asking Gmail, whatever its history id", async () => {
  const { source, reads } = store({ t1: body("t1", "5", [1, 2]) }, ["t1"]);
  const bodies = new SavedBodies(source);
  const gmail: string[] = [];
  const shown = await open(bodies, ["t1"], gmail);
  expect(shown?.messages.map((entry) => entry.id)).toEqual(["t1-m0", "t1-m1"]);
  expect(gmail).toEqual([]);
  expect(reads).toEqual([["t1"]]);
});

test("a thread read ahead opens from memory in the same step", async () => {
  const { source, reads } = store({ t1: body("t1", "5", [1]) }, ["t1"]);
  const bodies = new SavedBodies(source);
  expect(bodies.peek(["t1"])).toBeNull();
  bodies.prefetch(["t1"]);
  await bodies.load(["t1"]);
  expect(bodies.peek(["t1"])?.id).toBe("t1");
  expect(reads).toEqual([["t1"]]);
});

test("a thread not in the saved copy is read from Gmail", async () => {
  const { source } = store({ t1: body("t1", "5", [1]) }, []);
  const gmail: string[] = [];
  expect(await open(new SavedBodies(source), ["t1"], gmail)).toBeNull();
  expect(gmail).toEqual(["t1"]);
});

test("a joined row shows each saved thread's own messages once, by date", async () => {
  const lead = body("t1", "5", [1, 4]);
  // A body saved for the joined row holds the other thread's message too.
  lead.messages.push(message("t2-m0", "t2", 2));
  const { source } = store({ t1: lead, t2: body("t2", "6", [2]) }, [
    "t1",
    "t2",
  ]);
  const shown = await new SavedBodies(source).load(["t1", "t2"]);
  expect(shown?.messages.map((entry) => entry.id)).toEqual([
    "t1-m0",
    "t2-m0",
    "t1-m1",
  ]);
});

test("a forgotten body is read again; the oldest held go past the limit", async () => {
  const { source, reads } = store(
    {
      t1: body("t1", "5", [1]),
      t2: body("t2", "5", [1]),
      t3: body("t3", "5", [1]),
    },
    ["t1", "t2", "t3"],
  );
  const bodies = new SavedBodies(source, 2);
  await bodies.load(["t1"]);
  bodies.forget(["t1"]);
  expect(bodies.peek(["t1"])).toBeNull();
  await bodies.load(["t1"]);
  await bodies.load(["t2"]);
  await bodies.load(["t3"]);
  expect(bodies.peek(["t1"])).toBeNull();
  expect(bodies.peek(["t3"])?.id).toBe("t3");
  expect(reads).toEqual([["t1"], ["t1"], ["t2"], ["t3"]]);
});
