import type {
  GmailFilter,
  GmailFilterAction,
  GmailFilterCriteria,
} from "@inbox/shared/filters";
import { openRequest } from "../open-timing";
import {
  type CallPriority,
  type CallTicket,
  type FetchLike,
  GmailError,
  RateGate,
  requestJson,
  type TokenSource,
} from "./request";
import type {
  GmailDraft,
  GmailForwardingAddress,
  GmailHistoryList,
  GmailLabel,
  GmailLabelInput,
  GmailMessage,
  GmailSendAs,
  GmailThread,
  GmailThreadList,
} from "./types";

export {
  type CallPriority,
  FetchFailedError,
  type FetchLike,
  GmailError,
  isNetworkError,
  isRateLimited,
  isRetryable,
  isTimeout,
  moreUrgent,
  RateGate,
  type TokenSource,
} from "./request";

const BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

/** Options for `threads.list`. */
export type ListOptions = {
  labelIds?: string[];
  q?: string;
  pageToken?: string | null;
  maxResults?: number;
  includeSpamTrash?: boolean;
};

/** Headers fetched for list rows: all `summarizeThread` reads. */
const METADATA_HEADERS = [
  "From",
  "Subject",
  "Message-ID",
  "In-Reply-To",
  "References",
];
/** The parts of a `metadata` thread `summarizeThread` reads, so Gmail sends nothing more. */
const METADATA_FIELDS =
  "id,historyId,snippet,messages(id,threadId,historyId,labelIds,snippet,internalDate,payload/headers)";

// A read joined to the same one already in flight, noted in the open being timed as one try
// that waited for the read to end: otherwise an open joined to a read started before it shows none.
function joined<Result>(
  request: Promise<Result>,
  url: string,
  ticket: CallTicket,
): Promise<Result> {
  const timing = openRequest("GET", url, ticket.priority);
  if (timing === null) return request;
  const asked = performance.now();
  const note = (outcome: string) => {
    timing.attempt(
      performance.now() - asked,
      0,
      outcome,
      `${ticket.priority} joined`,
    );
    timing.ended();
  };
  return request.then(
    (result) => {
      note("ok");
      return result;
    },
    (error: unknown) => {
      note(
        error instanceof GmailError
          ? `${error.status}${error.reason === null ? "" : ` ${error.reason}`}`
          : "failed",
      );
      throw error;
    },
  );
}

/** The Gmail REST calls this app makes, straight from the browser with the user's token. */
export class GmailClient {
  /** The turns and the pause every call on this device shares. */
  readonly gate: RateGate;
  #tokens: TokenSource;
  #fetch: FetchLike;
  // Reads in flight by URL, so the same read asked for twice at once goes out once.
  #reading = new Map<
    string,
    { request: Promise<unknown>; ticket: CallTicket }
  >();

  constructor(
    tokens: TokenSource,
    fetchImpl: FetchLike = fetch,
    gate: RateGate = new RateGate(),
  ) {
    this.#tokens = tokens;
    // Native fetch throws "Illegal invocation" when called as a method of another object.
    this.#fetch = (input, init) => fetchImpl(input, init);
    this.gate = gate;
  }

