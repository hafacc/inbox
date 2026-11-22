import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  type AnswerHeaders,
  answerHeaders,
  base64,
  base64Lines,
  bodyParts,
  buildMime,
  type ComposeInput,
  encodeHeaderText,
  foldHeader,
  formatMailbox,
  forwardSubject,
  gmailRaw,
  InvalidAddressError,
  InvalidHeaderError,
  type Mailbox,
  markdownToHtml,
  markdownToText,
  normalizeHtml,
  offersReplyAll,
  type QuotedMessage,
  quotableHtml,
  quoteAttribution,
  quotedContentIds,
  quotedPrintable,
  quoteText,
  type ReplyParticipants,
  type ReplySource,
  randomBoundary,
  replyFromAddress,
  replyHeaders,
  replyRecipients,
  replySubject,
  splitQuotedHtml,
  splitQuotedText,
  utf8,
} from "./index";

type FileFixture = { filename: string; mimeType: string; data: string };

type QuoteFixture = Omit<QuotedMessage, "date"> & { dateLocal: string };

function quoteOf(fixture: QuoteFixture): QuotedMessage {
  const { dateLocal, ...rest } = fixture;
  return { ...rest, date: new Date(dateLocal).getTime() };
}

type Fixtures = {
  markdown: { name: string; markdown: string; html: string; text: string }[];
  body: {
    name: string;
    body: string;
    signature: string | null;
    quote?: QuoteFixture;
    html: string;
    text: string;
  }[];
  attribution: { from: Mailbox; dateLocal: string; expect: string }[];
  quoteText: { name: string; quote: QuoteFixture; expect: string }[];
  quotableHtml: { name: string; html: string; expect: string }[];
  quotedContentIds: { name: string; html: string | null; expect: string[] }[];
  splitText: {
    name: string;
    text: string;
    body: string;
    quote: string | null;
  }[];
  splitHtml: {
    name: string;
    html: string;
    body: string;
    quote: string | null;
  }[];
  replySubject: { subject: string; expect: string }[];
  forwardSubject: { subject: string; expect: string }[];
  replyHeaders: {
    name: string;
    source: ReplySource;
    expect: ReturnType<typeof replyHeaders>;
  }[];
  answerHeaders: {
    name: string;
    source: ReplySource;
    forward: boolean;
    expect: AnswerHeaders;
    mime: string;
  }[];
  recipients: {
    name: string;
    original: ReplyParticipants;
    own: string[];
    replyAll: boolean;
    expect: { to: Mailbox[]; cc: Mailbox[] };
  }[];
  offersReplyAll: {
    name: string;
    original: ReplyParticipants;
    own: string[];
    expect: boolean;
  }[];
  replyFrom: {
    name: string;
    original: ReplyParticipants;
    own: string[];
    fallback: string;
    expect: string;
  }[];
  mailbox: { mailbox: Mailbox; expect: string }[];
  invalidMailbox: { name: string; mailbox: Mailbox }[];
  headerText: { text: string; expect: string }[];
  fold: { name: string; value: string; expect: string }[];
  qp: { text: string; expect: string }[];
  base64Lines: { utf8: string; expect: string }[];
  mime: { name: string; input: MimeInputFixture; expect: string }[];
  invalidHeader: { name: string; input: MimeInputFixture }[];
};

type MimeInputFixture = Omit<
  ComposeInput,
  "inlineImages" | "attachments" | "quote"
