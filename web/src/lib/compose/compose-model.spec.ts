import { expect, test } from "bun:test";
import {
  base64Url,
  bodyParts,
  buildMime,
  InvalidAddressError,
  InvalidHeaderError,
  type QuotedMessage,
  utf8,
} from "@inbox/shared/compose";
import { offlineEditable } from "@inbox/shared/offline-drafts";
import type { GmailMessage } from "../gmail/types";
import { threadDetail } from "../thread-detail";
import {
  answerFields,
  blankSnapshot,
  buildProblem,
  type ComposeSnapshot,
  composeInput,
  forwardMarkdown,
  htmlToText,
  isBlank,
  MAX_MESSAGE_BYTES,
  markdownFromText,
  quoteFromText,
  referencedImages,
  type SourceMessage,
  savedDraftParts,
  sendProblem,
  snapshotFromDraft,
  snapshotFromSaved,
  splitDraftText,
} from "./compose-model";

const SOURCE: SourceMessage = {
  id: "m1",
  messageId: "<b@x.example.net>",
  references: "<a@x.example.net>",
  inReplyTo: "<a@x.example.net>",
  subject: "Lease paperwork",
  from: [{ name: "Sam Okafor", email: "sam@proton.example.net" }],
  replyTo: [],
  to: [{ name: "", email: "test@hafa.cc" }],
  cc: [
    { name: "Dana", email: "dana@x.example.net" },
    { name: "", email: "jordan.lee@example.com" },
  ],
  date: new Date("2026-11-04T08:47:00").getTime(),
  text: "Hi Jordan,\n\nSigned copy *attached*.\n",
};
const OWN = ["test@hafa.cc", "jordan.lee@example.com"];

test("reply goes to the sender, from the address it was sent to", () => {
  expect(answerFields(SOURCE, "reply", OWN, "jordan.lee@example.com")).toEqual({
    to: [{ name: "Sam Okafor", email: "sam@proton.example.net" }],
    cc: [],
    subject: "Re: Lease paperwork",
    inReplyTo: "<b@x.example.net>",
    references: ["<a@x.example.net>", "<b@x.example.net>"],
    from: "test@hafa.cc",
  });
});

test("reply all copies the others but never the user", () => {
  const fields = answerFields(SOURCE, "replyAll", OWN, "x@x.example.net");
  expect(fields.cc).toEqual([{ name: "Dana", email: "dana@x.example.net" }]);
});

test("forward keeps threading but no recipients", () => {
  const fields = answerFields(SOURCE, "forward", OWN, "x@x.example.net");
  expect(fields.to).toEqual([]);
  expect(fields.subject).toBe("Fwd: Lease paperwork");
  expect(fields.inReplyTo).toBe("<b@x.example.net>");
});

test("a message without a Message-ID gets no threading headers", () => {
  const fields = answerFields(
    { ...SOURCE, messageId: "" },
    "reply",
    OWN,
    "x@x.example.net",
  );
  expect(fields.inReplyTo).toBeNull();
  expect(fields.references).toEqual([]);
});

test("a forward quotes the original under its headers", () => {
  const now = new Date("2026-11-04T12:00:00").getTime();
  expect(forwardMarkdown(SOURCE, now)).toBe(
    [
      "",
      "",
      "---------- Forwarded message ---------",
      "From: Sam Okafor <sam@proton.example.net>",
      "Date: Today 08:47",
      "Subject: Lease paperwork",
      "To: test@hafa.cc",
      "Cc: Dana <dana@x.example.net>, jordan.lee@example.com",
      "",
      "> Hi Jordan,",
      ">",
      "> Signed copy *attached*.",
      "",
    ].join("\n"),
  );
});

test("HTML bodies forward as text", () => {
  expect(
    htmlToText(
      "<p>Hi&nbsp;<b>you</b></p><p>a &amp; b<br>c</p><style>x</style>",
    ),
  ).toBe("Hi you\na & b\nc");
});

