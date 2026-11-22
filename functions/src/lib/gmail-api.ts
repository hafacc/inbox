import { fetchTimeout, isTimeout } from "./util.js";

const BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

/** Gmail system label ids the server reads. */
export const LABEL = {
  inbox: "INBOX",
  draft: "DRAFT",
  sent: "SENT",
  spam: "SPAM",
  trash: "TRASH",
  unread: "UNREAD",
} as const;

/** One header of a Gmail message. */
export interface GmailHeader {
  /** header name */
  name: string;
  /** header value */
  value: string;
}

/** A Gmail message as `messages.get` returns it in `metadata` format. */
export interface GmailMessage {
  /** message id */
  id: string;
  /** thread id */
  threadId: string;
  /** label ids */
  labelIds?: string[];
  /** short plain-text preview */
  snippet?: string;
  /** last history id that touched the message */
  historyId?: string;
  /** epoch milliseconds, as a decimal string */
  internalDate?: string;
  /** top MIME part, carrying the requested headers and its media type */
  payload?: { mimeType?: string; headers?: GmailHeader[] };
}

/** A Gmail thread in `metadata` format. */
export interface GmailThread {
  /** thread id */
  id: string;
  /** the thread's latest history id */
  historyId?: string;
  /** messages, oldest first */
  messages?: GmailMessage[];
}

/** One record of `history.list`. */
export interface GmailHistoryRecord {
  /** history id of the record */
  id: string;
  /** messages added to the mailbox */
  messagesAdded?: { message: GmailMessage }[];
  /** labels added to messages */
  labelsAdded?: { message: GmailMessage; labelIds?: string[] }[];
  /** labels removed from messages */
  labelsRemoved?: { message: GmailMessage; labelIds?: string[] }[];
}

/** A page of `history.list`. */
export interface GmailHistoryPage {
  /** records, oldest first */
  history?: GmailHistoryRecord[];
  /** token for the next page */
  nextPageToken?: string;
  /** the mailbox's current history id */
  historyId?: string;
}

/** Result of `users.watch`. */
export interface GmailWatch {
  /** the mailbox's history id when the watch started */
  historyId: string;
  /** when the watch lapses, in epoch milliseconds */
  expiration: number;
}

/** A draft as `drafts.get` returns it in `raw` format. */
export interface GmailRawDraft {
  /** draft id */
  id: string;
  /** the draft's message */
  message: {
    /** message id */
    id: string;
    /** thread id */
    threadId: string;
    /** the whole message, base64url */
    raw: string;
  };
}

/** A draft as `drafts.get` returns it in `metadata` format: its message's headers, no body. */
export interface GmailDraftHeaders {
  /** draft id */
  id: string;
  /** the draft's message */
  message: {
    /** message id */
    id: string;
    /** thread id */
    threadId: string;
    /** its headers, as Gmail reads them */
    payload?: { headers?: GmailHeader[] };
  };
}

/** One send-as address from `settings.sendAs.list`. */
export interface GmailSendAs {
  /** the address mail is sent from */
  sendAsEmail: string;
  /** whether it is the account's own address */
  isPrimary?: boolean;
  /** `accepted` once Gmail verified the alias; absent for the primary address */
  verificationStatus?: string;
}

/** One MIME part of a message in `full` format. */
export interface GmailPart {
  /** MIME type, e.g. `text/html` */
  mimeType?: string;
  /** the part's headers */
  headers?: GmailHeader[];
  /** the part's decoded content, base64url, when it is inline */
  body?: { data?: string; size?: number };
  /** child parts of a multipart */
  parts?: GmailPart[];
}

/** A Gmail message as `messages.get` returns it in `full` format. */
export interface GmailFullMessage {
  /** message id */
  id: string;
  /** thread id */
  threadId: string;
  /** label ids */
  labelIds?: string[];
  /** epoch milliseconds, as a decimal string */
  internalDate?: string;
  /** top MIME part */
  payload?: GmailPart;
}

