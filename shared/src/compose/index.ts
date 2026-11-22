// Pure message building shared by the web and (as a port) Android: text styles, MIME, reply headers.

export {
  addressKey,
  formatAddressList,
  formatMailbox,
  InvalidAddressError,
  isPlainAddress,
  type Mailbox,
} from "./address";
export {
  base64,
  base64Lines,
  base64Url,
  CRLF,
  encodedWords,
  encodeHeaderText,
  foldHeader,
  gmailRaw,
  MAX_BODY_LINE,
  MAX_HEADER_LINE,
  normalizeNewlines,
  quotedPrintable,
  randomBoundary,
  utf8,
} from "./encoding";
export {
  type InlineStyle,
  parseInlineStyles,
  plainText,
  type StyleSpan,
  type WrapEdit,
  wrapSelection,
} from "./inline-style";
export {
  type BodyParts,
  bodyParts,
  markdownToHtml,
  markdownToText,
  normalizeHtml,
  SIGNATURE_SEPARATOR,
} from "./markdown";
export {
  MARKDOWN_SPAN_KINDS,
  type MarkdownSpan,
  type MarkdownSpanKind,
  markdownSpans,
} from "./markdown-spans";
export {
  buildMime,
  type CalendarPart,
  type ComposeInput,
  InvalidHeaderError,
  type MimeAttachment,
  type MimeInlineImage,
} from "./mime";
export {
  QUOTE_BLOCKQUOTE_STYLE,
  type QuotedMessage,
  type QuoteSplit,
  quotableHtml,
  quoteAttribution,
  quotedContentIds,
  quoteHtml,
  quoteText,
  splitQuotedHtml,
  splitQuotedText,
} from "./quote";
export {
  type AnswerHeaders,
  answerHeaders,
  forwardSubject,
  messageIds,
  offersReplyAll,
  type ReplyHeaders,
  type ReplyParticipants,
  type ReplyRecipients,
  type ReplySource,
  replyFromAddress,
  replyHeaders,
  replyRecipients,
  replySubject,
} from "./reply";
export {
  markdownToRich,
  normalizeRuns,
  parseRuns,
  RICH_MARKS,
  type RichBlock,
  type RichMark,
  type RichRun,
  richToHtml,
  richToMarkdown,
  runsToHtml,
  runsToMarkdown,
} from "./rich";
