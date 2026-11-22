import {
  type FetchLike,
  requestJson,
  type TokenSource,
} from "../gmail/request";
import type { ContactSource, PeoplePerson } from "./contacts";

const BASE = "https://people.googleapis.com/v1";
const FIELDS = "names,emailAddresses,photos,metadata";
const PAGE_SIZE = "1000";

/** One page of a People API list or sync. */
export type PeoplePage = {
  people: PeoplePerson[];
  nextPageToken: string | null;
  nextSyncToken: string | null;
};

/** People API reads for autocomplete: saved contacts and "other contacts". */
export class PeopleClient {
  #tokens: TokenSource;
  #fetch: FetchLike;

  constructor(tokens: TokenSource, fetchImpl: FetchLike = fetch) {
    this.#tokens = tokens;
    this.#fetch = (input, init) => fetchImpl(input, init);
  }

  /** A page of a list, from the start or from a sync token. */
  async page(
    source: ContactSource,
    syncToken: string | null,
    pageToken: string | null,
  ): Promise<PeoplePage> {
    const params = new URLSearchParams({
      pageSize: PAGE_SIZE,
      requestSyncToken: "true",
    });
    if (source === "connections") params.set("personFields", FIELDS);
    else params.set("readMask", FIELDS);
    if (syncToken) params.set("syncToken", syncToken);
    if (pageToken) params.set("pageToken", pageToken);
    const path =
      source === "connections" ? "/people/me/connections" : "/otherContacts";
    const result = await requestJson<{
      connections?: PeoplePerson[];
      otherContacts?: PeoplePerson[];
      nextPageToken?: string;
      nextSyncToken?: string;
    }>(this.#tokens, this.#fetch, `${BASE}${path}?${params}`);
    return {
      people: result?.connections ?? result?.otherContacts ?? [],
      nextPageToken: result?.nextPageToken ?? null,
      nextSyncToken: result?.nextSyncToken ?? null,
    };
  }
}
