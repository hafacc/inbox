// Removing what the app keeps in this browser: on sign-out, everything about the account (mail,
// keys, composers, Firestore's offline copy, contact photos, push token), keeping only the theme
// and the app's offline copy; on a sign-out forced by lost Gmail access, the same but the unsent
// work parked for the next sign-in (parked.ts); after deleting the account, everything, the
// service worker too.

import { clearFirestoreCache } from "./firebase";
import { PARKED_DATABASE } from "./parked";

/** What to remove: the account's data, that but its parked unsent work, or everything. */
export type ClearScope = "signOut" | "forcedSignOut" | "everything";

// Kept across sign-out: how the app looks, focus mode and the round buttons' corner, not anything about the account.
const KEPT_ON_SIGN_OUT = new Set([
  "inbox:theme",
  "inbox:sidebar-collapsed",
  "inbox:focus",
  "inbox:corner-side",
]);
// The service worker's copy of the app itself.
const APP_SHELL_PREFIX = "inbox-";

function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve();
    request.onerror = () => resolve();
    request.onblocked = () => resolve();
  });
}

async function attempt(
  step: string,
  run: () => Promise<unknown>,
): Promise<void> {
  try {
    await run();
  } catch (error) {
    console.error(`clearing ${step} failed`, error);
  }
}

/** Remove the app's data from this browser; every step runs even when one fails. */
export async function clearDeviceData(scope: ClearScope): Promise<void> {
  await attempt("Firestore", clearFirestoreCache);
  await attempt("IndexedDB", async () => {
    const databases = await indexedDB.databases();
    await Promise.all(
      databases.flatMap(({ name }) =>
        name === undefined ||
        (scope === "forcedSignOut" && name === PARKED_DATABASE)
          ? []
          : [deleteDatabase(name)],
      ),
    );
  });
  await attempt("caches", async () => {
    if (typeof caches === "undefined") return;
    const names = await caches.keys();
    await Promise.all(
      names
        .filter(
          (name) =>
            scope === "everything" || !name.startsWith(APP_SHELL_PREFIX),
        )
        .map((name) => caches.delete(name)),
    );
  });
  await attempt("localStorage", async () => {
    if (scope === "everything") {
      localStorage.clear();
      return;
    }
    for (const key of Object.keys(localStorage)) {
      if (!KEPT_ON_SIGN_OUT.has(key)) localStorage.removeItem(key);
    }
  });
  await attempt("sessionStorage", async () => sessionStorage.clear());
  if (scope === "everything") {
    await attempt("service worker", async () => {
      const registrations =
        (await navigator.serviceWorker?.getRegistrations()) ?? [];
      await Promise.all(
        registrations.map((registration) => registration.unregister()),
      );
    });
  }
}