test("the typed body comes back from a draft's text part without the signature", () => {
  expect(markdownFromText("Hi **there**\r\n\r\n-- \r\n— Jordan")).toBe(
    "Hi **there**",
  );
  expect(markdownFromText("No signature\n")).toBe("No signature");
  expect(markdownFromText("-- \n— Jordan")).toBe("");
  expect(markdownFromText(null)).toBe("");
});

const IMAGE = {
  id: "i1",
  contentId: "pic@inbox",
  filename: "pic.png",
  mimeType: "image/png",
  data: new Uint8Array([1, 2, 3]),
};

test("images the body no longer mentions are left out", () => {
  expect(referencedImages("![a](cid:pic@inbox)", [IMAGE])).toEqual([IMAGE]);
  expect(referencedImages("gone", [IMAGE])).toEqual([]);
});

function snapshot(patch: Partial<ComposeSnapshot>): ComposeSnapshot {
  return { ...blankSnapshot("l1", "test@hafa.cc"), ...patch };
}

test("blank and sendable", () => {
  expect(isBlank(snapshot({}))).toBe(true);
  expect(isBlank(snapshot({ markdown: " x " }))).toBe(false);
  expect(sendProblem(snapshot({}), 10)).toBe("Add at least one recipient.");
  const addressed = snapshot({ bcc: [{ name: "", email: "a@x.example.net" }] });
  expect(sendProblem(addressed, 10)).toBeNull();
  expect(sendProblem(addressed, MAX_MESSAGE_BYTES + 1)).toContain("35 MB");
});

test("the built message carries the signature and only referenced images", () => {
  const mime = buildMime(
    composeInput(
      snapshot({
        to: [{ name: "Maya Chen", email: "maya@x.example.net" }],
        subject: "Saturday photos",
        markdown: "Look ![table](cid:pic@inbox)",
        inlineImages: [IMAGE, { ...IMAGE, id: "i2", contentId: "old@inbox" }],
      }),
      { name: "Jordan", email: "test@hafa.cc" },
      "— Jordan",
      "b1",
    ),
  );
  expect(mime).toContain("From: Jordan <test@hafa.cc>");
  expect(mime).toContain("To: Maya Chen <maya@x.example.net>");
  expect(mime).toContain("Content-ID: <pic@inbox>");
  expect(mime).not.toContain("old@inbox");
  expect(mime).toContain("multipart/related");
  expect(mime).toContain("--=20\r\n=E2=80=94 Jordan");
});

test("an empty signature adds nothing", () => {
  const input = composeInput(
    snapshot({}),
    { name: "", email: "a@x.example.net" },
    " ",
    "b",
  );
  expect(input.signature).toBeNull();
});

test("a saved draft reads back into a composer", () => {
  const text = base64Url(utf8("Hi **there**\r\n\r\n-- \r\n— Jordan"));
  const message: GmailMessage = {
    id: "dm1",
    threadId: "t1",
    payload: {
      mimeType: "multipart/mixed",
      headers: [
        { name: "From", value: "Jordan <test@hafa.cc>" },
        { name: "To", value: "Sam Okafor <sam@proton.example.net>" },
        { name: "Bcc", value: "me@x.example.net" },
        { name: "Subject", value: "Re: Lease paperwork" },
        { name: "In-Reply-To", value: "<b@x.example.net>" },
        { name: "References", value: "<a@x.example.net> <b@x.example.net>" },
      ],
      parts: [
        {
          mimeType: "multipart/alternative",
          parts: [
            { mimeType: "text/plain", body: { size: 10, data: text } },
            {
              mimeType: "text/html",
              body: { size: 10, data: base64Url(utf8("<p>Hi</p>")) },
            },
          ],
        },
        {
          mimeType: "application/pdf",
          filename: "lease.pdf",
          headers: [{ name: "Content-Disposition", value: "attachment" }],
          body: { size: 4, attachmentId: "att1" },
        },
      ],
    },
  };
  const { snapshot: read, files } = snapshotFromDraft("d1", message, "l9");
  expect(read).toMatchObject({
    localId: "l9",
    draftId: "d1",
    messageId: message.id,
    mode: "reply",
    threadId: "t1",
    from: "test@hafa.cc",
    to: [{ name: "Sam Okafor", email: "sam@proton.example.net" }],
    bcc: [{ name: "", email: "me@x.example.net" }],
    subject: "Re: Lease paperwork",
    markdown: "Hi **there**",
    inReplyTo: "<b@x.example.net>",
    references: ["<a@x.example.net>", "<b@x.example.net>"],
  });
  expect(files.attachments.map((file) => file.attachmentId)).toEqual(["att1"]);

  // The saved copy reads back the same, but its attachment's bytes aren't saved.
  const saved = threadDetail({
    id: "t1",
    messages: [{ ...message, labelIds: ["DRAFT"] }],
  }).drafts[0]!;
  const offline = snapshotFromSaved("d1", saved, "l9");
  expect(offline?.snapshot).toEqual(read);
  expect(offlineEditable(savedDraftParts(saved))).toBe(false);
  const plain = threadDetail({
    id: "t1",
    messages: [
      {
        ...message,
        labelIds: ["DRAFT"],
        payload: {
          ...message.payload!,
          parts: message.payload!.parts!.slice(0, 1),
        },
      },
    ],
  }).drafts[0]!;
  expect(offlineEditable(savedDraftParts(plain))).toBe(true);
  expect(
    offlineEditable(
      savedDraftParts({
        ...plain,
        headers: { ...plain.headers!, bcc: undefined },
      }),
    ),
  ).toBe(false);
});

