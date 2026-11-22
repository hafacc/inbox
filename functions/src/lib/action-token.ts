import { randomBytes } from "node:crypto";

/** What a notification action asks for. */
export type NotificationAction = "done";

/** Input of the `notificationAction` callable. */
export interface NotificationActionInput {
  /** token from the notification's data */
  token: string;
  /** the action tapped */
  action: NotificationAction;
}

/** A parsed action token: whose it is and its stored nonce. */
export interface ActionTokenParts {
  /** Firebase uid */
  uid: string;
  /** id of `private/{uid}/actions/{nonce}` */
  nonce: string;
}

/** How long a notification's actions keep working. */
export const ACTION_TOKEN_TTL_MS = 14 * 24 * 60 * 60 * 1000;

const NONCE_PATTERN = /^[A-Za-z0-9_-]{24}$/;

/** Make a token for a uid: the uid, a dot, and a fresh random nonce. */
export function newActionToken(uid: string): {
  token: string;
  nonce: string;
} {
  const nonce = randomBytes(18).toString("base64url");
  return { token: `${Buffer.from(uid).toString("base64url")}.${nonce}`, nonce };
}

/** Split a token into its uid and nonce, returning undefined when malformed. */
export function parseActionToken(token: string): ActionTokenParts | undefined {
  const [encodedUid, nonce, ...rest] = token.split(".");
  if (
    rest.length > 0 ||
    encodedUid === undefined ||
    encodedUid === "" ||
    nonce === undefined ||
    !NONCE_PATTERN.test(nonce)
  ) {
    return undefined;
  }
  const uid = Buffer.from(encodedUid, "base64url").toString("utf8");
  return uid === "" ? undefined : { uid, nonce };
}

/** Validate untrusted `notificationAction` input, returning undefined when malformed. */
export function parseActionInput(
  data: unknown,
): NotificationActionInput | undefined {
  if (typeof data !== "object" || data === null) return undefined;
  const { token, action } = data as Record<string, unknown>;
  if (typeof token !== "string" || token === "" || action !== "done") {
    return undefined;
  } else {
    return { token, action };
  }
}
