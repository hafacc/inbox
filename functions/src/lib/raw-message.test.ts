import { describe, expect, it } from "bun:test";
import {
  addressesIn,
  decodeRaw,
  decodeWords,
  encodeRaw,
  fromAddress,
  headerProblem,
  headerValue,
  newMessageId,
  ownAddressProblem,
  prepareForSmtp,
  rfc5322Date,
  splitMessage,
} from "./raw-message.js";

const MESSAGE = [
  "From: =?UTF-8?B?QW5hIEzDs3Bleg==?= <Test@Hafa.cc>",
  "To: Bob <bob@example.com>,",
  ' "Lopez, Cat" <cat@example.com>',
  "Cc: team: dan@example.com, eve@example.com;",
  "Bcc: secret@example.com",
  "Subject: Hi",
  "MIME-Version: 1.0",
  "Content-Type: text/plain; charset=UTF-8",
  "",
  "Body line",
  "",
  "Second: not a header",
  "",
].join("\r\n");

describe("splitMessage", () => {
  it("unfolds headers and keeps the body untouched", () => {
    const { headers, body, newline } = splitMessage(Buffer.from(MESSAGE));
    expect(newline).toBe("\r\n");
    expect(headerValue(headers, "to")).toBe(
      'Bob <bob@example.com>, "Lopez, Cat" <cat@example.com>',
    );
    expect(headers.find((header) => header.name === "To")?.text).toBe(
      'To: Bob <bob@example.com>,\r\n "Lopez, Cat" <cat@example.com>',
    );
    expect(body.toString()).toBe("Body line\r\n\r\nSecond: not a header\r\n");
  });

  it("reads LF-only messages", () => {
    const { headers, body, newline } = splitMessage(
      Buffer.from("From: a@b.c\nSubject: x\n\nhi\n"),
    );
    expect(newline).toBe("\n");
    expect(headerValue(headers, "Subject")).toBe("x");
    expect(body.toString()).toBe("hi\n");
  });

  it("keeps 8-bit body bytes", () => {
    const bytes = Buffer.concat([
      Buffer.from("From: a@b.c\r\n\r\n"),
      Buffer.from([0xc3, 0xa9, 0xff]),
    ]);
    expect([...splitMessage(bytes).body]).toEqual([0xc3, 0xa9, 0xff]);
  });

  it("flags a bare CR or LF in the header block, and only there", () => {
    const stray = (text: string) =>
      splitMessage(Buffer.from(text)).strayLineBreak;
    expect(stray(MESSAGE)).toBe(false);
    expect(stray("From: a@b.c\nSubject: x\n\nhi\r\n")).toBe(false);
    expect(stray("From: a@b.c\r\nSubject: x\nBcc: z@d.e\r\n\r\nhi")).toBe(true);
    expect(stray("From: a@b.c\r\nSubject: x\rBcc: z@d.e\r\n\r\nhi")).toBe(true);
    expect(stray("From: a@b.c\nSubject: x\r\nBcc: z\n\nhi")).toBe(true);
    expect(stray("From: a@b.c\r\n\r\nline\nbare\rbody")).toBe(false);
  });
});

describe("addresses", () => {
  it("finds From lower-cased and every recipient, groups flattened", () => {
    const { headers } = splitMessage(Buffer.from(MESSAGE));
    expect(fromAddress(headers)).toBe("test@hafa.cc");
    expect(addressesIn(headers, ["To", "Cc", "Bcc"])).toEqual([
      "bob@example.com",
      "cat@example.com",
      "dan@example.com",
      "eve@example.com",
      "secret@example.com",
    ]);
  });

  it("takes From only from exactly one header holding exactly one mailbox", () => {
    const from = (header: string) =>
      fromAddress(splitMessage(Buffer.from(`${header}\r\n\r\nhi`)).headers);
    expect(from("From: Ana <test@hafa.cc>")).toBe("test@hafa.cc");
    expect(from("from: test@hafa.cc")).toBe("test@hafa.cc");
    expect(from("To: bob@example.com")).toBeUndefined();
    expect(from("From: test@hafa.cc\r\nFrom: staging@hafa.cc")).toBeUndefined();
    expect(from("From: test@hafa.cc, staging@hafa.cc")).toBeUndefined();
    expect(from("From: team: test@hafa.cc;")).toBeUndefined();
    expect(from("From: Ana")).toBeUndefined();
  });
});