const QUOTE: QuotedMessage = {
  from: { name: "Maya Chen", email: "maya.chen@gmail.example.com" },
  date: new Date("2026-11-02T11:50:00").getTime(),
  text: "Lunch on Friday?\n\nMaya",
  html: '<div>Lunch on Friday?<img src="cid:ii_map"></div>',
};

test("a reply carries its quote and the original's images the quote shows", () => {
  const mime = buildMime(
    composeInput(
      snapshot({
        to: [{ name: "", email: "maya.chen@gmail.example.com" }],
        markdown: "Friday works.",
        quote: QUOTE,
        inlineImages: [
          { ...IMAGE, contentId: "ii_map" },
          { ...IMAGE, id: "i3", contentId: "unused@inbox" },
        ],
      }),
      { name: "Jordan", email: "test@hafa.cc" },
      null,
      "b1",
    ),
  );
  expect(mime).toContain("Content-ID: <ii_map>");
  expect(mime).not.toContain("unused@inbox");
  expect(mime).toContain("gmail_quote");
});

test("a reply draft reads back without its quote in the markdown", () => {
  for (const signature of ["— Jordan", null]) {
    for (const body of ["Friday works.", ""]) {
      const { text } = bodyParts(body, signature, QUOTE);
      const split = splitDraftText(text);
      expect(split.quote).toStartWith("On Mon, Nov 2, 2026 at 11:50");
      expect(markdownFromText(split.body)).toBe(body);
    }
  }
  expect(splitDraftText("Just text")).toEqual({
    body: "Just text",
    quote: null,
  });
});

test("a quote whose message is gone is rebuilt from its text", () => {
  const { text } = bodyParts("Yes", null, QUOTE);
  const quote = splitDraftText(text).quote!;
  expect(quoteFromText(quote)).toEqual({
    from: QUOTE.from,
    date: QUOTE.date,
    text: QUOTE.text,
    html: null,
  });
  expect(quoteFromText("Some other line:")).toBeNull();
});

test("an invalid address in a message reads as something to fix; other failures don't", () => {
  expect(buildProblem(new InvalidAddressError("ana@ex ample.com"))).toBe(
    "ana@ex ample.com isn't a valid email address. Fix or remove it.",
  );
  expect(buildProblem(new InvalidAddressError(" "))).toBe(
    "One of the addresses is empty. Remove it and try again.",
  );
  expect(buildProblem(new InvalidHeaderError("In-Reply-To"))).toBe(
    "The message's In-Reply-To header can't be sent safely.",
  );
  expect(buildProblem(new Error("boom"))).toBeNull();
});
