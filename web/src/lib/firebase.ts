import { type FirebaseApp, getApps, initializeApp } from "firebase/app";
import { type Auth, connectAuthEmulator, getAuth } from "firebase/auth";
import {
  clearIndexedDbPersistence,
  connectFirestoreEmulator,
  type Firestore,
  getFirestore,
  initializeFirestore,
  persistentLocalCache,
  terminate,
} from "firebase/firestore";
import {
  connectFunctionsEmulator,
  type Functions,
  getFunctions,
} from "firebase/functions";
import { firebaseConfig } from "./firebase-config";

const EMULATOR_HOST = "localhost";

let cachedApp: FirebaseApp | null = null;
let cachedAuth: Auth | null = null;
let cachedDb: Firestore | null = null;
let cachedFunctions: Functions | null = null;

/** Whether the dev server runs the app on this machine, where it talks to the Firebase emulators unless started with `bun run dev:live`. */
export function usesEmulators(): boolean {
  return (
    import.meta.env.DEV &&
    import.meta.env.MODE !== "live" &&
    typeof location !== "undefined" &&
    (location.hostname === "localhost" || location.hostname === "127.0.0.1")
  );
}

/** The Firebase app. */
export function app(): FirebaseApp {
  if (cachedApp) return cachedApp;
  cachedApp = getApps()[0] ?? initializeApp(firebaseConfig);
  return cachedApp;
}

/** Firebase Auth, on the emulator when local. */
export function auth(): Auth {
  if (cachedAuth) return cachedAuth;
  cachedAuth = getAuth(app());
  if (usesEmulators()) {
    connectAuthEmulator(cachedAuth, `http://${EMULATOR_HOST}:9099`, {
      disableWarnings: true,
    });
  }
  return cachedAuth;
}

/** Firestore with its offline cache, on the emulator when local. */
export function db(): Firestore {
  if (cachedDb) return cachedDb;
  try {
    cachedDb = initializeFirestore(app(), {
      localCache: persistentLocalCache(),
    });
    if (usesEmulators())
      connectFirestoreEmulator(cachedDb, EMULATOR_HOST, 8080);
  } catch (error) {
    // Already initialized, as when hot reload reuses the app.
    console.warn("firestore: reusing existing instance", error);
    cachedDb = getFirestore(app());
  }
  return cachedDb;
}

/** Stop Firestore and delete its offline cache from the device; the page must reload after. */
export async function clearFirestoreCache(): Promise<void> {
  const database = cachedDb ?? db();
  cachedDb = null;
  await terminate(database);
  await clearIndexedDbPersistence(database);
}

/** Cloud Functions, on the emulator when local. */
export function functions(): Functions {
  if (cachedFunctions) return cachedFunctions;
  cachedFunctions = getFunctions(app());
  if (usesEmulators()) {
    connectFunctionsEmulator(cachedFunctions, EMULATOR_HOST, 5001);
  }
  return cachedFunctions;
}
