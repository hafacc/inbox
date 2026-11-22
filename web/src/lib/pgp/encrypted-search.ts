// Search inside encrypted mail, which Gmail can't read (docs/pgp-contract.md "Offline cache and
// search"): ask Gmail for the encrypted mail the rest of the query allows, decrypt it on the device
// and match the free-text words.

import { freeTerms } from "@inbox/shared/offline-search";
import { ENCRYPTED_MAIL_QUERY } from "@inbox/shared/offline-set";
import { gmailQueryText } from "@inbox/shared/search";
import type { SearchPlan } from "../search/search-plan";
import type { MessageView } from "../thread-detail";

export { ENCRYPTED_MAIL_QUERY };

/** Messages fetched per page. */
export const ENCRYPTED_SEARCH_PAGE = 50;
/** Most messages one search decrypts. */
export const ENCRYPTED_SEARCH_LIMIT = 200;

/** The words an encrypted message must hold. */
export type EncryptedTerms = { include: string[]; exclude: string[] };

/**
 * The second Gmail query for a search: its operators without the free text, plus
 * `ENCRYPTED_MAIL_QUERY`; null when there are no words to look for.
 */
export function encryptedSearch(
  plan: SearchPlan,
): { query: string; terms: EncryptedTerms } | null {
  const terms = freeTerms(plan.free);
  if (terms.include.length === 0) return null;
  const operators = gmailQueryText(plan.chips, "");
  return {
    query: [operators, ENCRYPTED_MAIL_QUERY].filter(Boolean).join(" "),
    terms,
  };
}

/** What words are matched against: the protected subject and the text of a decrypted message. */
export function searchableText(message: MessageView): string {
  const text =
    message.body.text ?? message.body.html?.replace(/<[^>]*>/g, " ") ?? "";
  return `${message.headers?.subject ?? ""} ${text}`.toLowerCase();
}

/** Whether a decrypted message holds every word, case-insensitively, and none excluded. */
export function matchesTerms(
  message: MessageView,
  terms: EncryptedTerms,
): boolean {
  const text = searchableText(message);
  return (
    terms.include.every((term) => text.includes(term)) &&
    !terms.exclude.some((term) => text.includes(term))
  );
}

/** What a session's runs of one encrypted search have done so far. */
export type EncryptedProgress = {
  /** encrypted messages already decrypted and matched */
  checked: Set<string>;
  /** threads that matched */
  matches: ReadonlySet<string>;
};

/** The messages of a page a run still has to check: not checked yet, in a thread not yet matched. */
export function uncheckedMessages<
  Message extends { id: string; threadId: string },
>(messages: readonly Message[], progress: EncryptedProgress): Message[] {
  return messages.filter(
    ({ id, threadId }) =>
      !progress.checked.has(id) && !progress.matches.has(threadId),
  );
}