describe("prepareForSmtp", () => {
  const sentAt = Date.UTC(2026, 10, 4, 13, 5, 0);

  it("sets Date, adds a Message-ID and drops Bcc from what goes out", () => {
    const prepared = prepareForSmtp(
      Buffer.from(MESSAGE),
      sentAt,
      "<dev@hafa.cc>",
    );
    const outgoing = prepared.outgoing.toString();
    expect(
      outgoing.startsWith(
        "Date: Wed, 04 Nov 2026 13:05:00 +0000\r\nMessage-ID: <dev@hafa.cc>\r\nFrom: ",
      ),
    ).toBe(true);
    expect(outgoing).not.toContain("Bcc:");
    expect(outgoing).toContain(' "Lopez, Cat" <cat@example.com>\r\nCc:');
    expect(
      outgoing.endsWith(
        "Content-Type: text/plain; charset=UTF-8\r\n\r\nBody line\r\n\r\nSecond: not a header\r\n",
      ),
    ).toBe(true);
    expect(prepared.sentCopy.toString()).toContain(
      "Bcc: secret@example.com\r\n",
    );
    expect(prepared.messageId).toBe("<dev@hafa.cc>");
    expect(prepared.envelope.from).toBe("test@hafa.cc");
    expect(prepared.envelope.to).toContain("secret@example.com");
  });

  it("keeps the draft's own Message-ID and replaces its Date", () => {
    const withIds = `Message-ID: <draft@mail.gmail.com>\r\nDate: Thu, 1 Jan 2026 00:00:00 +0000\r\n${MESSAGE}`;
    const prepared = prepareForSmtp(
      Buffer.from(withIds),
      sentAt,
      "<dev@hafa.cc>",
    );
    const outgoing = prepared.outgoing.toString();
    expect(prepared.messageId).toBe("<draft@mail.gmail.com>");
    expect(outgoing.match(/^Message-ID:/gm)).toHaveLength(1);
    expect(outgoing.match(/^Date:/gm)).toHaveLength(1);
    expect(outgoing).not.toContain("2026 00:00:00");
  });

  it("keeps line breaks out of the headers it writes", () => {
    const prepared = prepareForSmtp(
      Buffer.from(MESSAGE),
      sentAt,
      "<dev@hafa.cc>\r\nBcc: eve@example.com",
      [["Resend-Idempotency-Key", "k\r\nBcc: mallory@example.com\n"]],
    );
    const outgoing = prepared.outgoing.toString();
    expect(outgoing).not.toMatch(/^Bcc:/m);
    expect(outgoing).toContain(
      "Resend-Idempotency-Key: kBcc: mallory@example.com\r\n",
    );
    expect(prepared.messageId).toBe("<dev@hafa.cc>Bcc: eve@example.com");
  });

  it("refuses a message without a From", () => {
    expect(() =>
      prepareForSmtp(Buffer.from("To: a@b.c\r\n\r\nhi"), sentAt, "<x@y>"),
    ).toThrow();
  });
});

describe("helpers", () => {
  it("round-trips base64url", () => {
    const bytes = Buffer.from([0xfb, 0xff, 0x00, 0x41]);
    expect(encodeRaw(bytes)).toBe("-_8AQQ");
    expect([...decodeRaw(encodeRaw(bytes))]).toEqual([...bytes]);
  });

  it("formats dates in UTC with a numeric zone", () => {
    expect(rfc5322Date(0)).toBe("Thu, 01 Jan 1970 00:00:00 +0000");
  });

  it("makes unique message ids on the domain", () => {
    const first = newMessageId("hafa.cc");
    expect(first).toMatch(/^<[0-9a-f-]{36}@hafa\.cc>$/);
    expect(newMessageId("hafa.cc")).not.toBe(first);
  });
});

