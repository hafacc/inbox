import {
  onAuthStateChanged,
  signInWithCustomToken,
  signOut,
  type User,
} from "firebase/auth";
import { httpsCallable } from "firebase/functions";
import { createContext } from "svelte";
import { clearDeviceData } from "./device-data";
import { auth, functions, usesEmulators } from "./firebase";
import { hasPlaceholders } from "./firebase-config";
import { isUnreachableCall, UnreachableError } from "./gmail/request";
import { requestAuthCode, SignInCanceled } from "./google-identity";
import { type AccessToken, TokenCache } from "./token-cache";

const LINKED_KEY = "inbox:linked";
const EMAIL_KEY = "inbox:email";
// Shown when Google must ask for consent again before the server can keep the account linked.
const CONSENT_AGAIN =
  "Google needs you to allow access once more. Click Sign in with Google again.";

type SignInRequest = { code: string; redirectUri: "postmessage" };
type SignInResponse = { customToken: string; email: string };

/** Gmail access is gone (revoked, or the stored grant lost); the user has to sign in again. */
export class ReauthNeeded extends Error {}

function isReauth(error: unknown): boolean {
  const { code, message } = error as { code?: string; message?: string };
  return code === "functions/failed-precondition" && message === "reauth";
}

/** Whether the app is signed in, and the sign-in and Gmail token plumbing. */
export class AuthState {
  /** Whether Firebase has said who is signed in yet. */
  status = $state<"loading" | "signedOut" | "signedIn">("loading");
  /** The Firebase user. */
  user = $state.raw<User | null>(null);
  /** The linked Gmail address. */
  email = $state("");
  /** Whether a sign-in is under way. */
  busy = $state(false);
  /** What went wrong with the last sign-in, for the sign-in page. */
  error = $state<string | null>(null);
  /** Gmail access tokens from the `gmailToken` function. */
  readonly tokens = new TokenCache(() => this.#fetchToken());
  // What the signed-in app clears before a forced sign-out.
  #forcedSignOutHandler: (() => Promise<void>) | null = null;
  #reauthing: Promise<void> | null = null;

  constructor() {
    $effect(() =>
      onAuthStateChanged(auth(), (user) => {
        this.user = user;
        this.email = user
          ? (user.email ?? localStorage.getItem(EMAIL_KEY) ?? "")
          : "";
        this.status = user ? "signedIn" : "signedOut";
        if (!user) this.tokens.accountChanged();
      }),
    );
  }

  async #fetchToken(): Promise<AccessToken> {
    const call = httpsCallable<undefined, AccessToken>(
      functions(),
      "gmailToken",
    );
    try {
      const { data } = await call();
      return data;
    } catch (error) {
      if (isReauth(error)) {
        await this.reauth();
        throw new ReauthNeeded();
      } else if (isUnreachableCall(error)) {
        throw new UnreachableError(error);
      } else {
        throw error;
      }
    }
  }

  async #link(consent: boolean): Promise<void> {
    const code = await requestAuthCode(consent);
    const call = httpsCallable<SignInRequest, SignInResponse>(
      functions(),
      "signIn",
    );
    const { data } = await call({ code, redirectUri: "postmessage" });
    localStorage.setItem(EMAIL_KEY, data.email);
    localStorage.setItem(LINKED_KEY, "1");
    this.email = data.email;
    await signInWithCustomToken(auth(), data.customToken);
  }

  /** Google's code popup, then the `signIn` function, then Firebase with the custom token it returns. */
  signIn = async (): Promise<void> => {
    if (hasPlaceholders() && !usesEmulators()) {
      this.error =
        "This build has no Firebase project yet. Fill in web/src/lib/firebase-config.ts.";
      return;
    }
    this.busy = true;
    this.error = null;
    try {
      const firstLink = localStorage.getItem(LINKED_KEY) !== "1";
      try {
        await this.#link(firstLink);
      } catch (error) {
        // Google only hands out a refresh token on the consent screen, and a second popup after
        // the round trip would be blocked, so the next click asks for consent.
        if (!firstLink && isReauth(error)) {
          localStorage.removeItem(LINKED_KEY);
          this.error = CONSENT_AGAIN;
          return;
        } else throw error;
      }
    } catch (error) {
      if (!(error instanceof SignInCanceled)) {
        console.error(error);
        this.error = "Sign-in didn't finish. Try again.";
      }
    } finally {
      this.busy = false;
    }
  };

  /** Sign out of Firebase; the caller clears the device's mail cache. */
  signOut = async (): Promise<void> => {
    this.tokens.accountChanged();
    await signOut(auth());
  };

  /** Delete the account and everything the server keeps for it (the `deleteAccount` function). */
  deleteAccount = async (): Promise<void> => {
    const call = httpsCallable<undefined, { deleted: boolean }>(
      functions(),
      "deleteAccount",
    );
    await call();
    this.tokens.accountChanged();
  };

  /**
   * Run `handler` before a sign-out forced by lost Gmail access, to clear the signed-in account
   * from the device; returns the function that stops it.
   */
  onForcedSignOut(handler: () => Promise<void>): () => void {
    this.#forcedSignOutHandler = handler;
    return () => {
      if (this.#forcedSignOutHandler === handler) {
        this.#forcedSignOutHandler = null;
      }
    };
  }

  /**
   * Gmail access was lost: clear the account from the device as on a sign-out, keeping only its
   * unsent work for the next sign-in, sign out, and ask for consent again next time.
   */
  reauth = (): Promise<void> => {
    this.#reauthing ??= this.#forcedSignOut().finally(() => {
      this.#reauthing = null;
    });
    return this.#reauthing;
  };

  async #forcedSignOut(): Promise<void> {
    const handler = this.#forcedSignOutHandler;
    if (handler !== null) {
      await handler().catch((error) =>
        console.error("clearing the account failed", error),
      );
    }
    this.tokens.accountChanged();
    await signOut(auth());
    await clearDeviceData("forcedSignOut");
    // Cleared with the rest; consent is asked again on the next sign-in.
    localStorage.removeItem(LINKED_KEY);
    this.error = "Gmail access ended. Sign in again to reconnect.";
  }
}

/** Context accessors for the one AuthState. */
export const [getAuthState, setAuthState] = createContext<AuthState>();
