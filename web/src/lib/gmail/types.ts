/** One header of a Gmail message part. */
export type GmailHeader = { name: string; value: string };

/** Body of a Gmail message part: inline base64url data, or an attachment id to fetch it by. */
export type GmailPartBody = {
  attachmentId?: string;
  size: number;
  data?: string;
};

/** A node of a Gmail message's MIME tree. */
export type GmailMessagePart = {
  partId?: string;
  mimeType: string;
  filename?: string;
  headers?: GmailHeader[];
  body?: GmailPartBody;
  parts?: GmailMessagePart[];
};

/** A Gmail message as `messages.get` / `threads.get` return it. */
export type GmailMessage = {
  id: string;
  threadId: string;
  labelIds?: string[];
  snippet?: string;
  historyId?: string;
  // Epoch milliseconds, as a decimal string.
  internalDate?: string;
  payload?: GmailMessagePart;
  sizeEstimate?: number;
  // The whole message as sent, base64url, with `format=raw`.
  raw?: string;
};

/** A Gmail thread as `threads.get` returns it. */
export type GmailThread = {
  id: string;
  historyId?: string;
  snippet?: string;
  messages?: GmailMessage[];
};

/** One entry of `threads.list`. */
export type GmailThreadStub = {
  id: string;
  snippet?: string;
  historyId?: string;
};

/** A page of `threads.list`. */
export type GmailThreadList = {
  threads?: GmailThreadStub[];
  nextPageToken?: string;
  resultSizeEstimate?: number;
};

/** A Gmail label from `labels.list` / `labels.get`. */
export type GmailLabel = {
  id: string;
  name: string;
  type: "system" | "user";
  labelListVisibility?: "labelShow" | "labelShowIfUnread" | "labelHide";
  color?: { textColor?: string; backgroundColor?: string };
  threadsTotal?: number;
  threadsUnread?: number;
};

/** A label to create, or the fields of one to change. */
export type GmailLabelInput = {
  name: string;
  labelListVisibility?: "labelShow" | "labelShowIfUnread" | "labelHide";
  messageListVisibility?: "show" | "hide";
  color?: { textColor: string; backgroundColor: string };
};

/** An address Gmail may forward to. */
export type GmailForwardingAddress = {
  forwardingEmail: string;
  verificationStatus?: "accepted" | "pending" | "verificationStatusUnspecified";
};

/** One record of `history.list`, reduced to the message and label changes this app reads. */
export type GmailHistoryRecord = {
  id: string;
  messagesAdded?: { message: GmailMessage }[];
  labelsAdded?: { message: GmailMessage; labelIds?: string[] }[];
  labelsRemoved?: { message: GmailMessage; labelIds?: string[] }[];
  messagesDeleted?: { message: GmailMessage }[];
};

/** A page of `history.list`. */
export type GmailHistoryList = {
  history?: GmailHistoryRecord[];
  nextPageToken?: string;
  historyId?: string;
};

/** A Gmail draft: its id and the message it holds. */
export type GmailDraft = { id: string; message: GmailMessage };

/** One address Gmail can send as, from `settings.sendAs.list`. */
export type GmailSendAs = {
  sendAsEmail: string;
  displayName?: string;
  isPrimary?: boolean;
  isDefault?: boolean;
  verificationStatus?: "accepted" | "pending" | "verificationStatusUnspecified";
};
