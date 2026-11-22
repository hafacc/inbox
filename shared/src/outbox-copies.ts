// The outbox docs of one send: an encrypted send with Bcc recipients writes its first message's doc
// `{id}` and one doc per Bcc copy, `{id}-bcc-{n}`. Web and Android (:core send/OutboxCopies.kt) both
// run shared/testdata/outbox-copy-fixtures.json.

// A copy's id: the send's id, `-bcc-`, and its number.
const COPY_ID = /^(.+)-bcc-([1-9]\d*)$/;

/** The outbox doc id of a split send's copy number `copy` (from 1). */
export function copyOutboxId(baseId: string, copy: number): string {
  return `${baseId}-bcc-${copy}`;
}

/** A Bcc copy's number, or null for a send's first doc. */
export function copyNumber(id: string): number | null {
  const match = COPY_ID.exec(id);
  return match === null ? null : Number(match[2]);
}

/** Whether an outbox doc id is a Bcc copy's. */
export function isCopyId(id: string): boolean {
  return COPY_ID.test(id);
}

/** The id of the send a doc belongs to: its own, or the first message's for a copy. */
export function baseOutboxId(id: string): string {
  return COPY_ID.exec(id)?.[1] ?? id;
}

/** The docs of one send, in the order given: the first message's and its copies'. */
export function sendGroup<Doc extends { id: string }>(
  docs: Iterable<Doc>,
  baseId: string,
): Doc[] {
  return [...docs].filter((entry) => baseOutboxId(entry.id) === baseId);
}

/** `count` copy numbers not used by any doc of the send yet, ascending. */
export function nextCopyNumbers(
  ids: Iterable<string>,
  baseId: string,
  count: number,
): number[] {
  let highest = 0;
  for (const id of ids) {
    const copy = baseOutboxId(id) === baseId ? copyNumber(id) : null;
    if (copy !== null && copy > highest) highest = copy;
  }
  return Array.from({ length: count }, (_, index) => highest + index + 1);
}
