import { addressKey, type Mailbox } from "@inbox/shared/compose";
import {
  type BookEntry,
  type ContactSource,
  mergeBook as mergeEntries,
  searchContacts as searchEntries,
} from "@inbox/shared/contact-match";

export type { ContactSource };

/** One address of one person, as autocomplete offers it. */
export type Contact = BookEntry & {
  /** People API resource name, e.g. `people/c123` */
  resourceName: string;
};

/** A person as the People API returns them, reduced to what's read here. */
export type PeoplePerson = {
  resourceName: string;
  metadata?: { deleted?: boolean };
  names?: { displayName?: string; metadata?: { primary?: boolean } }[];
  emailAddresses?: { value?: string; metadata?: { primary?: boolean } }[];
  photos?: {
    url?: string;
    default?: boolean;
    metadata?: { primary?: boolean };
  }[];
};

function primaryFirst<Item extends { metadata?: { primary?: boolean } }>(
  items: readonly Item[],
): Item[] {
  return [...items].sort(
    (left, right) =>
      Number(right.metadata?.primary ?? false) -
      Number(left.metadata?.primary ?? false),
  );
}

/** A person's contacts, one per address, primary address first. */
export function contactsOf(
  person: PeoplePerson,
  source: ContactSource,
): Contact[] {
  const name = primaryFirst(person.names ?? [])[0]?.displayName?.trim() ?? "";
  // Google's generated letter (`default`) is left out: the app draws its own initials.
  const chosen = primaryFirst(person.photos ?? [])[0];
  const photo = chosen && !chosen.default && chosen.url ? chosen.url : null;
  const seen = new Set<string>();
  const out: Contact[] = [];
  for (const entry of primaryFirst(person.emailAddresses ?? [])) {
    const email = entry.value?.trim() ?? "";
    const key = addressKey(email);
    if (email === "" || seen.has(key)) continue;
    seen.add(key);
    out.push({
      resourceName: person.resourceName,
      name,
      email,
      photo,
      source,
    });
  }
  return out;
}

/**
 * Apply a page of a sync to the stored people: changed people replace their old entries, deleted
 * ones (`metadata.deleted`) drop out. Keyed by resource name.
 */
export function applySync(
  stored: ReadonlyMap<string, Contact[]>,
  people: readonly PeoplePerson[],
  source: ContactSource,
): Map<string, Contact[]> {
  const next = new Map(stored);
  for (const person of people) {
    if (person.metadata?.deleted) next.delete(person.resourceName);
    else next.set(person.resourceName, contactsOf(person, source));
  }
  return next;
}

/**
 * The address book autocomplete searches: saved contacts, then other contacts, one entry per
 * address. A saved contact wins over an "other contact" with the same address.
 */
export function mergeBook(
  connections: Iterable<Contact[]>,
  other: Iterable<Contact[]>,
): Contact[] {
  return mergeEntries([...connections, ...other].flat());
}

/** The best `limit` contacts for a query, leaving out addresses already chosen. */
export function searchContacts(
  book: readonly Contact[],
  query: string,
  exclude: readonly Mailbox[] = [],
  limit = 6,
): Contact[] {
  return searchEntries(
    book,
    query,
    exclude.map((mailbox) => mailbox.email),
    limit,
  );
}

/** Every photo URL the people hold. */
export function photoUrls(people: Iterable<Contact[]>): Set<string> {
  const urls = new Set<string>();
  for (const contacts of people) {
    for (const contact of contacts) {
      if (contact.photo !== null) urls.add(contact.photo);
    }
  }
  return urls;
}
