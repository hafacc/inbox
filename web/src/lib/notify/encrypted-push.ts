// An encrypted message's notification (docs/pgp-contract.md "Notifications"): the service worker
// decrypts it when this device holds the unlocked key, else the notification says only who sent it.

import type { DecryptedMessage, MimeEntity } from "@inbox/shared/pgp";
import type { DeviceKeyRecord } from "../pgp/device-key";
import type { PushPayload } from "./push";

/** How long decrypting may hold up a notification. */
export const DECRYPT_PUSH_TIMEOUT_MS = 8_000;

const SNIPPET_LENGTH = 200;

/** The shared MIME helpers a preview needs. */
export type PreviewTools = {
  walk(entity: MimeEntity): MimeEntity[];
  decodedText(entity: MimeEntity): string;
  header(entity: MimeEntity, name: string): string | undefined;
};

function isAttachment(entity: MimeEntity, tools: PreviewTools): boolean {
  return /^\s*attachment/i.test(
    tools.header(entity, "Content-Disposition") ?? "",
  );
}

/** The real subject and the start of the text of a decrypted message. */
export function previewOf(
  decrypted: DecryptedMessage,
  tools: PreviewTools,
): { subject: string; snippet: string } {
  const parts = tools
    .walk(decrypted.entity)
    .filter((part) => !isAttachment(part, tools));
  const plain = parts.find((part) => part.type === "text/plain");
  const html = parts.find((part) => part.type === "text/html");
  const text = plain
    ? tools.decodedText(plain)
    : html
      ? tools
          .decodedText(html)
          .replace(/<(style|script)[\s\S]*?<\/\1>/gi, " ")
          .replace(/<[^>]+>/g, " ")
      : "";
  const lines = text
    .split(/\r?\n/)
    .filter((line) => !line.startsWith(">"))
    .join(" ");
  return {
    subject: decrypted.protectedHeaders?.subject ?? "",
    snippet: lines.replace(/\s+/g, " ").trim().slice(0, SNIPPET_LENGTH),
  };
}

/** What the service worker gives `decryptPush`. */
export type PushDecryptDeps = {
  /** this device's unlocked key, or null */
  deviceRecord(): Promise<DeviceKeyRecord | null>;
  /** the armored private key a record holds */
  privateKeyOf(record: DeviceKeyRecord): Promise<string>;
  /** the message as sent (Gmail `format=raw`), as bytes */
  rawMessage(messageId: string): Promise<Uint8Array>;
  /** decrypt it */
  decrypt(raw: Uint8Array, privateKey: string): Promise<DecryptedMessage>;
  /** MIME helpers */
  tools: PreviewTools;
};

/** The payload with the decrypted subject and text filled in; unchanged when it can't be. */
export async function decryptPush(
  payload: PushPayload,
  deps: PushDecryptDeps,
): Promise<PushPayload> {
  if (!payload.encrypted || payload.kind !== "message" || !payload.messageId) {
    return payload;
  }
  try {
    const record = await deps.deviceRecord();
    if (record === null) return payload;
    const [privateKey, raw] = await Promise.all([
      deps.privateKeyOf(record),
      deps.rawMessage(payload.messageId),
    ]);
    const preview = previewOf(await deps.decrypt(raw, privateKey), deps.tools);
    return { ...payload, ...preview };
  } catch {
    return payload;
  }
}
