/** Input of the `signIn` callable. */
export interface SignInInput {
  /** authorization code from the Google Identity Services code client */
  code: string;
  /** redirect URI the code was issued for: `postmessage` for the web popup, empty for an Android server auth code */
  redirectUri: string;
}

/** Output of the `signIn` callable. */
export interface SignInResult {
  /** Firebase custom token for `signInWithCustomToken` */
  customToken: string;
  /** Google account email */
  email: string;
}

/** Output of the `gmailToken` callable. */
export interface GmailTokenResult {
  /** Gmail access token */
  accessToken: string;
  /** when the access token expires, in epoch milliseconds */
  expiresAt: number;
}

/** Validate untrusted `signIn` input, returning undefined when malformed. */
export function parseSignInInput(data: unknown): SignInInput | undefined {
  if (typeof data !== "object" || data === null) {
    return undefined;
  }
  const { code, redirectUri } = data as Record<string, unknown>;
  if (
    typeof code !== "string" ||
    code === "" ||
    typeof redirectUri !== "string"
  ) {
    return undefined;
  }
  return { code, redirectUri };
}
