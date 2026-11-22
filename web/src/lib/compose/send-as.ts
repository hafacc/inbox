import { addressKey, type Mailbox } from "@inbox/shared/compose";
import type { GmailSendAs } from "../gmail/types";

/** How an address sends. */
export type SendRoute = "gmail" | "alias" | "hafa";

/** An address the user can send from. */
export type FromOption = {
  /** the address and the name that goes with it */
  mailbox: Mailbox;
  /** how it sends */
  route: SendRoute;
};

/** The line under each address in the From menu. */
export const ROUTE_TEXT: Record<SendRoute, string> = {
  gmail: "Gmail",
  alias: "Gmail send-as",
  hafa: "Sent through hafa.cc",
};

/**
 * The From menu: the claimed @hafa.cc address first, then the Gmail address, then Gmail send-as
 * aliases Gmail has verified (pending ones would fail on the server). The @hafa.cc address takes
 * the Gmail address's display name.
 */
export function fromOptions(
  sendAs: readonly GmailSendAs[],
  gmailAddress: string,
  hafaAddress: string | null,
): FromOption[] {
  const primary = sendAs.find((entry) => entry.isPrimary);
  const primaryName = primary?.displayName ?? "";
  const options: FromOption[] = [];
  const seen = new Set<string>();
  const add = (email: string, name: string, route: SendRoute) => {
    const key = addressKey(email);
    if (key === "" || seen.has(key)) return;
    seen.add(key);
    options.push({ mailbox: { name, email }, route });
  };
  if (hafaAddress) add(hafaAddress, primaryName, "hafa");
  add(primary?.sendAsEmail ?? gmailAddress, primaryName, "gmail");
  for (const entry of sendAs) {
    if (entry.isPrimary || entry.verificationStatus !== "accepted") continue;
    add(entry.sendAsEmail, entry.displayName ?? "", "alias");
  }
  return options;
}

/** The address a new message starts from: the saved default if it's still an option, else Gmail's default, else the first. */
export function defaultFrom(
  options: readonly FromOption[],
  sendAs: readonly GmailSendAs[],
  savedDefault: string | null,
): FromOption | null {
  const find = (email: string | null | undefined) =>
    email
      ? options.find(
          (option) => addressKey(option.mailbox.email) === addressKey(email),
        )
      : undefined;
  return (
    find(savedDefault) ??
    find(sendAs.find((entry) => entry.isDefault)?.sendAsEmail) ??
    options[0] ??
    null
  );
}

/** The option for an address, if it is one. */
export function optionFor(
  options: readonly FromOption[],
  email: string,
): FromOption | undefined {
  return options.find(
    (option) => addressKey(option.mailbox.email) === addressKey(email),
  );
}
