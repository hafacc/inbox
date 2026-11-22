// Whether a message's remote images load without asking: `users/{uid}.settings.images` and
// `.settings.imageSenders` (docs/images-contract.md, "Showing images").

import { addressKey } from "@inbox/shared/compose";

/** Ask before showing remote images, or always show them. */
export type ImageMode = "ask" | "always";

/** The image settings, parsed. */
export type ImageSettings = {
  /** `settings.images`; "ask" unless it is "always" */
  mode: ImageMode;
  /** `settings.imageSenders`: lower-case addresses whose images always show */
  senders: ReadonlySet<string>;
};

/** The image settings in `users/{uid}.settings`, with defaults for what isn't set. */
export function imageSettingsFrom(settings: unknown): ImageSettings {
  const record =
    typeof settings === "object" && settings !== null
      ? (settings as Record<string, unknown>)
      : {};
  const senders = new Set<string>();
  const map = record.imageSenders;
  if (typeof map === "object" && map !== null) {
    for (const [email, on] of Object.entries(map)) {
      if (on === true) senders.add(email.toLowerCase());
    }
  }
  return { mode: record.images === "always" ? "always" : "ask", senders };
}

/** Whether a sender's images load without asking. */
export function showsImagesFrom(
  settings: ImageSettings,
  senderEmail: string,
): boolean {
  return (
    settings.mode === "always" || settings.senders.has(addressKey(senderEmail))
  );
}