/** The Gmail calls that look for one message and put it away. */
export interface GmailMailbox {
  /** Ids of messages matching a Gmail search, newest first. */
  listMessages(
    query: string,
    maxResults: number,
  ): Promise<{ id: string; threadId: string }[]>;
  /** A message with every header and body part. */
  getFullMessage(id: string): Promise<GmailFullMessage>;
  /** Move a message to the trash. */
  trashMessage(id: string): Promise<void>;
}

/** A Gmail API call that came back with an error status. */
export class GmailError extends Error {
  /** HTTP status */
  readonly status: number;
  /** Google's reason, e.g. `rateLimitExceeded`, when the response gave one */
  readonly reason: string | undefined;

  /** Create an error for an HTTP status */
  constructor(status: number, message: string, reason?: string) {
    super(message);
    this.name = "GmailError";
    this.status = status;
    this.reason = reason;
  }
}

/** The part of `fetch` the client uses, injectable for tests. */
export type GmailFetch = (
  input: string,
  init: {
    method: string;
    headers: Record<string, string>;
    body?: string;
    signal?: AbortSignal;
  },
) => Promise<Response>;

type Method = "GET" | "POST" | "DELETE";

/** Headers fetched for notifications and for joining an encrypted reply to the thread it answers. */
const METADATA_HEADERS = [
  "From",
  "Subject",
  "Content-Type",
  "In-Reply-To",
  "References",
];

/** The Gmail calls the server makes on a user's behalf. */
export interface GmailApi {
  /** Start or renew push notifications to a Pub/Sub topic for changes that touch the inbox. */
  watch(topicName: string): Promise<GmailWatch>;
  /** Stop push notifications for the mailbox. */
  stop(): Promise<void>;
  /** One page of message additions and label changes since a history id. */
  listHistory(
    startHistoryId: string,
    pageToken?: string,
  ): Promise<GmailHistoryPage>;
  /**
   * A message's labels and snippet, with the named headers (by default its sender, subject,
   * content type and reply headers).
   */
  getMessage(id: string, headers?: readonly string[]): Promise<GmailMessage>;
  /** A thread's history id and messages with sender and subject. */
  getThread(id: string): Promise<GmailThread>;
  /** Add and remove labels on every message of a thread. */
  modifyThread(id: string, add: string[], remove: string[]): Promise<void>;
  /** Ids of messages matching a Gmail search, newest first. */
  listMessages(
    query: string,
    maxResults: number,
  ): Promise<{ id: string; threadId: string }[]>;
  /** The mailbox's current history id. */
  getHistoryId(): Promise<string>;
  /** A draft with its whole message, base64url. */
  getDraftRaw(id: string): Promise<GmailRawDraft>;
  /** A draft's message headers only. */
  getDraftHeaders(id: string): Promise<GmailDraftHeaders>;
  /** Send a draft as it is; returns the sent message's ids. */
  sendDraft(id: string): Promise<{ id: string; threadId: string }>;
  /** Delete a draft for good. */
  deleteDraft(id: string): Promise<void>;
  /** File a message (base64url) into the mailbox without sending it; in its own thread without `threadId`. */
  insertMessage(
    raw: string,
    threadId: string | undefined,
    labelIds: string[],
  ): Promise<{ id: string; threadId: string }>;
  /** The addresses Gmail lets this account send from. */
  listSendAs(): Promise<GmailSendAs[]>;
}

function metadataParams(
  headers: readonly string[] = METADATA_HEADERS,
): URLSearchParams {
  const params = new URLSearchParams({ format: "metadata" });
  for (const header of headers) {
    params.append("metadataHeaders", header);
  }
  return params;
}

