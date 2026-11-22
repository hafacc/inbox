import { describe, expect, it } from "bun:test";
import type { ComposeInput, Mailbox } from "@inbox/shared/compose";
import * as pgp from "@inbox/shared/pgp";
import { TEST_KEYS } from "../pgp/test-keys";
import {
  encryptionNote,
  outgoingMessages,
  sendEncryption,
} from "./encrypt-send";

const { alice, bob, carol } = TEST_KEYS;
const ana: Mailbox = { name: "Ana Ruiz", email: "ana@example.com" };
const sam: Mailbox = { name: "Sam", email: "Sam@Example.com" };

describe("sendEncryption", () => {
  const keys = new Map<string, string | null>([
    ["ana@example.com", "K"],
    ["sam@example.com", null],
  ]);

  it("encrypts from @hafa.cc when everyone has a key and this device is unlocked", () => {
    expect(sendEncryption("user@hafa.cc", [ana], keys, true, true)).toEqual({
      kind: "encrypted",
    });
    expect(sendEncryption("user@hafa.cc", [ana], keys, true, false)).toEqual({
      kind: "locked",
    });
  });

  it("goes as is from Gmail, without a key, or without recipients", () => {
    expect(
      sendEncryption("me@gmail.example.com", [ana], keys, true, true).kind,
    ).toBe("plain");
    expect(sendEncryption("user@hafa.cc", [ana], keys, false, true).kind).toBe(
      "plain",
    );
    expect(sendEncryption("user@hafa.cc", [], keys, true, true).kind).toBe(
      "plain",
    );
  });

  it("names who has no key, and waits for lookups", () => {
    const state = sendEncryption("user@hafa.cc", [ana, sam], keys, true, true);
    expect(state).toEqual({ kind: "missing", missing: [sam] });
    expect(encryptionNote(state)).toBe("Not encrypted — Sam has no key");
    expect(
      sendEncryption(
        "user@hafa.cc",
        [ana, { name: "", email: "new@x.example.org" }],
        keys,
        true,
        true,
      ).kind,
    ).toBe("pending");
  });

  it("words the note for several people", () => {
    const two = { kind: "missing" as const, missing: [ana, sam] };
    expect(encryptionNote(two)).toBe("Not encrypted — Ana and Sam have no key");
    const three = {
      kind: "missing" as const,
      missing: [ana, sam, { name: "", email: "x@y.z" }],
    };
    expect(encryptionNote(three)).toBe("Not encrypted — 3 people have no key");
    expect(encryptionNote({ kind: "encrypted" })).toBeNull();
    expect(encryptionNote({ kind: "locked" })).toBe(
      "Encrypted once you unlock this device; Send asks",
    );
    expect(encryptionNote({ kind: "locked" }, true)).toBe(
      "Not encrypted — you chose to send without encryption",
    );
  });
});

function input(bcc: Mailbox[]): ComposeInput {
  return {
    from: { name: "Alice", email: alice.address },
    to: [{ name: "Bob", email: bob.address }],
    cc: [],
    bcc,
    subject: "Plans",
    markdown: "Saturday?",
    signature: null,
    inReplyTo: null,
    references: [],
    inlineImages: [],
    attachments: [],
    extraHeaders: [["Autocrypt", "addr=test@hafa.cc; keydata=AAAA"]],
    boundary: "b1",
  };
}

const keys = {
  recipients: new Map([
    [bob.address, bob.publicKey],
    [carol.address, carol.publicKey],
  ]),
  own: alice.publicKey,
  signing: alice.privateKey,
};

function headersOf(mime: string): string {
  return mime.slice(0, mime.search(/\r\n\r\n/));
}

describe("outgoingMessages", () => {
  it("builds plain mail as is", async () => {
    const [mime] = await outgoingMessages(input([]), null, true, pgp);
    expect(mime).toContain("Subject: Plans");
    expect(mime).toContain("Autocrypt: addr=test@hafa.cc");
  });

  it("saves a draft encrypted to everyone, Bcc outside", async () => {
    const messages = await outgoingMessages(
      input([{ name: "", email: carol.address }]),
      keys,
      false,
      pgp,
    );
    expect(messages).toHaveLength(1);
    const outer = headersOf(messages[0]!);
    expect(outer).toContain("Subject: ...");
    expect(outer).toContain(`Bcc: ${carol.address}`);
    expect(outer).toContain("multipart/encrypted");
    expect(outer).toContain("Autocrypt:");
    for (const key of [alice, bob, carol]) {
      const opened = await pgp.decryptMessage(
        new TextEncoder().encode(messages[0]!),
        {
          decryptionKeys: [key.privateKey],
          verificationKeys: [alice.publicKey],
        },
      );
      expect(opened.protectedHeaders?.subject).toBe("Plans");
      expect(opened.signature.status).toBe("valid");
    }
  });

  it("splits a send with Bcc into the main message and a copy per Bcc", async () => {
    const [main, copy, ...rest] = await outgoingMessages(
      input([{ name: "", email: carol.address }]),
      keys,
      true,
      pgp,
    );
    expect(rest).toEqual([]);
    expect(headersOf(main!)).not.toContain("Bcc:");
    expect(headersOf(copy!)).toContain(`Bcc: ${carol.address}`);
    expect(headersOf(copy!)).not.toMatch(/^To:/m);
    await expect(
      pgp.decryptMessage(new TextEncoder().encode(main!), {
        decryptionKeys: [carol.privateKey],
        verificationKeys: [],
      }),
    ).rejects.toThrow();
    // The inner To counts only under the sender's valid signature.
    const opened = await pgp.decryptMessage(new TextEncoder().encode(copy!), {
      decryptionKeys: [carol.privateKey],
      verificationKeys: [alice.publicKey],
    });
    expect(opened.signature.status).toBe("valid");
    expect(opened.protectedHeaders?.to).toContain(bob.address);
    await expect(
      pgp.decryptMessage(new TextEncoder().encode(copy!), {
        decryptionKeys: [bob.privateKey],
        verificationKeys: [],
      }),
    ).rejects.toThrow();
  });

  it("refuses to encrypt to someone without a key", async () => {
    await expect(
      outgoingMessages(
        input([{ name: "", email: "nokey@x.example.org" }]),
        keys,
        false,
        pgp,
      ),
    ).rejects.toThrow("no key");
  });
});
