import { firebaseConfig } from "../firebase-config";

/** The FCM data a notification carries; see `functions/src/lib/notification.ts`. */
export type PushPayload = {
  kind: "message";
  threadId: string;
  messageId: string;
  sender: string;
  senderAddress: string;
  subject: string;
  snippet: string;
  account: string;
  actionToken: string;
  sentAt: string;
  // OpenPGP-encrypted: subject and snippet are empty, and the device decrypts the message to fill them.
  encrypted: boolean;
};

/** A notification ready for `showNotification`. */
export type NotificationContent = {
  title: string;
  options: {
    body: string;
    /** the app's icon, shown beside the text */
    icon: string;
    /** the white-on-clear mark the status bar shows */
    badge: string;
    tag: string;
    renotify: boolean;
    timestamp?: number;
    data: PushPayload;
    actions: { action: NotificationAction; title: string }[];
  };
};

/** The buttons a notification offers. */
export type NotificationAction = "done" | "reply";

/** What tapping a notification or one of its buttons does. */
export type ClickPlan =
  | { kind: "open"; threadId: string }
  | {
      kind: "call";
      threadId: string;
      body: { data: { token: string; action: "done" } };
    };

const REGION = "us-central1";

const PAYLOAD_KEYS = [
  "threadId",
  "messageId",
  "sender",
  "senderAddress",
  "subject",
  "snippet",
  "account",
  "actionToken",
  "sentAt",
] as const;

/** Read a push message body: FCM wraps the data under `data`. Null when it isn't ours. */
export function parsePush(body: unknown): PushPayload | null {
  if (typeof body !== "object" || body === null) return null;
  const record = body as Record<string, unknown>;
  const data = (
    typeof record.data === "object" && record.data !== null
      ? record.data
      : record
  ) as Record<string, unknown>;
  if (data.kind !== "message") return null;
  if (typeof data.threadId !== "string" || data.threadId === "") return null;
  const fields: Record<string, string> = {};
  for (const key of PAYLOAD_KEYS) {
    const value = data[key];
    fields[key] = typeof value === "string" ? value : "";
  }
  return {
    ...(fields as Omit<PushPayload, "kind" | "encrypted">),
    kind: data.kind,
    encrypted: data.encrypted === "true" || data.encrypted === true,
  };
}

/** Build the notification for a push, with as many buttons as the platform shows; icons come from `scope`, the app's base URL. */
export function notificationContent(
  payload: PushPayload,
  maxActions: number,
  scope: string,
): NotificationContent {
  const sender = payload.sender || payload.senderAddress || "Someone";
  const body =
    payload.encrypted && !payload.subject && !payload.snippet
      ? `Encrypted message from ${sender}`
      : [payload.subject, payload.snippet].filter(Boolean).join("\n");
  const sentAt = Number(payload.sentAt);
  const actions: { action: NotificationAction; title: string }[] = [
    { action: "done", title: "Done" },
    { action: "reply", title: "Reply" },
  ];
  return {
    title: sender,
    options: {
      body,
      icon: new URL("icon-192.png", scope).href,
      badge: new URL("badge-96.png", scope).href,
      tag: `thread:${payload.threadId}`,
      renotify: true,
      ...(Number.isFinite(sentAt) && sentAt > 0 ? { timestamp: sentAt } : {}),
      data: payload,
      actions: actions.slice(0, Math.max(0, maxActions)),
    },
  };
}

/** Decide what a tap does: Done goes to the server, anything else opens the thread. */
export function clickPlan(action: string, payload: PushPayload): ClickPlan {
  const { threadId, actionToken: token } = payload;
  if (action === "done" && token) {
    return {
      kind: "call",
      threadId,
      body: { data: { token, action: "done" } },
    };
  }
  return { kind: "open", threadId };
}

/** Whether a host is this machine, where the app talks to the Firebase emulators. */
export function isLocalHost(hostname: string): boolean {
  return hostname === "localhost" || hostname === "127.0.0.1";
}

/** URL of a callable function: the emulator on this machine, Cloud Functions otherwise. */
export function functionUrl(name: string, hostname: string): string {
  const project = firebaseConfig.projectId;
  return isLocalHost(hostname)
    ? `http://localhost:5001/${project}/${REGION}/${name}`
    : `https://${REGION}-${project}.cloudfunctions.net/${name}`;
}

/** The app URL that opens a thread. */
export function threadUrl(scope: string, threadId: string): string {
  const url = new URL(scope);
  url.searchParams.set("thread", threadId);
  return url.href;
}
