import { googleClientId } from "./firebase-config";

const GIS_URL = "https://accounts.google.com/gsi/client";

/** Every scope the app asks for once, covering web, Android and the server. */
export const SCOPES = [
  "openid",
  "email",
  "profile",
  "https://www.googleapis.com/auth/gmail.modify",
  "https://www.googleapis.com/auth/gmail.settings.basic",
  "https://www.googleapis.com/auth/contacts.readonly",
  "https://www.googleapis.com/auth/contacts.other.readonly",
  "https://www.googleapis.com/auth/calendar.events",
];

type CodeResponse = {
  code?: string;
  error?: string;
  error_description?: string;
};

type CodeClient = { requestCode: () => void };

type CodeClientConfig = {
  client_id: string;
  scope: string;
  ux_mode: "popup";
  access_type: "offline";
  prompt?: "consent";
  include_granted_scopes: boolean;
  callback: (response: CodeResponse) => void;
  error_callback: (error: { type: string; message?: string }) => void;
};

type GoogleAccounts = {
  oauth2: { initCodeClient: (config: CodeClientConfig) => CodeClient };
};

let loading: Promise<GoogleAccounts> | null = null;

function loadedAccounts(): GoogleAccounts | undefined {
  return (window as { google?: { accounts?: GoogleAccounts } }).google
    ?.accounts;
}

function loadIdentityServices(): Promise<GoogleAccounts> {
  const existing = loadedAccounts();
  if (existing) return Promise.resolve(existing);
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = GIS_URL;
    script.async = true;
    script.onload = () => {
      const accounts = loadedAccounts();
      if (accounts) resolve(accounts);
      else reject(new Error("Google sign-in did not load."));
    };
    script.onerror = () => {
      loading = null;
      reject(new Error("Couldn't reach Google to sign in."));
    };
    document.head.append(script);
  });
  return loading;
}

/** Load Google's sign-in script ahead of the click, so the popup opens within it. */
export function preloadIdentityServices(): void {
  loadIdentityServices().catch((error) =>
    console.warn("Google sign-in didn't load yet", error),
  );
}

/** The user closed the popup or declined. */
export class SignInCanceled extends Error {}

/**
 * Ask Google for an offline authorization code in a popup; `consent` forces the consent screen.
 *
 * Call it straight from the click: with the script loaded (see {@link preloadIdentityServices}) the
 * popup opens before this returns, which Safari requires. Otherwise it loads the script first, and
 * the popup may be blocked.
 */
export function requestAuthCode(consent: boolean): Promise<string> {
  const accounts = loadedAccounts();
  if (accounts) return popupCode(accounts, consent);
  else {
    return loadIdentityServices().then((loaded) => popupCode(loaded, consent));
  }
}

function popupCode(
  accounts: GoogleAccounts,
  consent: boolean,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const client = accounts.oauth2.initCodeClient({
      client_id: googleClientId,
      scope: SCOPES.join(" "),
      ux_mode: "popup",
      access_type: "offline",
      ...(consent ? { prompt: "consent" as const } : {}),
      include_granted_scopes: true,
      callback: (response) => {
        if (response.code) resolve(response.code);
        else if (response.error === "access_denied") {
          reject(new SignInCanceled(response.error));
        } else {
          reject(new Error(response.error_description ?? response.error));
        }
      },
      error_callback: (error) => {
        if (error.type === "popup_closed") {
          reject(new SignInCanceled(error.type));
        } else {
          reject(new Error(error.message ?? error.type));
        }
      },
    });
    client.requestCode();
  });
}
