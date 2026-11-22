// Claiming an @hafa.cc address: the setup progress on users/{uid}.addressSetup and the copy for each
// state; the name rules and refusal wording are @inbox/shared/address-name. docs/address-contract.md.

import {
  type ClaimReason,
  isClaimReason,
  reasonText,
} from "@inbox/shared/address-name";
import {
  addressNoticeState,
  addressTrouble,
} from "@inbox/shared/address-notice";

export {
  ADDRESS_DOMAIN,
  type ClaimReason,
  checkName,
  type NameCheck,
  REASON_TEXT,
} from "@inbox/shared/address-name";

/** Where a claim's setup stands. */
export type SetupStatus = "pending" | "verifying" | "active" | "releasing";

/** What setup is waiting out. */
export type SetupErrorCode =
  | "confirm-by-hand"
  | "cloudflare"
  | "not-configured";

/** `users/{uid}.addressSetup`, parsed. */
export type AddressSetup = {
  status: SetupStatus;
  error: { code: string; message: string } | null;
  /** when the server last changed it, in milliseconds, or null */
  updatedAt: number | null;
};

const STATUSES: readonly SetupStatus[] = [
  "pending",
  "verifying",
  "active",
  "releasing",
];

/** The setup on `users/{uid}`, or null when there is none or it doesn't parse. */
export function setupFrom(value: unknown): AddressSetup | null {
  if (typeof value !== "object" || value === null) return null;
  const record = value as Record<string, unknown>;
  const status = record.status;
  if (typeof status !== "string" || !STATUSES.includes(status as SetupStatus)) {
    return null;
  }
  const error = record.error as Record<string, unknown> | null | undefined;
  const updatedAt = record.updatedAt as
    | { toMillis?: () => number }
    | number
    | null
    | undefined;
  return {
    updatedAt:
      typeof updatedAt === "number"
        ? updatedAt
        : typeof updatedAt?.toMillis === "function"
          ? updatedAt.toMillis()
          : null,
    status: status as SetupStatus,
    error:
      error && typeof error.code === "string"
        ? {
            code: error.code,
            message: typeof error.message === "string" ? error.message : "",
          }
        : null,
  };
}

/** What the inbox's address notice shows: a calm `working` line, an `error`, or nothing. */
export function noticeState(
  setup: AddressSetup | null,
  now: number,
): "working" | "error" | null {
  return addressNoticeState(
    setup && {
      status: setup.status,
      errorCode: setup.error?.code ?? null,
      updatedAt: setup.updatedAt,
    },
    now,
  );
}

/** Whether setup is stalled or stopped, so Addresses offers Try again. */
export function canRetry(setup: AddressSetup | null, now: number): boolean {
  return noticeState(setup, now) === "error";
}

/** Why the address needs the user, worded for the notice in the inbox; null when nothing is wrong. */
export function addressNoticeText(
  setup: AddressSetup | null,
  now: number,
): string | null {
  const trouble = addressTrouble(
    setup && {
      status: setup.status,
      errorCode: setup.error?.code ?? null,
      updatedAt: setup.updatedAt,
    },
    now,
  );
  if (trouble === null || setup === null) return null;
  return trouble === "error"
    ? (setupErrorText(setup) ?? "Setup stopped.")
    : "Setup has stalled.";
}

/** The line describing where setup stands. */
export function statusText(setup: AddressSetup, address: string): string {
  switch (setup.status) {
    case "pending":
      return "Setting up…";
    case "verifying":
      return "Confirming your Gmail address…";
    case "active":
      return `Mail to ${address} arrives here.`;
    case "releasing":
      return "Releasing…";
  }
}

/** What to tell the user about a setup problem, or null when there is none. */
export function setupErrorText(setup: AddressSetup): string | null {
  const error = setup.error;
  if (error === null) return null;
  switch (error.code) {
    case "confirm-by-hand":
      return "Open the link in the email from Cloudflare to finish. Check Spam too.";
    case "cloudflare":
      return "Still setting up; this can take a few minutes.";
    case "not-configured":
      return "@hafa.cc addresses aren't available yet.";
    default:
      return error.message || "Still setting up.";
  }
}

/** One line of the progress list. */
export type SetupStep = {
  label: string;
  state: "done" | "active" | "waiting";
};

/** The progress list under the address: creating, then confirming. */
export function setupSteps(setup: AddressSetup | null): SetupStep[] {
  const status = setup?.status ?? null;
  const created = status === "verifying" || status === "active";
  const confirmed = status === "active";
  return [
    {
      label: created ? "Address created" : "Creating address",
      state: created ? "done" : status === "pending" ? "active" : "waiting",
    },
    {
      label: confirmed
        ? "Confirmed with Cloudflare"
        : "Confirming with Cloudflare…",
      state: confirmed ? "done" : created ? "active" : "waiting",
    },
  ];
}

/** Whether the address can be offered as a From choice: only once mail to it arrives. */
export function isActive(setup: AddressSetup | null): boolean {
  return setup?.status === "active";
}

/** What to show for a failed claim: the refusal's text, naming the old address when given; null for any other failure. */
export function refusalText(error: unknown): string | null {
  const details = (error as { details?: { address?: unknown } } | null)
    ?.details;
  const address = details?.address;
  return reasonText(
    reasonOf(error),
    typeof address === "string" ? address : null,
  );
}

/** The refusal reason of a failed callable, or null for any other failure. */
export function reasonOf(error: unknown): ClaimReason | null {
  const details = (error as { details?: unknown } | null)?.details;
  const reason = (details as { reason?: unknown } | null | undefined)?.reason;
  return isClaimReason(reason) ? reason : null;
}
