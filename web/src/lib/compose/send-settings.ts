import { varySetting } from "@inbox/shared/send-suggest";

/** The sending settings kept in `users/{uid}.settings`. */
export type SendSettings = {
  /** signature, markdown; empty for none */
  signature: string;
  /** address new messages start from, or null for Gmail's default */
  sendAsDefault: string | null;
  /** shift suggested schedule-send times by a few minutes */
  varySendTimes: boolean;
  /** largest shift either way, whole minutes */
  varySendMaxMinutes: number;
};

/** `users/{uid}` as far as the app reads it. */
export type UserDoc = {
  address?: string | null;
  // the server's record of the address's setup (see address/address-model.ts)
  addressSetup?: unknown;
  settings?: Partial<SendSettings> & Record<string, unknown>;
};

/** The parsed send settings, with defaults for what isn't set. */
export function sendSettingsFrom(data: UserDoc | undefined): SendSettings {
  const settings = data?.settings ?? {};
  const vary = varySetting(settings);
  return {
    signature: typeof settings.signature === "string" ? settings.signature : "",
    sendAsDefault:
      typeof settings.sendAsDefault === "string" &&
      settings.sendAsDefault !== ""
        ? settings.sendAsDefault
        : null,
    varySendTimes: vary.enabled,
    varySendMaxMinutes: vary.maxMinutes,
  };
}
