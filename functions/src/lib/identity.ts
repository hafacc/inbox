import type { TokenPayload } from "google-auth-library";

/** Google account fields taken from a verified id token. */
export interface GoogleIdentity {
  /** stable Google account id */
  sub: string;
  /** account email address */
  email: string;
  /** display name, if the profile scope was granted */
  displayName: string | undefined;
  /** profile photo URL, if the profile scope was granted */
  photoUrl: string | undefined;
}

/** Derive the Firebase uid for a Google account id. */
export function uidForGoogleSub(sub: string): string {
  if (sub === "") {
    throw new Error("empty Google account id");
  }
  return `google:${sub}`;
}

/** Pick the identity out of a verified id token payload, requiring a verified email. */
export function identityFromPayload(
  payload: TokenPayload | undefined,
): GoogleIdentity {
  if (payload === undefined || payload.sub === "") {
    throw new Error("id token has no subject");
  }
  if (payload.email === undefined || payload.email_verified !== true) {
    throw new Error("id token has no verified email");
  }
  return {
    sub: payload.sub,
    email: payload.email,
    displayName: payload.name === "" ? undefined : payload.name,
    photoUrl: payload.picture === "" ? undefined : payload.picture,
  };
}