> & {
  quote?: QuoteFixture;
  inlineImages: (FileFixture & { contentId: string })[];
  attachments: FileFixture[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/compose-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

function decodeBase64(text: string): Uint8Array {
  return Uint8Array.from(atob(text), (character) => character.charCodeAt(0));
}

function mimeInput(fixture: MimeInputFixture): ComposeInput {
  const { quote, ...rest } = fixture;
  return {
    ...rest,
    quote: quote === undefined ? null : quoteOf(quote),
    inlineImages: fixture.inlineImages.map((image) => ({
      ...image,
      data: decodeBase64(image.data),
    })),
    attachments: fixture.attachments.map((file) => ({
      ...file,
      data: decodeBase64(file.data),
    })),
  };
}

describe("compose fixtures (must match Android)", () => {
  for (const fixture of fixtures.markdown) {
    test(`markdown: ${fixture.name}`, () => {
      expect(normalizeHtml(markdownToHtml(fixture.markdown))).toBe(
        fixture.html,
      );
      expect(markdownToText(fixture.markdown)).toBe(fixture.text);
    });
  }

  for (const fixture of fixtures.body) {
    test(`body: ${fixture.name}`, () => {
      const parts = bodyParts(
        fixture.body,
        fixture.signature,
        fixture.quote === undefined ? null : quoteOf(fixture.quote),
      );
      expect(normalizeHtml(parts.html)).toBe(fixture.html);
      expect(parts.text).toBe(fixture.text);
    });
  }

  for (const fixture of fixtures.attribution) {
    test(`quoteAttribution: ${fixture.expect}`, () => {
      expect(
        quoteAttribution(fixture.from, new Date(fixture.dateLocal).getTime()),
      ).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.quoteText) {
    test(`quoteText: ${fixture.name}`, () => {
      expect(quoteText(quoteOf(fixture.quote))).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.quotableHtml) {
    test(`quotableHtml: ${fixture.name}`, () => {
      expect(quotableHtml(fixture.html)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.quotedContentIds) {
    test(`quotedContentIds: ${fixture.name}`, () => {
      expect(quotedContentIds(fixture.html)).toEqual(fixture.expect);
    });
  }

  for (const fixture of fixtures.splitText) {
    test(`splitQuotedText: ${fixture.name}`, () => {
      expect(splitQuotedText(fixture.text)).toEqual({
        body: fixture.body,
        quote: fixture.quote,
      });
    });
  }

  for (const fixture of fixtures.splitHtml) {
    test(`splitQuotedHtml: ${fixture.name}`, () => {
      expect(splitQuotedHtml(fixture.html)).toEqual({
        body: fixture.body,
        quote: fixture.quote,
      });
    });
  }

  for (const fixture of fixtures.replySubject) {
    test(`replySubject: ${JSON.stringify(fixture.subject)}`, () => {
      expect(replySubject(fixture.subject)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.forwardSubject) {
    test(`forwardSubject: ${JSON.stringify(fixture.subject)}`, () => {
      expect(forwardSubject(fixture.subject)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.replyHeaders) {
    test(`replyHeaders: ${fixture.name}`, () => {
      expect(replyHeaders(fixture.source)).toEqual(fixture.expect);
    });
  }

  for (const fixture of fixtures.answerHeaders) {
    test(`answerHeaders: ${fixture.name}`, () => {
      const answer = answerHeaders(fixture.source, fixture.forward);
      expect(answer).toEqual(fixture.expect);
      const head =
        buildMime({
          from: { name: "", email: "test@hafa.cc" },
          to: [{ name: "", email: "bob@example.com" }],
          cc: [],
          bcc: [],
          subject: answer.subject,
          markdown: "Hi",
          signature: null,
          inReplyTo: answer.inReplyTo,
          references: answer.references,
          inlineImages: [],
          attachments: [],
          extraHeaders: [],
          boundary: "b0",
        }).split("\r\n\r\n")[0] ?? "";
      expect(
        head.slice(head.indexOf("Subject:"), head.indexOf("\r\nMIME-Version:")),
      ).toBe(fixture.mime);
    });
  }

  for (const fixture of fixtures.recipients) {
    test(`replyRecipients: ${fixture.name}`, () => {
      expect(
        replyRecipients(fixture.original, fixture.own, fixture.replyAll),
      ).toEqual(fixture.expect);
    });
  }

  for (const fixture of fixtures.offersReplyAll) {
    test(`offersReplyAll: ${fixture.name}`, () => {
      expect(offersReplyAll(fixture.original, fixture.own)).toBe(
        fixture.expect,
      );
    });
  }

  for (const fixture of fixtures.replyFrom) {
    test(`replyFromAddress: ${fixture.name}`, () => {
      expect(
        replyFromAddress(fixture.original, fixture.own, fixture.fallback),
      ).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.mailbox) {
    test(`formatMailbox: ${JSON.stringify(fixture.mailbox.name)}`, () => {
      expect(formatMailbox(fixture.mailbox)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.invalidMailbox) {
    test(`formatMailbox refuses: ${fixture.name}`, () => {
      expect(() => formatMailbox(fixture.mailbox)).toThrow(InvalidAddressError);
    });
  }

  for (const fixture of fixtures.headerText) {
    test(`encodeHeaderText: ${JSON.stringify(fixture.text)}`, () => {
      expect(encodeHeaderText(fixture.text)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.fold) {
    test(`foldHeader: ${fixture.value.slice(0, 30)}`, () => {
      expect(foldHeader(fixture.name, fixture.value)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.qp) {
    test(`quotedPrintable: ${JSON.stringify(fixture.text.slice(0, 30))}`, () => {
      expect(quotedPrintable(fixture.text)).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.base64Lines) {
    test(`base64Lines: ${fixture.utf8.length} characters`, () => {
      expect(base64Lines(utf8(fixture.utf8))).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.mime) {
    test(`buildMime: ${fixture.name}`, () => {
      expect(buildMime(mimeInput(fixture.input))).toBe(fixture.expect);
    });
  }

  for (const fixture of fixtures.invalidHeader) {
    test(`buildMime refuses: ${fixture.name}`, () => {
      expect(() => buildMime(mimeInput(fixture.input))).toThrow(
        InvalidHeaderError,
      );
    });
  }
});

describe("encodings", () => {
  test("base64 matches the platform's", () => {
    for (const text of ["", "a", "ab", "abc", "Grüße 🙂", "x".repeat(100)]) {
      const bytes = utf8(text);
      expect(base64(bytes)).toBe(Buffer.from(bytes).toString("base64"));
    }
  });

  test("quoted-printable lines stay within 76 characters", () => {
    const encoded = quotedPrintable(`${"é".repeat(60)} ${"a ".repeat(60)}`);
    for (const line of encoded.split("\r\n")) {
      expect(line.length).toBeLessThanOrEqual(76);
    }
  });

  test("gmailRaw is base64url without padding", () => {
    const raw = gmailRaw("Subject: ??>\r\n\r\nhi");
    expect(raw).not.toMatch(/[+/=]/);
    expect(Buffer.from(raw, "base64url").toString("utf8")).toBe(
      "Subject: ??>\r\n\r\nhi",
    );
  });

  test("randomBoundary gives 24 hex digits, different each time", () => {
    const first = randomBoundary();
    expect(first).toMatch(/^[0-9a-f]{24}$/);
    expect(randomBoundary()).not.toBe(first);
  });
});

describe("buildMime", () => {
  const input: ComposeInput = {
    from: { name: "Ana", email: "test@hafa.cc" },
    to: [{ name: "Bob", email: "bob@example.com" }],
    cc: [],
    bcc: [],
    subject: "Hi",
    markdown: "Hello",
    signature: null,
    inReplyTo: null,
    references: [],
    inlineImages: [],
    attachments: [],
    extraHeaders: [],
    boundary: "t",
  };

  test("uses CRLF only and ends with one", () => {
    const message = buildMime(input);
    expect(message.replace(/\r\n/g, "")).not.toMatch(/[\r\n]/);
    expect(message.endsWith("\r\n")).toBe(true);
  });

  test("throws InvalidAddressError for an address that would add a header", () => {
    expect(() =>
      buildMime({
        ...input,
        cc: [{ name: "", email: "eve@example.net\r\nBcc: x@example.net" }],
      }),
    ).toThrow(InvalidAddressError);
  });

  test("leaves Date and Message-ID to Gmail and the server", () => {
    const message = buildMime(input);
    expect(message).not.toMatch(/^Date:/m);
    expect(message).not.toMatch(/^Message-ID:/im);
  });
});

describe("large inputs", () => {
  test("splitQuotedHtml stays fast with thousands of quote markers", () => {
    const quote =
      '<div class="gmail_quote"><blockquote type="cite">x</blockquote></div>';
    const html = `<p>reply</p>${quote.repeat(5000)}<p>trailing text</p>`;
    const started = performance.now();
    expect(splitQuotedHtml(html).quote).toBeNull();
    expect(performance.now() - started).toBeLessThan(1000);
  });

  test("base64 of a few megabytes matches Buffer and stays fast", () => {
    const bytes = new Uint8Array(4 * 1024 * 1024).map((_, index) => index * 7);
    const started = performance.now();
    const encoded = base64(bytes);
    expect(performance.now() - started).toBeLessThan(1000);
    expect(encoded).toBe(Buffer.from(bytes).toString("base64"));
  });
});
