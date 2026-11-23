import {
  answerHeaders,
  type ComposeInput,
  draftLinks,
  InvalidAddressError,
  InvalidHeaderError,
  type Mailbox,
  messageIds,
  type QuotedMessage,
  quotedContentIds,
  replyFromAddress,
  replyRecipients,
  SIGNATURE_SEPARATOR,
  splitQuotedText,
} from "@inbox/shared/compose";
import {
  type Attachment,
  extractBody,
  headerValue,
  type InlinePart,
  parseAddressList,
} from "@inbox/shared/mime";
import type { OfflineDraftParts } from "@inbox/shared/offline-drafts";
import { formatMessageDate } from "@inbox/shared/time-format";
import type { GmailMessage } from "../gmail/types";
import type { MessageView } from "../thread-detail";

/** What a composer was opened for. */
export type ComposeMode = "new" | "reply" | "replyAll" | "forward";

/** A file in a message being written. */
export type ComposeFile = {
  /** local id, for list keys and removal */
  id: string;
  /** file name */
  filename: string;
  /** content type */
  mimeType: string;
  /** contents */
  data: Uint8Array;
};

/** An image shown in the body, referenced there as `cid:<contentId>`. */
export type ComposeImage = ComposeFile & { contentId: string };

/** Everything a composer holds, as saved on the device and turned into a draft. */
export type ComposeSnapshot = {
  /** id on this device, stable across saves */
  localId: string;
  /** what it was opened for */
  mode: ComposeMode;
  /** the Gmail thread a reply or forward belongs to */
  threadId: string | null;
  /** the Gmail draft, once saved there */
  draftId: string | null;
  /** the Gmail message id of the draft as last saved there */
  messageId: string | null;
  /** the Gmail thread the draft is in once saved there, a new message's own one too */
  draftThreadId?: string | null;
  /** sender address */
  from: string;
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
  /** `Bcc` */
  bcc: Mailbox[];
  /** subject */
  subject: string;
  /** body, markdown */
  markdown: string;
  /** body as the editor's HTML; absent or null when it wasn't edited, so it's built from `markdown` */
  html?: string | null;
  /** `In-Reply-To` */
  inReplyTo: string | null;
  /** `References` */
  references: string[];
  /** attachments */
  attachments: ComposeFile[];
  /** images in the body, and those of the quoted message under their original Content-IDs */
  inlineImages: ComposeImage[];
  /** the message a reply quotes, or null; missing in snapshots saved before quoting */
  quote?: QuotedMessage | null;
};

/** The headers of a message a reply or forward needs, as the thread reader keeps them. */
export type SourceMessage = {
  /** Gmail message id */
  id: string;
  /** `Message-ID`, or empty */
  messageId: string;
  /** `References`, or null */
  references: string | null;
  /** `In-Reply-To`, or null */
  inReplyTo: string | null;
  /** decoded subject */
  subject: string;
  /** `From` */
  from: Mailbox[];
  /** `Reply-To` */
  replyTo: Mailbox[];
  /** `To` */
  to: Mailbox[];
  /** `Cc` */
  cc: Mailbox[];
  /** when it arrived, epoch milliseconds */
  date: number;
  /** plain text of the body, for a forward */
  text: string;
};

/** Gmail's limit on a whole draft. */
export const MAX_MESSAGE_BYTES = 35 * 1024 * 1024;

