import { describe, expect, it } from "bun:test";
import { buildMime, randomBoundary } from "@inbox/shared/compose";
import * as pgp from "@inbox/shared/pgp";
import type { GmailMessage } from "../gmail/types";
import { threadDetail } from "../thread-detail";
import { newWrappingKey } from "./device-key";
import {
  decryptedDraft,
  decryptedSubject,
  decryptedView,
  ENCRYPTED_PLACEHOLDER,
  isEncryptedMessage,
  mismatchWarning,
  openDetail,
  pgpBadge,
  rowText,
  sealDetail,
} from "./message-crypto";
import { TEST_KEYS } from "./test-keys";

const { alice, bob, carol } = TEST_KEYS;

function base64Url(text: string): string {
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const pgpMime: GmailMessage = {
  id: "m1",
  threadId: "t1",
  internalDate: "1800000000000",
  snippet: "This is an OpenPGP/MIME encrypted message",
  payload: {
    mimeType: "multipart/encrypted",
    headers: [
      { name: "From", value: "Alice <test@hafa.cc>" },
      { name: "To", value: "bob@example.com" },
      { name: "Subject", value: "..." },
    ],
    parts: [
      {
        mimeType: "application/pgp-encrypted",
        body: { size: 9, data: base64Url("Version: 1") },
      },
      {
        mimeType: "application/octet-stream",
        filename: "encrypted.asc",
        body: { size: 1, attachmentId: "a" },
      },
    ],
  },
};

async function encryptedFromAlice(withAttachment = false) {
  const mime = buildMime({
    from: { name: "Alice", email: alice.address },
    to: [{ name: "Bob", email: bob.address }],
    cc: [],
    bcc: [{ name: "", email: carol.address }],
    subject: "Lease paperwork",
    markdown: "Signed copy *attached*.",
    signature: null,
    inReplyTo: null,
    references: [],
    inlineImages: [],
    attachments: withAttachment
      ? [
          {
            filename: "lease.pdf",
            mimeType: "application/pdf",
            data: new Uint8Array([37, 80, 68, 70]),
          },
        ]
      : [],
    extraHeaders: [],
    boundary: randomBoundary(),
  });
  const armored = await pgp.encryptMime(mime, {
    encryptionKeys: [bob.publicKey, carol.publicKey, alice.publicKey],
    signingKey: alice.privateKey,
    boundary: randomBoundary(),
  });
  return new TextEncoder().encode(armored);
}

describe("spotting encrypted mail", () => {
  it("knows PGP/MIME and inline PGP", () => {
    expect(isEncryptedMessage(pgpMime)).toBe(true);
    const inline: GmailMessage = {
      id: "m2",
      threadId: "t1",
      payload: {
        mimeType: "text/plain",
        headers: [],
        body: {
          size: 1,
          data: base64Url(
            "hi\n-----BEGIN PGP MESSAGE-----\nabc\n-----END PGP MESSAGE-----",
          ),
        },
      },
    };
    expect(isEncryptedMessage(inline)).toBe(true);
    const plain = {
      ...inline,
      payload: { ...inline.payload!, body: { size: 1, data: base64Url("hi") } },
    };
    expect(isEncryptedMessage(plain)).toBe(false);
  });

  it("shows an empty, locked body in the thread until decrypted", () => {
    const [message] = threadDetail({ id: "t1", messages: [pgpMime] }).messages;
    expect(message?.pgp).toEqual({ status: "locked", signature: null });
    expect(message?.body.attachments).toEqual([]);
    expect(message?.snippet).toBe("");
  });

  it("names rows", () => {
    expect(rowText("...", "x", undefined)).toEqual({
      subject: ENCRYPTED_PLACEHOLDER,
      snippet: "",
      encrypted: true,
    });
    expect(rowText("...", "x", "Lease").subject).toBe("Lease");
    expect(rowText("Hi", "there", undefined)).toEqual({
      subject: "Hi",
      snippet: "there",
      encrypted: false,
    });
  });
});

describe("badges", () => {
  it("follows the signature", () => {
    expect(
      pgpBadge({ status: "decrypted", signature: "valid" }, "Ana"),
    ).toMatchObject({
      lock: true,
      signed: true,
      label: "Encrypted · Signed by Ana",
      warning: null,
    });
    expect(
      pgpBadge({ status: "decrypted", signature: "invalid" }, "Ana").warning,
    ).toBe("The signature doesn't match the key we have for Ana");
    expect(
      pgpBadge({ status: "decrypted", signature: "unknown-key" }, "Ana"),
    ).toMatchObject({
      signed: false,
      warning: null,
      problem: null,
    });
  });

  it("warns when the inside names other people than the outside and no signature vouches", () => {
    expect(
      pgpBadge(
        { status: "decrypted", signature: "unknown-key", mismatched: ["from"] },
        "Ana",
      ).warning,
    ).toBe(
      "Inside, this message names a different sender, and no signature confirms it. Showing the addresses it was sent with.",
    );
    expect(mismatchWarning(["to", "cc"])).toContain("different recipients");
    expect(mismatchWarning(["from", "cc"])).toContain(
      "a different sender and recipients",
    );
    expect(mismatchWarning([])).toBeNull();
    // A bad signature says more than a mismatch.
    expect(
      pgpBadge(
        { status: "decrypted", signature: "invalid", mismatched: ["from"] },
        "Ana",
      ).warning,
    ).toBe("The signature doesn't match the key we have for Ana");
  });

  it("explains what can't be opened", () => {
    expect(
      pgpBadge({ status: "wrong-key", signature: null }, "Ana").problem,
    ).toBe("Can't open: encrypted to another key");
    expect(
      pgpBadge({ status: "malformed", signature: null }, "Ana").problem,
    ).toBe("Can't open this encrypted message");
    expect(pgpBadge({ status: "locked", signature: null }, "Ana").unlock).toBe(
      true,
    );
  });
});

describe("decrypted messages", () => {
  it("show the protected subject, text and attachments", async () => {
    const decrypted = await pgp.decryptMessage(await encryptedFromAlice(true), {
      decryptionKeys: [bob.privateKey],
      verificationKeys: [alice.publicKey],
    });
    const [locked] = threadDetail({ id: "t1", messages: [pgpMime] }).messages;
    const view = decryptedView(locked!, decrypted, pgp);
    expect(view.pgp).toEqual({ status: "decrypted", signature: "valid" });
    expect(view.headers?.subject).toBe("Lease paperwork");
    expect(view.body.text).toContain("Signed copy *attached*.");
    expect(view.body.html).toContain("<b>attached</b>");
    expect(
      view.body.attachments.map((file) => [file.filename, file.size]),
    ).toEqual([["lease.pdf", 4]]);
    expect(view.snippet).toBe("Signed copy *attached*.");
    expect(view.to.map((person) => person.email)).toEqual([bob.address]);
    expect(
      decryptedSubject({
        id: "t1",
        historyId: "1",
        subject: "...",
        messages: [view],
        drafts: [],
      }),
    ).toBe("Lease paperwork");
  });

  it("are sealed in the offline cache and open again with the device key", async () => {
    const decrypted = await pgp.decryptMessage(await encryptedFromAlice(), {
      decryptionKeys: [bob.privateKey],
      verificationKeys: [],
    });
    const [locked] = threadDetail({ id: "t1", messages: [pgpMime] }).messages;
    const detail = {
      id: "t1",
      historyId: "1",
      subject: "...",
      messages: [decryptedView(locked!, decrypted, pgp)],
      drafts: [],
    };
    const key = await newWrappingKey();
    const sealed = await sealDetail(detail, key);
    const stored = JSON.stringify(sealed, (_, value) =>
      value instanceof Uint8Array ? "[bytes]" : value,
    );
    expect(stored).not.toContain("Lease");
    expect(stored).not.toContain("Signed copy");
    expect(sealed.messages[0]?.pgp?.status).toBe("locked");
    const opened = await openDetail(sealed, key);
    expect(opened.messages[0]?.body.text).toContain("Signed copy");
    expect(opened.messages[0]?.pgp).toEqual({
      status: "decrypted",
      signature: "unknown-key",
    });
    const flagged = await sealDetail(
      {
        ...detail,
        messages: detail.messages.map((message) => ({
          ...message,
          pgp: {
            status: "decrypted" as const,
            signature: "unknown-key" as const,
            mismatched: ["from"],
          },
        })),
      },
      key,
    );
    expect(
      (await openDetail(flagged, key)).messages[0]?.pgp?.mismatched,
    ).toEqual(["from"]);
    const elsewhere = await openDetail(sealed, await newWrappingKey());
    expect(elsewhere.messages[0]?.pgp?.status).toBe("locked");
    const withoutKey = await sealDetail(detail, null);
    expect(withoutKey.messages[0]?.pgp?.sealed).toBeUndefined();
    expect(withoutKey.messages[0]?.body.text).toBeNull();
  });

  it("rebuild a draft with its protected headers and the outer Bcc", async () => {
    const decrypted = await pgp.decryptMessage(await encryptedFromAlice(), {
      decryptionKeys: [alice.privateKey],
      verificationKeys: [],
    });
    const draft = decryptedDraft(
      {
        ...pgpMime,
        payload: {
          ...pgpMime.payload!,
          headers: [
            ...pgpMime.payload!.headers!,
            { name: "Bcc", value: carol.address },
          ],
        },
      },
      decrypted,
      pgp,
    );
    const headers = Object.fromEntries(
      (draft.payload?.headers ?? []).map((header) => [
        header.name,
        header.value,
      ]),
    );
    expect(headers.Subject).toBe("Lease paperwork");
    expect(headers.Bcc).toBe(carol.address);
    expect(headers.To).toContain(bob.address);
  });
});
