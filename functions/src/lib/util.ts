/** Logs what the handlers decided. */
export interface Log {
  /** Log an informational line. */
  info(message: string, fields?: Record<string, unknown>): void;
  /** Log a problem. */
  warn(message: string, fields?: Record<string, unknown>): void;
}

/** How long an outbound request may take before it is given up as failed; callers retry it. */
export const FETCH_TIMEOUT_MS = 20_000;

/** A signal that aborts an outbound request after {@link FETCH_TIMEOUT_MS}. */
export function fetchTimeout(): AbortSignal {
  return AbortSignal.timeout(FETCH_TIMEOUT_MS);
}

/** Whether an error is a request abandoned by {@link fetchTimeout}. */
export function isTimeout(error: unknown): boolean {
  return (
    error instanceof Error &&
    (error.name === "TimeoutError" || error.name === "AbortError")
  );
}

/** Text for logs with every email address in it replaced by `[address]`. */
export function redact(text: string): string {
  return text.replace(/[^\s<>()[\]"',;:@]+@[^\s<>()[\]"',;:@]+/g, "[address]");
}

/** An error's message, or the thrown value as text. */
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/** Whether a value is a finite number. */
export function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

/** Epoch milliseconds of a Firestore Timestamp; undefined for anything else. */
export function millisOf(value: unknown): number | undefined {
  if (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { toMillis?: unknown }).toMillis === "function"
  ) {
    return (value as { toMillis(): number }).toMillis();
  } else {
    return undefined;
  }
}

/** Decode the HTML character references Gmail and mail bodies use. */
export function decodeEntities(text: string): string {
  const character = (reference: string, digits: string, radix: number) => {
    const codePoint = Number.parseInt(digits, radix);
    return codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : reference;
  };
  return text
    .replace(/&#x([0-9a-f]+);/gi, (reference, hex: string) =>
      character(reference, hex, 16),
    )
    .replace(/&#(\d+);/g, (reference, decimal: string) =>
      character(reference, decimal, 10),
    )
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

/** Run `work` on every item with at most `limit` running at once; results keep the items' order. */
export async function mapLimit<Item, Result>(
  items: readonly Item[],
  limit: number,
  work: (item: Item) => Promise<Result>,
): Promise<Result[]> {
  const results: Result[] = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await work(items[index] as Item);
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  return results;
}