/** A random id for local records and Content-IDs. */
export function randomId(): string {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

/** An empty new message. */
export function blankSnapshot(localId: string, from: string): ComposeSnapshot {
  return {
    localId,
    mode: "new",
    threadId: null,
    draftId: null,
    messageId: null,
    from,
    to: [],
    cc: [],
    bcc: [],
    subject: "",
    markdown: "",
    inReplyTo: null,
    references: [],
    attachments: [],
    inlineImages: [],
    quote: null,
  };
}

/** Content-IDs the markdown refers to with `cid:`. */
export function referencedContentIds(markdown: string): Set<string> {
  const ids = new Set<string>();
  for (const match of markdown.matchAll(/cid:([^\s)>"']+)/gi)) {
    ids.add(match[1]!);
  }
  return ids;
}

/** The inline images the body still refers to; ones whose markdown was deleted are dropped. */
export function referencedImages(
  markdown: string,
  images: readonly ComposeImage[],
): ComposeImage[] {
  const ids = referencedContentIds(markdown);
  return images.filter((image) => ids.has(image.contentId));
}

/** The images that go with a message: those the body refers to, then those its quote refers to. */
export function sentImages(
  markdown: string,
  quote: QuotedMessage | null,
  images: readonly ComposeImage[],
): ComposeImage[] {
  const ids = referencedContentIds(markdown);
  for (const id of quotedContentIds(quote?.html ?? null)) ids.add(id);
  return images.filter((image) => ids.has(image.contentId));
}

/** The quote of a reply to `message`: its sender, `Date`, text and HTML as received. */
export function quoteOf(message: MessageView): QuotedMessage {
  const { html, text } = message.body;
  return {
    from: message.headers?.from[0] ?? message.from,
    date: message.headers?.date ?? message.date,
    text: text ?? (html ? htmlToText(html) : message.snippet),
    html,
  };
}

const ATTRIBUTION =
  /^On (\w{3}, \w{3} \d{1,2}, \d{4}) at (\d{2}):(\d{2}), (?:(.*?) )?<([^<>\s]+)> wrote:$/;

/**
 * A quote rebuilt from its plain text alone (`On …, Name <addr> wrote:` then `> ` lines), for a
 * reopened draft whose replied-to message is gone; null when the attribution doesn't read.
 */
export function quoteFromText(quote: string): QuotedMessage | null {
  const [attribution = "", ...rest] = quote.replace(/\r\n?/g, "\n").split("\n");
  const match = ATTRIBUTION.exec(attribution.trim());
  if (match === null) return null;
  const [, day = "", hours = "", minutes = "", name = "", email = ""] = match;
  const when = new Date(day.replace(/^\w{3}, /, ""));
  if (Number.isNaN(when.getTime())) return null;
  when.setHours(Number(hours), Number(minutes), 0, 0);
  const text = rest
    .map((line) => line.replace(/^> ?/, ""))
    .join("\n")
    .replace(/^\n+|\n+$/g, "");
  return {
    from: { name, email },
    date: when.getTime(),
    text,
    html: null,
  };
}

/** Whether a composer holds nothing worth saving. */
export function isBlank(snapshot: ComposeSnapshot): boolean {
  return (
    snapshot.to.length === 0 &&
    snapshot.cc.length === 0 &&
    snapshot.bcc.length === 0 &&
    snapshot.subject.trim() === "" &&
    snapshot.markdown.trim() === "" &&
    snapshot.attachments.length === 0
  );
}

/** Why the message can't be sent yet, or null when it can. */
export function sendProblem(
  snapshot: ComposeSnapshot,
  size: number,
): string | null {
  if (
    snapshot.to.length === 0 &&
    snapshot.cc.length === 0 &&
    snapshot.bcc.length === 0
  ) {
    return "Add at least one recipient.";
  }
  if (size > MAX_MESSAGE_BYTES) {
    return "This message is over Gmail's 35 MB limit. Remove an attachment.";
  }
  return null;
}

/** What to tell the user when building the message failed on an address or a header, else null. */
export function buildProblem(error: unknown): string | null {
  if (error instanceof InvalidHeaderError) {
    return `The message's ${error.header} header can't be sent safely.`;
  }
  if (!(error instanceof InvalidAddressError)) return null;
  const address = error.address.trim();
  return address === ""
    ? "One of the addresses is empty. Remove it and try again."
    : `${address} isn't a valid email address. Fix or remove it.`;
}

/** The input for `buildMime`. */
export function composeInput(
  snapshot: ComposeSnapshot,
  from: Mailbox,
  signature: string | null,
  boundary: string,
): ComposeInput {
  const signatureText =
    signature === null || signature.trim() === "" ? null : signature;
  return {
    from,
    to: snapshot.to,
    cc: snapshot.cc,
    bcc: snapshot.bcc,
    subject: snapshot.subject,
    markdown: snapshot.markdown,
    html: snapshot.html ?? null,
    signature: signatureText,
    inReplyTo: snapshot.inReplyTo,
    references: snapshot.references,
    quote: snapshot.quote ?? null,
    inlineImages: sentImages(
      snapshot.markdown,
      snapshot.quote ?? null,
      snapshot.inlineImages,
    ).map(({ filename, mimeType, data, contentId }) => ({
      filename,
      mimeType,
      data,
      contentId,
    })),
    attachments: snapshot.attachments.map(({ filename, mimeType, data }) => ({
      filename,
      mimeType,
      data,
    })),
    extraHeaders: [],
    boundary,
  };
}

/** Recipients, subject, threading and From for answering `source`. */
export function answerFields(
  source: SourceMessage,
  mode: Exclude<ComposeMode, "new">,
  ownAddresses: readonly string[],
  fallbackFrom: string,
): Pick<
  ComposeSnapshot,
  "to" | "cc" | "subject" | "inReplyTo" | "references" | "from"
> {
  const participants = {
    from: source.from,
    replyTo: source.replyTo,
    to: source.to,
    cc: source.cc,
  };
  const from = replyFromAddress(participants, ownAddresses, fallbackFrom);
  const threading = answerHeaders(
    {
      messageId: source.messageId,
      references: source.references,
      inReplyTo: source.inReplyTo,
      subject: source.subject,
    },
    mode === "forward",
  );
  if (mode === "forward") {
    return {
      to: [],
      cc: [],
      subject: threading.subject,
      inReplyTo: threading.inReplyTo,
      references: threading.references,
      from,
    };
  }
  const recipients = replyRecipients(
    participants,
    ownAddresses,
    mode === "replyAll",
  );
  return {
    to: recipients.to,
    cc: recipients.cc,
    subject: threading.subject,
    inReplyTo: threading.inReplyTo,
    references: threading.references,
    from,
  };
}

function mailboxText(mailbox: Mailbox): string {
  return mailbox.name ? `${mailbox.name} <${mailbox.email}>` : mailbox.email;
}

/** The body of a forward: a header block, then the original text quoted line by line. */
export function forwardMarkdown(source: SourceMessage, now: number): string {
  const header = [
    "---------- Forwarded message ---------",
    `From: ${source.from.map(mailboxText).join(", ")}`,
    `Date: ${formatMessageDate(source.date, now)}`,
    `Subject: ${source.subject}`,
    `To: ${source.to.map(mailboxText).join(", ")}`,
    ...(source.cc.length > 0
      ? [`Cc: ${source.cc.map(mailboxText).join(", ")}`]
      : []),
  ];
  const body = source.text
    .replace(/\r\n?/g, "\n")
    .trimEnd()
    .split("\n")
    .map((line) => (line.trim() === "" ? ">" : `> ${line}`));
  return `\n\n${header.join("\n")}\n\n${body.join("\n")}\n`;
}

/** Plain text of an HTML body, for forwarding a message that has no text part. */
export function htmlToText(html: string): string {
  return html
    .replace(/<(script|style|head)[\s\S]*?<\/\1>/gi, "")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|tr|h[1-6]|blockquote)>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/**
 * A draft's plain text split into what was typed (signature included) and the quote this app put
 * after it (attribution first), or null when there is none.
 */
export function splitDraftText(text: string | null): {
  body: string | null;
  quote: string | null;
} {
  if (text === null) return { body: null, quote: null };
  const firstLine = text.split(/\r?\n/, 1)[0] ?? "";
  // A reply with nothing typed and no signature is all quote, which splitQuotedText won't take.
  const onlyQuote = /^On .* wrote:\s*$/.test(firstLine);
  const split = splitQuotedText(onlyQuote ? `.\n\n${text}` : text);
  if (split.quote === null || !split.quote.startsWith("On ")) {
    return { body: text, quote: null };
  }
  return { body: onlyQuote ? "" : split.body, quote: split.quote };
}

/** The body as typed: the plain-text part without the signature this app adds after `-- `. */
export function markdownFromText(text: string | null): string {
  if (text === null) return "";
  const normalized = text.replace(/\r\n?/g, "\n");
  const separator = `\n${SIGNATURE_SEPARATOR}\n`;
  const index = normalized.lastIndexOf(separator);
  if (normalized.startsWith(`${SIGNATURE_SEPARATOR}\n`) && index === -1) {
    return "";
  }
  return (index === -1 ? normalized : normalized.slice(0, index)).replace(
    /\n+$/,
    "",
  );
}

/** A saved draft's files still to fetch: attachments and `cid:` images. */
export type DraftFiles = { attachments: Attachment[]; inline: InlinePart[] };

/**
 * A Gmail draft read back into a composer (its files come separately, see `DraftFiles`). A reply's
 * quote is left out of the markdown and returned as text as `quoteText`; the caller rebuilds the
 * quote from the replied-to message (`quote` in the snapshot is null until then).
 */
export function snapshotFromDraft(
  draftId: string,
  message: GmailMessage,
  localId: string,
): { snapshot: ComposeSnapshot; files: DraftFiles; quoteText: string | null } {
  const headers = message.payload?.headers;
  const body = message.payload
    ? extractBody(message.payload)
    : { html: null, text: null, attachments: [], inline: [] };
  return draftSnapshot(
    {
      draftId,
      messageId: message.id,
      threadId: message.threadId,
      from: parseAddressList(headerValue(headers, "From"))[0]?.email ?? "",
      to: parseAddressList(headerValue(headers, "To")),
      cc: parseAddressList(headerValue(headers, "Cc")),
      bcc: parseAddressList(headerValue(headers, "Bcc")),
      subject: headerValue(headers, "Subject")?.trim() ?? "",
      inReplyTo: headerValue(headers, "In-Reply-To"),
      references: headerValue(headers, "References"),
      text: body.text,
      html: body.html,
    },
    { attachments: body.attachments, inline: body.inline },
    localId,
  );
}

/**
 * A draft the saved copy holds (`message`, its draft id `draftId`) read back into a composer, as
 * `snapshotFromDraft` reads Gmail's; null when the saved copy lacks its headers.
 */
export function snapshotFromSaved(
  draftId: string,
  message: MessageView,
  localId: string,
): {
  snapshot: ComposeSnapshot;
  files: DraftFiles;
  quoteText: string | null;
} | null {
  const headers = message.headers;
  if (headers === undefined) return null;
  return draftSnapshot(
    {
      draftId,
      messageId: message.id,
      threadId: message.threadId,
      from: headers.from[0]?.email ?? message.from.email,
      to: headers.to,
      cc: headers.cc,
      bcc: headers.bcc ?? [],
      subject: headers.subject,
      inReplyTo: headers.inReplyTo,
      references: headers.references,
      text: message.body.text,
      html: message.body.html,
    },
    { attachments: message.body.attachments, inline: message.body.inline },
    localId,
  );
}

/** What offline editing needs to know of a saved draft (see `offlineEditable`). */
export function savedDraftParts(message: MessageView): OfflineDraftParts {
  return {
    encrypted: message.pgp !== undefined,
    headersKnown: message.headers?.bcc !== undefined,
    files: [...message.body.attachments, ...message.body.inline].map(
      (file) => ({ held: file.data !== null }),
    ),
  };
}

// A draft's fields, as read from Gmail or the saved copy, as a composer's contents.
function draftSnapshot(
  fields: {
    draftId: string;
    messageId: string;
    threadId: string;
    from: string;
    to: Mailbox[];
    cc: Mailbox[];
    bcc: Mailbox[];
    subject: string;
    inReplyTo: string | null;
    references: string | null;
    text: string | null;
    html: string | null;
  },
  files: DraftFiles,
  localId: string,
): { snapshot: ComposeSnapshot; files: DraftFiles; quoteText: string | null } {
  const inReplyTo = messageIds(fields.inReplyTo)[0] ?? null;
  const references = messageIds(fields.references);
  const isReply = inReplyTo !== null;
  const mode: ComposeMode = isReply
    ? /^(fwd?|fw)\s*:/i.test(fields.subject)
      ? "forward"
      : "reply"
    : "new";
  const split =
    mode === "reply"
      ? splitDraftText(fields.text)
      : { body: fields.text, quote: null };
  return {
    quoteText: split.quote,
    snapshot: {
      localId,
      mode,
      threadId: isReply ? fields.threadId : null,
      draftId: fields.draftId,
      messageId: fields.messageId,
      draftThreadId: fields.threadId,
      from: fields.from,
      to: fields.to,
      cc: fields.cc,
      bcc: fields.bcc,
      subject: fields.subject,
      markdown: draftLinks(markdownFromText(split.body), fields.html),
      inReplyTo,
      references,
      attachments: [],
      inlineImages: [],
      quote: null,
    },
    files,
  };
}

/** The recipients line of a scheduled or failed message: names, else addresses. */
export function recipientsLine(mailboxes: readonly Mailbox[]): string {
  return mailboxes.map((mailbox) => mailbox.name || mailbox.email).join(", ");
}
