// scripts/build-sw.ts bundles this into out/sw.js and prepends self.__VERSION and self.__FILES.

import { decodeBase64Url } from "@inbox/shared/mime";
import { decodedText, decryptMessage, header, walk } from "@inbox/shared/pgp";
import { initializeApp } from "firebase/app";
import {
  type Auth,
  connectAuthEmulator,
  indexedDBLocalPersistence,
  initializeAuth,
  type User,
} from "firebase/auth";
import { firebaseConfig } from "../src/lib/firebase-config";
import { Idb } from "../src/lib/idb";
import {
  DECRYPT_PUSH_TIMEOUT_MS,
  decryptPush,
  type PushDecryptDeps,
} from "../src/lib/notify/encrypted-push";
import {
  clickPlan,
  functionUrl,
  isLocalHost,
  notificationContent,
  type PushPayload,
  parsePush,
  threadUrl,
} from "../src/lib/notify/push";
import { DEVICE_RECORD_KEY, privateKeyOf } from "../src/lib/pgp/device-key";

declare const self: ServiceWorkerGlobalScope & {
  __VERSION: string;
  __FILES: string[];
};

const CACHE = `inbox-${self.__VERSION}`;
const SCOPE = self.registration.scope;

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) =>
        cache.addAll(
          self.__FILES.map(
            (file) => new Request(new URL(file, SCOPE), { cache: "reload" }),
          ),
        ),
      ),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(
          names
            .filter((name) => name.startsWith("inbox-") && name !== CACHE)
            .map((name) => caches.delete(name)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("message", (event) => {
  if (event.data === "skipWaiting") self.skipWaiting();
});

// A saved page at that path (with or without its trailing slash), else the app.
async function savedPage(url: string): Promise<Response> {
  const path = new URL(url);
  path.search = "";
  path.hash = "";
  const cache = await caches.open(CACHE);
  const withSlash = path.href.endsWith("/") ? path.href : `${path.href}/`;
  return (
    (await cache.match(path.href)) ??
    (await cache.match(withSlash)) ??
    (await cache.match(SCOPE)) ??
    fetch(url)
  );
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET" || !request.url.startsWith(SCOPE)) return;
  event.respondWith(
    request.mode === "navigate"
      ? savedPage(request.url)
      : caches
          .match(request, { cacheName: CACHE })
          .then((cached) => cached ?? fetch(request)),
  );
});

async function windowClients() {
  return self.clients.matchAll({ type: "window", includeUncontrolled: true });
}

// Firebase Auth reads the signed-in user the app stored in IndexedDB (same default app).
let authReady: Promise<Auth> | null = null;
function signedInUser(): Promise<User | null> {
  if (authReady === null) {
    const auth = initializeAuth(initializeApp(firebaseConfig), {
      persistence: indexedDBLocalPersistence,
    });
    if (isLocalHost(self.location.hostname)) {
      connectAuthEmulator(auth, "http://localhost:9099", {
        disableWarnings: true,
      });
    }
    authReady = auth.authStateReady().then(() => auth);
  }
  return authReady.then((auth) => auth.currentUser);
}

async function gmailToken(user: User): Promise<string> {
  const response = await fetch(
    functionUrl("gmailToken", self.location.hostname),
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${await user.getIdToken()}`,
      },
      body: JSON.stringify({ data: null }),
    },
  );
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const body = (await response.json()) as { result: { accessToken: string } };
  return body.result.accessToken;
}

// An encrypted message's real subject and text, when this device holds the unlocked key.
async function withDecrypted(payload: PushPayload): Promise<PushPayload> {
  if (!payload.encrypted) return payload;
  const user = await signedInUser().catch(() => null);
  if (!user) return payload;
  const deps: PushDecryptDeps = {
    async deviceRecord() {
      const cache = new Idb(`inbox:${user.uid}`);
      try {
        return (await cache.get("device", DEVICE_RECORD_KEY)) ?? null;
      } finally {
        await cache.close();
      }
    },
    privateKeyOf,
    async rawMessage(messageId) {
      const response = await fetch(
        `https://gmail.googleapis.com/gmail/v1/users/me/messages/${encodeURIComponent(messageId)}?format=raw`,
        { headers: { Authorization: `Bearer ${await gmailToken(user)}` } },
      );
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const body = (await response.json()) as { raw: string };
      return decodeBase64Url(body.raw);
    },
    decrypt: (raw, privateKey) =>
      decryptMessage(raw, {
        decryptionKeys: [privateKey],
        verificationKeys: [],
      }),
    tools: { walk, decodedText, header },
  };
  const timeout = new Promise<PushPayload>((resolve) =>
    setTimeout(() => resolve(payload), DECRYPT_PUSH_TIMEOUT_MS),
  );
  return Promise.race([decryptPush(payload, deps), timeout]);
}

async function showPush(received: PushPayload): Promise<void> {
  const windows = await windowClients();
  for (const client of windows) client.postMessage({ type: "mail-changed" });
  // The open app shows new mail itself.
  if (windows.some((client) => client.focused)) return;
  const payload = await withDecrypted(received);
  const { title, options } = notificationContent(
    payload,
    // Not in TypeScript's lib: only some browsers have it.
    (Notification as typeof Notification & { maxActions?: number })
      .maxActions ?? 2,
    SCOPE,
  );
  await self.registration.showNotification(title, options);
}

self.addEventListener("push", (event) => {
  let body: unknown = null;
  try {
    body = event.data?.json() ?? null;
  } catch {
    body = null;
  }
  const payload = parsePush(body);
  if (payload) event.waitUntil(showPush(payload));
});

async function openThread(threadId: string): Promise<void> {
  const windows = await windowClients();
  const existing = windows.find((client) => client.url.startsWith(SCOPE));
  if (existing) {
    await existing.focus();
    existing.postMessage({ type: "open-thread", threadId });
  } else {
    await self.clients.openWindow(threadUrl(SCOPE, threadId));
  }
}

async function runClick(action: string, payload: PushPayload): Promise<void> {
  const plan = clickPlan(action, payload);
  if (plan.kind === "open") return openThread(plan.threadId);
  try {
    const response = await fetch(
      functionUrl("notificationAction", self.location.hostname),
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(plan.body),
      },
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    for (const client of await windowClients()) {
      client.postMessage({ type: "mail-changed", doneThreadId: plan.threadId });
    }
  } catch (error) {
    // Let the user do it in the app instead.
    console.error("notification action failed", error);
    await openThread(plan.threadId);
  }
}

self.addEventListener("notificationclick", (event) => {
  const payload: PushPayload | null = event.notification.data;
  event.notification.close();
  if (!payload || typeof payload.threadId !== "string") return;
  event.waitUntil(runClick(event.action, payload));
});