describe("headerProblem", () => {
  const problem = (lines: string[]) =>
    headerProblem(
      splitMessage(Buffer.from(`${lines.join("\r\n")}\r\n\r\nhi`)).headers,
    );

  it("passes what a client writes, Gmail's own headers included", () => {
    expect(problem(MESSAGE.split("\r\n\r\n")[0]!.split("\r\n"))).toBeNull();
    expect(
      problem([
        "From: a@b.c",
        "In-Reply-To: <a2@x>",
        "References: <a1@x>",
        " <a2@x>",
        "In-Reply-To:",
        "X-Note: tab\there",
        "Message-ID: <draft1@mail.gmail.com>",
        "Date: Fri, 15 Jan 2027 08:00:00 +0000",
      ]),
    ).toBeNull();
  });

  it("refuses control characters, ids that aren't, and repeated recipient lines", () => {
    expect(problem(["From: a@b.c", "Subject: a\u007fb"])).toContain(
      "Subject line holds a hidden control character",
    );
    expect(problem(["From: a@b.c", "References: <a1@x> a2@x"])).toContain(
      "References line holds something other than message ids",
    );
    expect(problem(["From: a@b.c", "In-Reply-To: <a<b>"])).not.toBeNull();
    expect(problem(["From: a@b.c", "To: x@y.z", "to: w@y.z"])).toBe(
      "The draft has more than one To line, so it can't be sent safely.",
    );
    expect(problem(["From: a@b.c", "Cc: x@y.z", "Cc: w@y.z"])).toContain(
      "more than one Cc line",
    );
  });
});

describe("ownAddressProblem", () => {
  const problem = (lines: string[]) =>
    ownAddressProblem(
      splitMessage(Buffer.from(`${lines.join("\r\n")}\r\n\r\nhi`)).headers,
      "test@hafa.cc",
      "hafa.cc",
    );

  it("passes plain names, an @ among words, and replies to the sender or elsewhere", () => {
    expect(problem(["From: Ana <test@hafa.cc>"])).toBeNull();
    expect(problem(["From: test@hafa.cc"])).toBeNull();
    expect(problem(['From: "Ana @ Home" <Test@Hafa.cc>'])).toBeNull();
    expect(
      problem([
        "From: Ana <test@hafa.cc>",
        "Reply-To: test@hafa.cc, a@gmail.example.com",
      ]),
    ).toBeNull();
  });

  it("refuses an address in the name and another @hafa.cc reply address", () => {
    expect(problem(['From: "bob@x.example.com" <test@hafa.cc>'])).toContain(
      "Change your name in Settings.",
    );
    expect(
      problem(["From: =?UTF-8?Q?Ana_=28bob=40x=2Ecom=29?= <test@hafa.cc>"]),
    ).not.toBeNull();
    expect(problem(["From: Ana <test@hafa.cc>", "Sender: staff@hafa.cc"])).toBe(
      "Mail from your @hafa.cc address can only use that address as its reply address.",
    );
  });
});

describe("decodeWords", () => {
  it("decodes B and Q words, joining adjacent ones, and keeps malformed ones", () => {
    expect(decodeWords("=?UTF-8?B?QW5hIEzDs3Bleg==?= <a@b.c>")).toBe(
      "Ana López <a@b.c>",
    );
    expect(decodeWords("=?utf-8?q?a_b?= =?utf-8?q?=C3=A9?=")).toBe("a bé");
    expect(decodeWords("=?nope?B?QQ==?=")).toBe("=?nope?B?QQ==?=");
  });
});