/** Create a Gmail client for an access token. */
export function gmailApi(
  accessToken: string,
  fetchImpl: GmailFetch = fetch,
): GmailApi & GmailMailbox {
  async function request<Result>(
    path: string,
    body?: unknown,
    method: Method = body === undefined ? "GET" : "POST",
  ) {
    try {
      return await send<Result>(path, body, method);
    } catch (error) {
      // A timeout is answered like Gmail's own 408, which callers retry.
      if (isTimeout(error)) {
        throw new GmailError(408, "Gmail took too long to answer", "timeout");
      }
      throw error;
    }
  }

  async function send<Result>(path: string, body: unknown, method: Method) {
    const response = await fetchImpl(`${BASE}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: fetchTimeout(),
    });
    if (!response.ok) {
      let message = response.statusText;
      let reason: string | undefined;
      try {
        const parsed = (await response.json()) as {
          error?: { message?: string; errors?: { reason?: string }[] };
        };
        message = parsed.error?.message ?? message;
        reason = parsed.error?.errors?.[0]?.reason;
      } catch {
        // not JSON
      }
      throw new GmailError(response.status, message, reason);
    }
    const text = await response.text();
    return (text === "" ? {} : JSON.parse(text)) as Result;
  }

  return {
    async watch(topicName) {
      const result = await request<{ historyId: string; expiration: string }>(
        "/watch",
        {
          topicName,
          labelIds: [LABEL.inbox],
          labelFilterBehavior: "include",
        },
      );
      return {
        historyId: result.historyId,
        expiration: Number(result.expiration),
      };
    },
    async stop() {
      await request("/stop", {});
    },
    listHistory(startHistoryId, pageToken) {
      const params = new URLSearchParams([
        ["startHistoryId", startHistoryId],
        ["historyTypes", "messageAdded"],
        ["historyTypes", "labelAdded"],
        ["historyTypes", "labelRemoved"],
        ["maxResults", "500"],
      ]);
      if (pageToken !== undefined) params.set("pageToken", pageToken);
      return request(`/history?${params}`);
    },
    getMessage(id, headers) {
      return request(
        `/messages/${encodeURIComponent(id)}?${metadataParams(headers)}`,
      );
    },
    getFullMessage(id) {
      return request(`/messages/${encodeURIComponent(id)}?format=full`);
    },
    async trashMessage(id) {
      await request(`/messages/${encodeURIComponent(id)}/trash`, {});
    },
    getThread(id) {
      return request(`/threads/${encodeURIComponent(id)}?${metadataParams()}`);
    },
    async modifyThread(id, add, remove) {
      await request(`/threads/${encodeURIComponent(id)}/modify`, {
        addLabelIds: add,
        removeLabelIds: remove,
      });
    },
    async listMessages(query, maxResults) {
      const params = new URLSearchParams({
        q: query,
        maxResults: String(maxResults),
      });
      const result = await request<{
        messages?: { id: string; threadId: string }[];
      }>(`/messages?${params}`);
      return result.messages ?? [];
    },
    async getHistoryId() {
      const profile = await request<{ historyId: string }>("/profile");
      return profile.historyId;
    },
    getDraftRaw(id) {
      return request(`/drafts/${encodeURIComponent(id)}?format=raw`);
    },
    getDraftHeaders(id) {
      return request(`/drafts/${encodeURIComponent(id)}?format=metadata`);
    },
    async sendDraft(id) {
      const sent = await request<{ id: string; threadId: string }>(
        "/drafts/send",
        { id },
      );
      return { id: sent.id, threadId: sent.threadId };
    },
    async deleteDraft(id) {
      await request(`/drafts/${encodeURIComponent(id)}`, undefined, "DELETE");
    },
    async insertMessage(raw, threadId, labelIds) {
      // Dated by its Date header, as a sent message is, rather than by when it was filed.
      const inserted = await request<{ id: string; threadId: string }>(
        "/messages?internalDateSource=dateHeader",
        { raw, threadId, labelIds },
      );
      return { id: inserted.id, threadId: inserted.threadId };
    },
    async listSendAs() {
      const result = await request<{ sendAs?: GmailSendAs[] }>(
        "/settings/sendAs",
      );
      return result.sendAs ?? [];
    },
  };
}
