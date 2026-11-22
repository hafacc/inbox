// Recipient suggestions: the address book autocomplete searches, and how typed text ranks it.
import { addressKey } from "./compose/address";

/** Which People API list a contact came from: a saved contact or someone the user has mailed. */
export type ContactSource = "connections" | "other";

/** One address of one person, as autocomplete offers it. */
export type BookEntry = {
  /** display name, or empty */
  name: string;
  /** address */
  email: string;
  /** photo URL, or null */
  photo: string | null;
  /** saved contact or someone the user has mailed */
  source: ContactSource;
};

/**
 * The address book autocomplete searches, one entry per address: the first entry for an address
 * wins (callers list saved contacts first), and later ones only fill in its missing name or photo.
 */
export function mergeBook<Entry extends BookEntry>(
  entries: Iterable<Entry>,
): Entry[] {
  const byAddress = new Map<string, Entry>();
  for (const entry of entries) {
    const key = addressKey(entry.email);
    const known = byAddress.get(key);
    if (known === undefined) byAddress.set(key, entry);
    else if (
      (known.name === "" && entry.name !== "") ||
      (known.photo === null && entry.photo !== null)
    ) {
      byAddress.set(key, {
        ...known,
        name: known.name || entry.name,
        photo: known.photo ?? entry.photo,
      });
    }
  }
  return [...byAddress.values()];
}

/** Text as matching compares it: lower-cased, accents dropped (`José` → `jose`). */
export function foldText(text: string): string {
  return text.toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");
}

/** The words of a name or address, folded, split at spaces and punctuation. */
export function contactWords(text: string): string[] {
  return foldText(text)
    .split(/[\s.,\-_@+'"()]+/u)
    .filter((word) => word !== "");
}

/**
 * How well an entry matches what's typed; 0 for no match. Every typed word must start a word of
 * the name or address, in any order. Whole-address and name starts score highest; saved contacts
 * beat others, and named ones beat bare addresses.
 */
export function matchScore(
  entry: Pick<BookEntry, "name" | "email" | "source">,
  query: string,
): number {
  const typed = foldText(query.trim());
  const typedWords = contactWords(typed);
  const [firstWord] = typedWords;
  if (firstWord === undefined) return 0;
  const nameWords = contactWords(entry.name);
  const allWords = [...nameWords, ...contactWords(entry.email)];
  if (
    !typedWords.every((word) => allWords.some((part) => part.startsWith(word)))
  ) {
    return 0;
  }
  let score = 1;
  if (foldText(entry.email).startsWith(typed)) score += 4;
  if (foldText(entry.name).startsWith(typed)) score += 4;
  if (nameWords.some((part) => part.startsWith(firstWord))) score += 2;
  if (entry.source === "connections") score += 3;
  if (entry.name !== "") score += 1;
  return score;
}

function compareText(left: string, right: string): number {
  if (left < right) return -1;
  else return left > right ? 1 : 0;
}

/**
 * The best `limit` entries for what's typed, best first, leaving out the addresses in `exclude`
 * (already chosen). Ties go by name (or address, when unnamed), then address.
 */
export function searchContacts<Entry extends BookEntry>(
  book: readonly Entry[],
  query: string,
  exclude: readonly string[] = [],
  limit = 6,
): Entry[] {
  const excluded = new Set(exclude.map(addressKey));
  return book
    .filter((entry) => !excluded.has(addressKey(entry.email)))
    .map((entry) => ({ entry, score: matchScore(entry, query) }))
    .filter(({ score }) => score > 0)
    .sort(
      (left, right) =>
        right.score - left.score ||
        compareText(
          foldText(left.entry.name || left.entry.email),
          foldText(right.entry.name || right.entry.email),
        ) ||
        compareText(
          addressKey(left.entry.email),
          addressKey(right.entry.email),
        ),
    )
    .slice(0, limit)
    .map(({ entry }) => entry);
}