  // A write is a user's action unless said otherwise; a read is what's on screen.
  #request<Result>(
    path: string,
    init: RequestInit = {},
    priority?: CallPriority,
  ): Promise<Result> {
    const url = `${BASE}${path}`;
    if ((init.method ?? "GET").toUpperCase() !== "GET") {
      return requestJson(this.#tokens, this.#fetch, url, init, {
        gate: this.gate,
        ticket: { priority: priority ?? "action" },
      });
    }
    const wanted = priority ?? "visible";
    const reading = this.#reading.get(url);
    if (reading !== undefined) {
      this.gate.raise(reading.ticket, wanted);
      return joined(reading.request as Promise<Result>, url, reading.ticket);
    }
    const ticket: CallTicket = { priority: wanted };
    const request = requestJson<Result>(this.#tokens, this.#fetch, url, init, {
      gate: this.gate,
      ticket,
    }).finally(() => this.#reading.delete(url));
    this.#reading.set(url, { request, ticket });
    return request;
  }

  // A create that timed out may still have been made, so its retry can meet Gmail's duplicate
  // refusal (a label or filter), whose callers adopt what Gmail already has, or make a second draft:
  // a draft can't be matched to an earlier try, since the app sets no `Message-ID` (Gmail adds it).
  #create<Result>(path: string, body: unknown): Promise<Result> {
    return this.#request(path, { method: "POST", body: JSON.stringify(body) });
  }

  /** One page of threads matching labels and/or a search. */
  listThreads(
    options: ListOptions,
    priority: CallPriority = "visible",
  ): Promise<GmailThreadList> {
    const params = new URLSearchParams();
    for (const label of options.labelIds ?? [])
      params.append("labelIds", label);
    if (options.q) params.set("q", options.q);
    if (options.pageToken) params.set("pageToken", options.pageToken);
    params.set("maxResults", String(options.maxResults ?? 50));
    if (options.includeSpamTrash) params.set("includeSpamTrash", "true");
    return this.#request(`/threads?${params}`, {}, priority);
  }

  /** A thread with the headers and fields a list row needs (`metadata`) or with bodies (`full`). */
  getThread(
    id: string,
    format: "metadata" | "full",
    priority: CallPriority = "visible",
  ): Promise<GmailThread> {
    const params = new URLSearchParams({ format });
    if (format === "metadata") {
      for (const header of METADATA_HEADERS) {
        params.append("metadataHeaders", header);
      }
      params.set("fields", METADATA_FIELDS);
    }
    return this.#request(
      `/threads/${encodeURIComponent(id)}?${params}`,
      {},
      priority,
    );
  }

  /** One page of message ids matching a search, newest first. */
  listMessages(
    query: string,
    pageToken: string | null,
    maxResults: number,
  ): Promise<{
    messages?: { id: string; threadId: string }[];
    nextPageToken?: string;
  }> {
    const params = new URLSearchParams({
      q: query,
      maxResults: String(maxResults),
    });
    if (pageToken) params.set("pageToken", pageToken);
    return this.#request(`/messages?${params}`);
  }

  /** Add and remove labels on every message of a thread. */
  modifyThread(
    id: string,
    addLabelIds: string[],
    removeLabelIds: string[],
  ): Promise<GmailThread> {
    return this.#request(`/threads/${encodeURIComponent(id)}/modify`, {
      method: "POST",
      body: JSON.stringify({ addLabelIds, removeLabelIds }),
    });
  }

  /** Every label of the account, without counts. */
  async listLabels(): Promise<GmailLabel[]> {
    const result = await this.#request<{ labels?: GmailLabel[] }>("/labels");
    return result.labels ?? [];
  }

  /** Make a user label. */
  createLabel(label: GmailLabelInput): Promise<GmailLabel> {
    return this.#create("/labels", label);
  }

  /** Change a label's name or color. */
  patchLabel(id: string, patch: Partial<GmailLabelInput>): Promise<GmailLabel> {
    return this.#request(`/labels/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    });
  }

  /** The account's Gmail filters. */
  async listFilters(): Promise<GmailFilter[]> {
    const result = await this.#request<{ filter?: GmailFilter[] }>(
      "/settings/filters",
    );
    return result.filter ?? [];
  }

  /** Make a Gmail filter. */
  createFilter(filter: {
    criteria: GmailFilterCriteria;
    action: GmailFilterAction;
  }): Promise<GmailFilter> {
    return this.#create("/settings/filters", filter);
  }

  /** Delete a Gmail filter. */
  async deleteFilter(id: string): Promise<void> {
    await this.#request(`/settings/filters/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  /** Addresses Gmail may forward to (`settings.forwardingAddresses.list`). */
  async listForwardingAddresses(): Promise<GmailForwardingAddress[]> {
    const result = await this.#request<{
      forwardingAddresses?: GmailForwardingAddress[];
    }>("/settings/forwardingAddresses");
    return result.forwardingAddresses ?? [];
  }

  /** One label with its thread counts. */
  getLabel(id: string): Promise<GmailLabel> {
    return this.#request(`/labels/${encodeURIComponent(id)}`);
  }

  /** Message and label additions since a history id, one page; a background check by default. */
  listHistory(
    startHistoryId: string,
    pageToken?: string,
    maxResults = 500,
    types: readonly string[] | null = ["messageAdded", "labelAdded"],
    priority: CallPriority = "background",
  ): Promise<GmailHistoryList> {
    const params = new URLSearchParams([
      ["startHistoryId", startHistoryId],
      ...(types ?? []).map((type) => ["historyTypes", type]),
      ["maxResults", String(maxResults)],
    ]);
    if (pageToken) params.set("pageToken", pageToken);
    return this.#request(`/history?${params}`, {}, priority);
  }

  /** An attachment's base64url data. */
  async getAttachment(
    messageId: string,
    attachmentId: string,
  ): Promise<string> {
    const result = await this.#request<{ data: string }>(
      `/messages/${encodeURIComponent(messageId)}/attachments/${encodeURIComponent(attachmentId)}`,
    );
    return result.data;
  }

  /** The addresses Gmail can send as (`settings.sendAs.list`). */
  async listSendAs(): Promise<GmailSendAs[]> {
    const result = await this.#request<{ sendAs?: GmailSendAs[] }>(
      "/settings/sendAs",
    );
    return result.sendAs ?? [];
  }

  /** Save a new draft from a whole message (`raw`, base64url), threaded into `threadId` when given. */
  createDraft(raw: string, threadId: string | null): Promise<GmailDraft> {
    return this.#create("/drafts", {
      message: threadId === null ? { raw } : { raw, threadId },
    });
  }

  /** Replace a draft's message. */
  updateDraft(
    id: string,
    raw: string,
    threadId: string | null,
  ): Promise<GmailDraft> {
    return this.#request(`/drafts/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify({
        id,
        message: threadId === null ? { raw } : { raw, threadId },
      }),
    });
  }

  /** The account's drafts: ids and the message and thread each holds. */
  async listDrafts(): Promise<GmailDraft[]> {
    const result = await this.#request<{ drafts?: GmailDraft[] }>(
      "/drafts?maxResults=500",
    );
    return result.drafts ?? [];
  }

  /** A draft with its message parsed (`full`), headers only (`metadata`) or as written (`raw`). */
  getDraft(
    id: string,
    format: "full" | "metadata" | "raw",
  ): Promise<GmailDraft> {
    return this.#request(
      `/drafts/${encodeURIComponent(id)}?${new URLSearchParams({ format })}`,
    );
  }

  /**
   * Send a draft as it stands (`drafts.send`), tried once: past the gate, which could hold it back
   * after its claim, and never again after a usage limit, which could send it twice.
   */
  sendDraft(id: string): Promise<GmailMessage> {
    return requestJson(
      this.#tokens,
      this.#fetch,
      `${BASE}/drafts/send`,
      { method: "POST", body: JSON.stringify({ id }) },
      { gate: null },
    );
  }

  /** Delete a draft for good. */
  async deleteDraft(id: string): Promise<void> {
    await this.#request(`/drafts/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  }

  /** A message with headers only (`metadata`), its parsed parts (`full`) or as sent (`raw`). */
  getMessage(
    id: string,
    format: "full" | "metadata" | "raw",
    priority: CallPriority = "visible",
  ): Promise<GmailMessage> {
    return this.#request(
      `/messages/${encodeURIComponent(id)}?${new URLSearchParams({ format })}`,
      {},
      priority,
    );
  }

  /** The account's address and current history id. */
  getProfile(
    priority: CallPriority = "background",
  ): Promise<{ emailAddress: string; historyId: string }> {
    return this.#request("/profile", {}, priority);
  }
}

/** Run `task` over `items` with at most `limit` running at once, keeping the input order. */
export async function mapLimited<Item, Result>(
  items: readonly Item[],
  limit: number,
  task: (item: Item) => Promise<Result>,
): Promise<PromiseSettledResult<Result>[]> {
  const results: PromiseSettledResult<Result>[] = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      try {
        results[index] = {
          status: "fulfilled",
          value: await task(items[index]!),
        };
      } catch (reason) {
        results[index] = { status: "rejected", reason };
      }
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  return results;
}
