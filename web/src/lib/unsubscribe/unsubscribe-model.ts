// Unsubscribing from a message's list (docs/images-contract.md, "Unsubscribe"): which way to go,
// the request mail for `mailto:`, and what the Unsubscribe control says while and after it runs.
import type { Mailbox } from "@inbox/shared/compose";
import {
  parseUnsubscribe,
  type Unsubscribe,
  type UnsubscribeMail,
} from "@inbox/shared/unsubscribe";

/** What a message's headers offer, or null when there is no way to unsubscribe. */
export function unsubscribeOf(
  listUnsubscribe: string | null | undefined,
  listUnsubscribePost: string | null | undefined,
): Unsubscribe | null {
  const parsed = parseUnsubscribe(
    listUnsubscribe ?? null,
    listUnsubscribePost ?? null,
  );
  return parsed.preferred === null ? null : parsed;
}

/** The request mail for a `mailto:` unsubscribe, with a subject and body when the list gives none. */
export function mailtoMessage(mail: UnsubscribeMail): {
  to: Mailbox[];
  subject: string;
  markdown: string;
} {
  return {
    to: [{ name: "", email: mail.address }],
    subject: mail.subject.trim() || "Unsubscribe",
    markdown: mail.body.trim() || "Please unsubscribe me from this list.",
  };
}

/**
 * Where an unsubscribe stands: idle, waiting on the request, or how it ended. A failure is
 * `signIn` when Gmail access has to be granted again, `offline` when there was no connection, else
 * `failed`.
 */
export type UnsubscribeStep =
  | "idle"
  | "working"
  | "unsubscribed"
  | "requested"
  | "failed"
  | "signIn"
  | "offline";

/** Whether the step is a failure, which can be tried again. */
export function isFailure(step: UnsubscribeStep): boolean {
  return step === "failed" || step === "signIn" || step === "offline";
}

/** The failure step for an error from the request, given whether the browser is online. */
export function failureStep(error: unknown, online: boolean): UnsubscribeStep {
  const { code, message } = (error ?? {}) as {
    code?: unknown;
    message?: unknown;
  };
  if (code === "functions/failed-precondition" && message === "reauth") {
    return "signIn";
  } else if (
    !online ||
    code === "functions/unavailable" ||
    code === "functions/deadline-exceeded"
  ) {
    return "offline";
  } else {
    return "failed";
  }
}

/** The Unsubscribe control's words for each step. */
export function stepText(step: UnsubscribeStep): string {
  switch (step) {
    case "idle":
      return "Unsubscribe";
    case "working":
      return "Unsubscribing…";
    case "unsubscribed":
      return "Unsubscribed";
    case "requested":
      return "Request sent";
    case "failed":
      return "Couldn't unsubscribe";
    case "signIn":
      return "Sign in again to unsubscribe";
    case "offline":
      return "Can't unsubscribe offline";
  }
}
