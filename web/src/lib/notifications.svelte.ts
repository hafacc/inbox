import {
  deleteDoc,
  doc,
  type Firestore,
  serverTimestamp,
  setDoc,
} from "firebase/firestore";
import type { Messaging } from "firebase/messaging";
import { createContext } from "svelte";
import { dev } from "$app/env";
import { app, db } from "./firebase";
import { vapidKey } from "./firebase-config";

/**
 * Whether this browser gets notifications: `unsupported` (no push here, or a dev build without the
 * service worker), `off`, `on`, or `blocked` (the user said no in the browser's prompt).
 */
export type NotificationStatus = "unsupported" | "off" | "on" | "blocked";

// Firebase Messaging, loaded only where push works.
type MessagingApi = typeof import("firebase/messaging");

// How often an open tab refreshes its token and the device's `lastSeen`.
const REFRESH_MS = 24 * 60 * 60 * 1000;

function tokenKey(uid: string): string {
  return `inbox:fcm-token:${uid}`;
}

// Set when the user turned notifications off here, so a granted permission doesn't turn them back on.
function offKey(uid: string): string {
  return `inbox:fcm-off:${uid}`;
}

/** This browser's notification setting and its FCM token in `users/{uid}/devices`. */
export class NotificationState {
  /** Where notifications stand on this browser. */
  status = $state<NotificationStatus>("unsupported");
  /** Whether turning on or off is under way. */
  busy = $state(false);
  /** What went wrong with the last change. */
  error = $state<string | null>(null);
  readonly #uid: string;
  readonly #db: Firestore;
  #messaging: Messaging | null = null;
  #api: MessagingApi | null = null;
  #registeredAt = 0;
  // Ends the page listeners on `dispose`.
  #listening = new AbortController();

  constructor(uid: string) {
    this.#uid = uid;
    this.#db = db();
    void this.#start();
  }

  async #start(): Promise<void> {
    if (
      dev ||
      typeof Notification === "undefined" ||
      !("serviceWorker" in navigator)
    ) {
      return;
    }
    const api = await import("firebase/messaging").catch(() => null);
    if (api === null || !(await api.isSupported().catch(() => false))) return;
    this.#api = api;
    this.#messaging = api.getMessaging(app());
    document.addEventListener(
      "visibilitychange",
      () => {
        if (
          document.visibilityState === "visible" &&
          this.status === "on" &&
          !this.busy &&
          Date.now() - this.#registeredAt > REFRESH_MS
        ) {
          void this.#refresh();
        }
      },
      { signal: this.#listening.signal },
    );
    if (Notification.permission === "denied") {
      this.status = "blocked";
      return;
    }
    if (
      Notification.permission !== "granted" ||
      localStorage.getItem(offKey(this.#uid)) !== null
    ) {
      this.status = "off";
      return;
    }
    this.status = "on";
    await this.#refresh();
  }

  // Tokens rotate and the server drops ones FCM refuses; save the current one and mark the device seen.
  async #refresh(): Promise<void> {
    try {
      await this.#register(localStorage.getItem(tokenKey(this.#uid)));
    } catch (error) {
      console.error("notification token refresh failed", error);
    }
  }

  async #register(previous: string | null): Promise<void> {
    const messaging = this.#messaging!;
    const token = await this.#api!.getToken(messaging, {
      vapidKey,
      serviceWorkerRegistration: await navigator.serviceWorker.ready,
    });
    if (previous !== null && previous !== token) {
      await deleteDoc(this.#device(previous)).catch(() => {});
    }
    await setDoc(this.#device(token), {
      platform: "web",
      lastSeen: serverTimestamp(),
    });
    localStorage.setItem(tokenKey(this.#uid), token);
    this.#registeredAt = Date.now();
  }

  #device(token: string) {
    return doc(this.#db, "users", this.#uid, "devices", token);
  }

  /** Ask the browser for permission and register this device. */
  enable = async (): Promise<void> => {
    if (this.#messaging === null || this.busy) return;
    this.busy = true;
    this.error = null;
    try {
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        this.status = permission === "denied" ? "blocked" : "off";
        return;
      }
      localStorage.removeItem(offKey(this.#uid));
      await this.#register(localStorage.getItem(tokenKey(this.#uid)));
      this.status = "on";
    } catch (error) {
      console.error("notifications could not be turned on", error);
      this.error = "Couldn't turn on notifications.";
    } finally {
      this.busy = false;
    }
  };

  /** Stop notifications to this browser and forget its token. */
  disable = async (): Promise<void> => {
    if (this.#messaging === null || this.busy) return;
    this.busy = true;
    this.error = null;
    try {
      localStorage.setItem(offKey(this.#uid), "1");
      await this.forget();
      this.status = "off";
    } catch (error) {
      console.error("notifications could not be turned off", error);
      this.error = "Couldn't turn off notifications.";
    } finally {
      this.busy = false;
    }
  };

  /** Stop following the page, as the signed-in app goes away. */
  dispose(): void {
    this.#listening.abort();
  }

  /** Drop this browser's token and what the worker keeps, as on sign-out. */
  async forget(): Promise<void> {
    const saved = localStorage.getItem(tokenKey(this.#uid));
    this.status = this.status === "on" ? "off" : this.status;
    localStorage.removeItem(tokenKey(this.#uid));
    if (saved === null || this.#messaging === null) return;
    await deleteDoc(this.#device(saved));
    await this.#api?.deleteToken(this.#messaging).catch(() => {});
  }
}

/** Context accessors for the signed-in NotificationState. */
export const [getNotificationState, setNotificationState] =
  createContext<NotificationState>();
