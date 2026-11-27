import {
  addressKey,
  buildMime,
  type CalendarPart,
  gmailRaw,
  type Mailbox,
  offersReplyAll,
  type QuotedMessage,
  quotedContentIds,
  randomBoundary,
} from "@inbox/shared/compose";
import {
  type DirectSendDoc,
  directSendDecision,
  isGmailRoute,
} from "@inbox/shared/direct-send";
import {
  decodeBase64Url,
  headerValue,
  parseAddressList,
} from "@inbox/shared/mime";
import {
  localOnlyDrafts,
  offlineDraftOf,
  offlineEditable,
} from "@inbox/shared/offline-drafts";
import {
  baseOutboxId,
  copyOutboxId,
  isCopyId,
  nextCopyNumbers,
  sendGroup,
} from "@inbox/shared/outbox-copies";
import { outboxDraftIds, type SendingDraft } from "@inbox/shared/outbox-drafts";
import {
  emptyPartition,
  type LastRow,
  lastRow,
  type QuickTime,
  quickTimes,
  type SendRow,
  type SendSource,
  sendRows,
  sendStatsPath,
  UNDO_SEND_DELAY_MS,
} from "@inbox/shared/send-suggest";
import type { DevicePartition } from "@inbox/shared/snooze-suggest";
import { draftSnippet, heldDraftStates } from "@inbox/shared/thread-drafts";
import { formatWakeTime } from "@inbox/shared/time-format";
import {
  collection,
  deleteDoc,
  deleteField,
  doc,
  type Firestore,
  runTransaction,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
} from "firebase/firestore";
import { createContext, untrack } from "svelte";
import { AccountSettings } from "../account-settings.svelte";
import { ADDRESS_DOMAIN } from "../address/address-model";
import { ContactBook } from "../contacts/contact-book.svelte";
import { errorText } from "../error-text";
import { db } from "../firebase";
import { watchFirestore } from "../firestore-watch";
import { GmailError, isNetworkError, isRetryable } from "../gmail/request";
import type { GmailDraft, GmailMessage, GmailSendAs } from "../gmail/types";
import { type MailState, NOTICE_MS, UNDO_MS } from "../mail-state.svelte";
import { loadPgp } from "../pgp/load";
import {
  decryptedDraft,
  isEncryptedMessage,
  rowText,
} from "../pgp/message-crypto";
import { SnoozeStatsStore } from "../snooze-stats-store";
import {
  draftIn,
  holdsThread,
  type MessageView,
  type ThreadDetail,
  threadDetail,
} from "../thread-detail";
import {
  answerFields,
  blankSnapshot,
  buildProblem,
  type ComposeImage,
  type ComposeMode,
  type ComposeSnapshot,
  composeInput,
  type DraftFiles,
  forwardMarkdown,
  htmlToText,
  isBlank,
  quoteFromText,
  quoteOf,
  randomId,
  type SourceMessage,
  savedDraftParts,
  sendProblem,
  snapshotFromDraft,
  snapshotFromSaved,
} from "./compose-model";
import { Composer } from "./composer.svelte";
import {
  ComposerStore,
  isPendingDelete,
  type KeptComposer,
  type PendingDelete,
  type Protection,
} from "./composer-store";
import { sendDirectly } from "./direct-send";
import {
  afterSave,
  type CopyDraft,
  type DraftJob,
  draftGone,
  opFor,
  type PendingSend,
  queueSave,
} from "./draft-queue";
import {
  copyRecipient,
  type EncryptKeys,
  isEncryptingAddress,
  outgoingMessages,
  type SendEncryption,
  sendEncryption,
} from "./encrypt-send";
import {
  closeThrowsAway,
  composersOf,
  deletedDraftOf,
  emptyComposers,
  type LocalDraft,
  localDraftOf,
  unsyncedDrafts,
} from "./local-drafts";
import {
  blockedText,
  canSendAgain,
  type DraftPreview,
  type HeldSend,
  heldSends,
  isFinished,
  isLocked,
  isPending,
  movedDraftWrite,
  type OutboxDoc,
  type OutboxWrite,
  outboxDocs,
  outboxForDraft,
  outboxFromData,
  resendWrite,
  waitingWrite,
} from "./outbox";
import {
  defaultFrom,
  type FromOption,
  fromOptions,
  optionFor,
} from "./send-as";

const SEND_AS_KEY = "sendAs";
// Send-as addresses change rarely (and only in Gmail's settings), so a day-old list is fine.
const SEND_AS_MAX_AGE_MS = 24 * 60 * 60 * 1000;

// The saved send-as list and when it was fetched; an older bare list counts as stale.
function sendAsFrom(
  value: unknown,
): { at: number; list: GmailSendAs[] } | null {
  if (Array.isArray(value)) return { at: 0, list: value as GmailSendAs[] };
  const saved = value as { at?: unknown; list?: unknown } | undefined;
  if (typeof saved?.at === "number" && Array.isArray(saved.list)) {
    return { at: saved.at, list: saved.list as GmailSendAs[] };
  }
  return null;
}
// A sent doc is kept this long so every open device can show "Sent" before it's cleared.
const SENT_KEEP_MS = 2 * 60 * 1000;
// Said when a draft can't be opened from the saved copy.
const OPENS_ONLINE = "This draft opens once you're back online.";
// Said when a listed draft has nothing left to open, here or in Gmail.
const DRAFT_GONE = "This draft is gone";
// Said when a draft kept here is sealed and this device can't open it now.
const LOCKED_DRAFT = "Unlock encrypted mail to open this draft.";

/** A composer's record on the device, as its thread shows it. */
type KeptDraft = {
  /** as its thread shows it; deleted for a delete waiting for Gmail */
  draft: LocalDraft;
  /** its Gmail draft, once saved there */
  draftId: string | null;
};

/** The pill about a message: undo while a send waits, then what happened; undo after a discard; Open after closing a draft. */
export type SendPill =
  | {
      kind: "undo";
      localId: string;
      outboxId: string | null;
      // the schedule the message had before Send, put back on undo
      restore: OutboxWrite | null;
      snapshot: ComposeSnapshot;
      expiresAt: number;
    }
  | { kind: "discarded"; localId: string; expiresAt: number }
  | { kind: "saved"; localId: string; expiresAt: number }
  | { kind: "notice"; text: string; failed: boolean; expiresAt: number };

// How long reading kept composers waits for the account's key and this device's to load.
const PROTECTION_WAIT_MS = 5_000;

async function composeProtection(
  encryption: MailState["encryption"],
): Promise<Protection> {
  await Promise.race([
    encryption.whenReady(),
    new Promise((resolve) => setTimeout(resolve, PROTECTION_WAIT_MS)),
  ]);
  // Not known yet whether the account has a key: write nothing rather than risk plain text.
  if (!encryption.ready) return { kind: "locked" };
  if (!encryption.hasKey) return { kind: "plain" };
  const key = encryption.deviceKey();
  return key === null ? { kind: "locked" } : { kind: "sealed", key };
}

// A composer's record as its thread shows it.
function keptDraftOf(kept: KeptComposer, updatedAt: number): KeptDraft {
  return isPendingDelete(kept)
    ? { draft: deletedDraftOf(kept), draftId: kept.draftId }
    : { draft: localDraftOf(kept, "draft", updatedAt), draftId: kept.draftId };
}

function withEntry<Value>(
  map: ReadonlyMap<string, Value>,
  key: string,
  value: Value,
): ReadonlyMap<string, Value> {
  const next = new Map(map);
  next.set(key, value);
  return next;
}

function withoutEntry<Value>(
  map: ReadonlyMap<string, Value>,
  key: string,
): ReadonlyMap<string, Value> {
  if (!map.has(key)) return map;
  const next = new Map(map);
  next.delete(key);
  return next;
}

function unchangedDoc(
  entry: OutboxDoc | undefined,
  write: OutboxWrite,
): boolean {
  return (
    entry !== undefined &&
    entry.status === "waiting" &&
    entry.draftId === write.draftId &&
    entry.from === write.from &&
    entry.sendAt === write.sendAt &&
    entry.chosenAt === write.chosenAt &&
    entry.threadId === write.threadId
  );
}

function sourceOf(message: MessageView, threadSubject: string): SourceMessage {
  const headers = message.headers;
  return {
    id: message.id,
    messageId: headers?.messageId ?? "",
    references: headers?.references ?? null,
    inReplyTo: headers?.inReplyTo ?? null,
    subject: headers?.subject || threadSubject,
    from: headers?.from ?? [message.from],
    replyTo: headers?.replyTo ?? [],
    to: headers?.to ?? message.to,
    cc: headers?.cc ?? [],
    date: message.date,
    text:
      message.body.text ??
      (message.body.html ? htmlToText(message.body.html) : message.snippet),
  };
}

function extensionOf(mimeType: string): string {
  const subtype = mimeType.split("/")[1] ?? "bin";
  return subtype === "jpeg" ? "jpg" : subtype.replace(/[^a-z0-9]/gi, "");
}

/**
 * Compose and send for the signed-in account: composers, drafts saved to Gmail (queued on the
 * device while offline), the outbox with undo and schedule send, the From list, contacts and the
 * send settings.
 */
export class ComposeHub {
  /** `users/{uid}`: address and send settings. */
  readonly settings: AccountSettings;
  /** Google Contacts for autocomplete. */
  readonly contacts: ContactBook;
  /** Gmail's send-as addresses. */
  sendAs = $state.raw<GmailSendAs[]>([]);
  /** The new-message composer, floating over the list. */
  floating = $state.raw<Composer | null>(null);
  /** The reply or forward open inside the thread card. */
  inline = $state.raw<Composer | null>(null);
  /** The message parked in the strip at the bottom of a phone's screen, out of the floating and inline places. */
  parked = $state.raw<Composer | null>(null);
  /** Outbox docs, by id. */
  outbox = $state.raw<ReadonlyMap<string, OutboxDoc>>(new Map());
  /** Draft saves waiting for Gmail, by composer id. */
  jobs = $state.raw<ReadonlyMap<string, DraftJob>>(new Map());
  // Composers' records on the device (`ComposerStore`), by composer id: only these are drafts.
  #kept = $state.raw<ReadonlyMap<string, KeptDraft>>(new Map());
  // Drafts thrown away here, hidden from their threads until their undo or their delete is done.
  #thrown = $state.raw<ReadonlyMap<string, LocalDraft>>(new Map());
  // Drafts being sent from here, until their thread shows Gmail's copy (or the send stops).
  #sending = $state.raw<ReadonlyMap<string, LocalDraft>>(new Map());
  // The order of changes to `#kept`, and the last one of each record, so a read of every record
  // started earlier doesn't undo a change made meanwhile.
  #keptSeq = 0;
  #keptAt = new Map<string, number>();

  /**
   * Drafts this device holds, as their threads show them, by composer id: each kept record, and
   * drafts thrown away or being sent here (`heldDraftStates`), so only a kept record is a draft.
   */
  readonly localDrafts = $derived.by((): ReadonlyMap<string, LocalDraft> => {
    const kept = this.#kept;
    const thrown = this.#thrown;
    const sending = this.#sending;
    const held = heldDraftStates({
      kept: [...kept.values()].map(({ draft }) => ({
        localId: draft.localId,
        deleted: draft.state === "deleted",
      })),
      thrown: [...thrown.keys()],
      sending: [...sending.keys()],
    });
    const drafts = new Map<string, LocalDraft>();
    for (const { localId, state } of held) {
      const draft =
        kept.get(localId)?.draft ?? thrown.get(localId) ?? sending.get(localId);
      if (draft !== undefined) drafts.set(localId, { ...draft, state });
    }
    return drafts;
  });

  /** New messages kept here that aren't in any Gmail thread yet, newest first: Drafts lists them on their own. */
  readonly newDrafts = $derived.by((): LocalDraft[] =>
    localOnlyDrafts(this.#mail.offlineDrafts())
      .flatMap((localId) => {
        const draft = this.localDrafts.get(localId);
        return draft === undefined ? [] : [draft];
      })
      .sort((left, right) => right.updatedAt - left.updatedAt),
  );

  /** How many drafts sign-out would lose: saves and sends not in Gmail, and deletes not done there. */
  get unsyncedDrafts(): number {
    return unsyncedDrafts(this.jobs.keys(), this.localDrafts.values());
  }

  /** Row data of outbox messages, by draft id. */
  previews = $state.raw<ReadonlyMap<string, DraftPreview>>(new Map());
  // The send each queued save's outbox doc was written for, by composer id, until the save is dropped.
  #docsWritten = $state.raw<ReadonlyMap<string, PendingSend>>(new Map());
  /** The pill after Send. */
  pill = $state.raw<SendPill | null>(null);
  /** Recipients' keys found so far, by lower-cased address (null: none). */
  recipientKeys = $state.raw<ReadonlyMap<string, string | null>>(new Map());
  /** A thread waiting for its messages before a reply opens (r, a or f on a closed thread). */
  pendingAnswer = $state<{ threadId: string; mode: ComposeMode } | null>(null);

  readonly #mail: MailState;
  readonly #db: Firestore;
  readonly #stats: SnoozeStatsStore;
  readonly #store: ComposerStore;
  #partition = $state.raw<DevicePartition>(emptyPartition("shared"));
  #disposers: (() => void)[] = [];
  // Set by `dispose`: async work finishing later changes nothing.
  #disposed = false;
  #flushing = false;
  // Sends started on this device, whose result the pill reports.
  #watching = new Set<string>();
  // Watched sends whose first retry the pill already reported.
  #retryReported = new Set<string>();
  #previewLoading = new Set<string>();
  #lookingUp = new Set<string>();
  // Each composer's saves run one at a time, so a slow encryption can't overtake a newer save.
  #saveChains = new Map<string, Promise<void>>();
  // Bcc copies' recipients (lower-cased) by draft id, so an edit updates each copy in place.
  #copyOwnerCache = new Map<string, string>();
  // Composers whose queued saves finished or were dropped this session, so a late read of the
  // device's queue doesn't run them again.
  #settled = new Set<string>();
  // Composers thrown away while a save of theirs may still be under way.
  #discarded = new Set<string>();
  // Thrown-away drafts whose Gmail copy is still to be deleted, as kept on the device, by composer id.
  #pendingDeletes = new Map<string, PendingDelete>();
  // Pending deletes whose Gmail call is under way.
  #deleting = new Set<string>();
  // The composer last closed with its draft kept, for the pill's Open.
  #closed: Composer | null = null;
  // Messages thrown away whose undo hasn't passed: the composer and the timer that ends it.
  #discarding = new Map<
    string,
    { composer: Composer; timer: ReturnType<typeof setTimeout> }
  >();
  // Sends with undo from this device by outbox id, to the composer id of their local draft.
  #sendingOutbox = new Map<string, string>();
  // Local drafts whose send went, with the open thread's history id then, kept until the thread
  // shows Gmail's copy.
  #sentLocal = new Map<string, string | null>();

  /** Addresses the user can send from. */
  readonly fromOptions: FromOption[];
  /** Where new messages start from. */
  readonly defaultFrom: FromOption | null;
  /** Every address of the user's, for reply recipients. */
  readonly ownAddresses: string[];
  /** The schedule-send menu's suggestions, before any shift. */
  readonly sendRows: SendRow[];
  /** The last custom send time, when it isn't already a row. */
  readonly sendLastRow: LastRow | null;
  /** The picker's learned clock times for sending. */
  readonly sendQuickTimes: QuickTime[];
  /** Everything not sent yet, failed first (see `outboxDocs`). */
  readonly pending: OutboxDoc[];
  /** Sends held on this device with no outbox doc yet, oldest first (`@inbox/shared/outbox-held`). */
  readonly held: HeldSend[];
  /** Failed sends. */
  readonly failedCount: number;
  /** Drafts this device is sending: queued with a send, or whose outbox doc it just wrote. */
  readonly sendingDrafts: SendingDraft[];
  // Drafts of outbox docs this device wrote, by doc id, until the doc is sent or gone.
  #wrote = $state.raw<ReadonlyMap<string, SendingDraft>>(new Map());
  // Docs of `#wrote` the listener has had, so one gone later is known to be gone.
  #wroteSeen = new Set<string>();

  constructor(mail: MailState) {
    this.#mail = mail;
    this.#db = db();
    mail.encryption.attach(this.#db);
    this.settings = new AccountSettings(this.#db, mail.uid, (value) =>
      mail.encryption.setDoc(value),
    );
    this.contacts = new ContactBook(mail.tokens, mail.cache);
    this.#store = new ComposerStore(
      mail.cache,
      () => composeProtection(mail.encryption),
      (localId, kept) => this.#keptChanged(localId, kept),
    );
    this.#stats = new SnoozeStatsStore(
      this.#db,
      mail.uid,
      (partition) => {
        this.#partition = partition;
      },
      sendStatsPath(mail.uid),
    );
    this.fromOptions = $derived(
      fromOptions(this.sendAs, mail.email, this.settings.activeAddress),
    );
    this.defaultFrom = $derived(
      defaultFrom(
        this.fromOptions,
        this.sendAs,
        this.settings.send.sendAsDefault,
      ),
    );
    this.ownAddresses = $derived(
      this.fromOptions.map((option) => option.mailbox.email),
    );
    this.sendRows = $derived(sendRows([this.#partition], mail.now));
    this.sendLastRow = $derived(
      lastRow([this.#partition], mail.now, this.sendRows),
    );
    this.sendQuickTimes = $derived(quickTimes([this.#partition], mail.now));
    this.pending = $derived(
      outboxDocs(this.outbox.values(), mail.now).filter(
        (entry) =>
          !isCopyId(entry.id) ||
          entry.status === "failed" ||
          entry.status === "blocked",
      ),
    );
    this.failedCount = $derived(
      this.pending.filter((entry) => entry.status === "failed").length,
    );
    this.held = $derived(
      heldSends(
        this.jobs.values(),
        this.#docsWritten,
        this.outbox.keys(),
        this.localDrafts,
      ),
    );
    this.sendingDrafts = $derived([
      ...[...this.jobs.values()].flatMap((job) => {
        if (job.send === null) return [];
        const local = this.localDrafts.get(job.localId);
        return [
          {
            draftId: job.draftId,
            messageId: local?.messageId ?? null,
            threadId: job.threadId ?? local?.draftThreadId ?? null,
          },
        ];
      }),
      ...this.#wrote.values(),
    ]);
    mail.setSendingDrafts(() => this.sendingDrafts);
    mail.setLocalDrafts(() =>
      [...this.localDrafts.values()].map((draft) => ({
        localId: draft.localId,
        threadId: draft.draftThreadId ?? draft.threadId,
        messageId: draft.messageId,
        state: draft.state,
      })),
    );
    const pageGone = () => void this.finishDiscards();
    window.addEventListener("pagehide", pageGone);
    this.#disposers.push(
      () => window.removeEventListener("pagehide", pageGone),
      () => this.settings.dispose(),
      () => this.#stats.dispose(),
      () => this.contacts.dispose(),
      watchFirestore(
        collection(this.#db, "users", mail.uid, "outbox"),
        (snapshot) => {
          const docs = new Map<string, OutboxDoc>();
          for (const entry of snapshot.docs) {
            const parsed = outboxFromData(entry.id, entry.data());
            if (parsed) docs.set(entry.id, parsed);
          }
          this.outbox = docs;
          mail.setOutboxDocs([...docs.values()]);
          this.#onOutbox(docs);
        },
        "outbox",
      ).stop,
    );

    $effect(() => {
      const pill = this.pill;
      if (pill === null) return;
      const timer = setTimeout(
        () => {
          if (this.pill === pill) this.pill = null;
        },
        Math.max(0, pill.expiresAt - Date.now()),
      );
      return () => clearTimeout(timer);
    });

    // Only one message is parked or open at a time: another one opening keeps the parked one as a draft.
    $effect(() => {
      const parked = this.parked;
      const open = this.floating ?? this.inline;
      if (parked !== null && open !== null && open !== parked) {
        untrack(() => void this.close(parked));
      }
    });

    // A reply belongs to its thread: closing or leaving the thread puts the reply away.
    $effect(() => {
      const inline = this.inline;
      if (inline !== null && !this.#mail.inOpenThread(inline.threadId)) {
        void this.close(inline);
      }
    });

    // Drafts listed from the saved copy follow this device's drafts as they change.
    $effect(() => {
      void this.localDrafts;
      void this.sendingDrafts;
      untrack(() => void this.#mail.reloadSavedDrafts());
    });

    this.#disposers.push(
      mail.onDraftsOpen((id) => void this.openThreadDraft(id)),
    );

    // r, a or f on a thread that wasn't open waits for its messages.
    $effect(() => {
      const pending = this.pendingAnswer;
      const detail = this.#mail.detail;
      if (pending === null || detail?.id !== pending.threadId) return;
      if (!detail.messages.at(-1)?.headers) return;
      this.pendingAnswer = null;
      if (pending.mode !== "new") void this.answer(pending.mode);
    });

    $effect(() => {
      const wake = () => {
        if (document.visibilityState === "visible") void this.flush();
      };
      // A page restored from the back-forward cache sends no visibility change.
      const restored = (event: PageTransitionEvent) => {
        if (event.persisted) wake();
      };
      document.addEventListener("visibilitychange", wake);
      window.addEventListener("online", wake);
      window.addEventListener("pageshow", restored);
      return () => {
        document.removeEventListener("visibilitychange", wake);
        window.removeEventListener("online", wake);
        window.removeEventListener("pageshow", restored);
      };
    });

    // A message sent from here shows as sending until the open thread has Gmail's copy.
    $effect(() => {
      const detail = this.#mail.detail;
      if (this.localDrafts.size > 0) untrack(() => this.#settleSent(detail));
    });

    // Saves sealed on the device open only once it is unlocked.
    $effect(() => {
      if (this.#mail.encryption.unlocked) untrack(() => void this.#loadJobs());
    });

    void this.#start();
  }

  // Queued saves kept on the device, joining those already in hand; then send them.
  async #loadJobs(): Promise<void> {
    const stored = await this.#store.jobs().catch(() => []);
    if (this.#disposed) return;
    const fresh = stored.filter(
      ([localId]) => !this.jobs.has(localId) && !this.#settled.has(localId),
    );
    if (fresh.length > 0) this.jobs = new Map([...fresh, ...this.jobs]);
    await this.#loadLocal();
    if (this.#disposed) return;
    void this.flush();
  }

  // Composers kept on the device, as their threads show them; pending deletes stay hidden and are
  // tried again. A blank one that never reached Gmail, with no save queued, is forgotten.
  async #loadLocal(): Promise<void> {
    const since = this.#keptSeq;
    const kept = await this.#store.composers().catch(() => null);
    const deletes = await this.#store.pendingDeletes().catch(() => null);
    if (this.#disposed || kept === null || deletes === null) return;
    const changed = (localId: string) =>
      (this.#keptAt.get(localId) ?? 0) > since;
    const next = new Map<string, KeptDraft>();
    for (const [localId, entry] of this.#kept) {
      if (changed(localId)) next.set(localId, entry);
    }
    for (const pending of deletes) {
      if (changed(pending.localId)) continue;
      if (!this.#pendingDeletes.has(pending.localId)) {
        this.#pendingDeletes.set(pending.localId, pending);
      }
      next.set(pending.localId, keptDraftOf(pending, 0));
    }
    const empty = new Set(
      emptyComposers(
        kept,
        (localId) => this.jobs.has(localId),
        (localId) => this.#composersOf(localId).length > 0,
      ),
    );
    const sending = new Map(this.#sending);
    for (const [localId, snapshot] of kept) {
      if (changed(localId) || this.#discarded.has(localId)) continue;
      if (empty.has(localId)) {
        void this.#forget(localId);
        continue;
      }
      next.set(
        localId,
        keptDraftOf(snapshot, this.#kept.get(localId)?.draft.updatedAt ?? 0),
      );
      if (
        this.jobs.get(localId)?.send?.kind === "now" &&
        !sending.has(localId)
      ) {
        sending.set(localId, localDraftOf(snapshot, "sending", 0));
      }
    }
    this.#kept = next;
    this.#sending = sending;
  }

  // The store kept `kept` for `localId` now, or nothing when null.
  #keptChanged(localId: string, kept: KeptComposer | null): void {
    this.#keptSeq += 1;
    this.#keptAt.set(localId, this.#keptSeq);
    const next = new Map(this.#kept);
    if (kept === null) next.delete(localId);
    else next.set(localId, keptDraftOf(kept, Date.now()));
    this.#kept = next;
  }

  #markSending(snapshot: ComposeSnapshot): void {
    this.#sending = withEntry(
      this.#sending,
      snapshot.localId,
      localDraftOf(snapshot, "sending", Date.now()),
    );
  }

  #stopSending(localId: string): void {
    this.#sending = withoutEntry(this.#sending, localId);
  }

  #throw(draft: LocalDraft): void {
    this.#thrown = withEntry(this.#thrown, draft.localId, {
      ...draft,
      state: "deleted",
    });
  }

  #unthrow(localId: string): void {
    this.#thrown = withoutEntry(this.#thrown, localId);
  }

  // Gmail's ids for a draft this session holds only in memory (thrown away or being sent).
  #patchHeld(
    localId: string,
    patch: Pick<LocalDraft, "messageId" | "draftThreadId">,
  ): void {
    const thrown = this.#thrown.get(localId);
    if (thrown) {
      this.#thrown = withEntry(this.#thrown, localId, { ...thrown, ...patch });
    }
    const sending = this.#sending.get(localId);
    if (sending) {
      this.#sending = withEntry(this.#sending, localId, {
        ...sending,
        ...patch,
      });
    }
  }

  // Every composer of `localId` this hub holds: open, parked, behind the Saved pill or a discard's undo.
  #composersOf(localId: string): Composer[] {
    return composersOf(localId, [
      this.floating,
      this.inline,
      this.parked,
      this.#closed,
      ...[...this.#discarding.values()].map(({ composer }) => composer),
    ]);
  }

  // Forget local drafts whose send went once the open thread shows Gmail's copy, or at once when
  // their thread isn't open.
  #settleSent(detail: ThreadDetail | null): void {
    for (const [localId, historyId] of this.#sentLocal) {
      const draft = this.localDrafts.get(localId);
      const stillSending =
        draft !== undefined &&
        detail !== null &&
        holdsThread(detail, draft.threadId) &&
        !detail.messages.some((message) => message.id === draft.messageId) &&
        (detail.historyId === historyId ||
          detail.drafts.some((message) => message.id === draft.messageId));
      if (!stillSending) {
        this.#sentLocal.delete(localId);
        this.#stopSending(localId);
      }
    }
  }

  async #start(): Promise<void> {
    await this.#mail.unparked;
    const cached = await this.#mail.cache
      .get("meta", SEND_AS_KEY)
      .catch(() => undefined);
    if (this.#disposed) return;
    const saved = sendAsFrom(cached);
    if (saved !== null) this.sendAs = saved.list;
    await this.#loadJobs();
    if (this.#disposed) return;
    // Contacts also give known senders their photos.
    void this.contacts.ensure();
    if (saved !== null && Date.now() - saved.at < SEND_AS_MAX_AGE_MS) return;
    await this.reloadSendAs();
  }

  /** Fetch the Gmail send-as addresses again and keep them on the device. */
  reloadSendAs = async (): Promise<void> => {
    try {
      const sendAs = await this.#mail.gmail.listSendAs();
      if (this.#disposed) return;
      this.sendAs = sendAs;
      void this.#mail.cache
        .put("meta", SEND_AS_KEY, { at: Date.now(), list: sendAs })
        .catch((error) => console.warn("send-as cache write failed", error));
    } catch (error) {
      if (!isNetworkError(error)) console.error("send-as list failed", error);
    }
  };

  /** Stop listeners; discards whose undo is still open are carried out. */
  dispose(): void {
    void this.finishDiscards();
    this.#disposed = true;
    for (const dispose of this.#disposers) dispose();
    this.#disposers = [];
    this.floating?.cancelAutosave();
    this.inline?.cancelAutosave();
    this.parked?.cancelAutosave();
  }

  #composer(snapshot: ComposeSnapshot): Composer {
    const composer: Composer = new Composer(
      snapshot,
      (saving) => void this.save(saving),
      () => {
        const entry = this.outboxOf(composer);
        return entry?.status === "waiting" ? entry.sendAt : null;
      },
    );
    return composer;
  }

  /** The unsent send of a composer's draft (scheduled, under way or failed), if it has one. */
  outboxOf(composer: Composer): OutboxDoc | null {
    return outboxForDraft(this.outbox.values(), composer.draftId);
  }

  /** The address to start from, falling back to the Gmail address before the list loads. */
  #fallbackFrom(): string {
    return this.defaultFrom?.mailbox.email ?? this.#mail.email;
  }

  /** Open the new-message composer (focusing the one already open). */
  compose = (): Composer => {
    if (this.floating) return this.floating;
    void this.contacts.ensure();
    const composer = this.#composer(
      blankSnapshot(randomId(), this.#fallbackFrom()),
    );
    this.floating = composer;
    return composer;
  };

  /**
   * Reply, reply all or forward to the open thread's last message, inside the thread card. A reply
   * this device already started for the thread opens again instead.
   */
  answer = async (mode: Exclude<ComposeMode, "new">): Promise<void> => {
    const detail = this.#mail.detail;
    const message = detail?.messages.at(-1);
    if (!detail || !message) return;
    // A copy cached before replies existed lacks the headers; the fresh one is on its way.
    if (!message.headers) {
      this.pendingAnswer = { threadId: detail.id, mode };
      return;
    }
    void this.contacts.ensure();
    if (this.inline !== null && holdsThread(detail, this.inline.threadId)) {
      return;
    }
    if (this.parked !== null && holdsThread(detail, this.parked.threadId)) {
      this.unpark();
      return;
    }
    const kept = await this.#keptAnswer(detail);
    if (this.#mail.openId !== detail.id) return;
    if (kept) {
      this.inline = this.#composer(kept);
      return;
    }
    const source = sourceOf(message, detail.subject);
    const fields = answerFields(
      source,
      mode,
      this.ownAddresses,
      this.#fallbackFrom(),
    );
    const snapshot: ComposeSnapshot = {
      ...blankSnapshot(randomId(), fields.from),
      ...fields,
      mode,
      threadId: message.threadId,
      markdown:
        mode === "forward" ? forwardMarkdown(source, this.#mail.now) : "",
      quote: mode === "forward" ? null : quoteOf(message),
    };
    const composer = this.#composer(snapshot);
    this.inline = composer;
    if (mode === "forward") void this.#forwardFiles(composer, message);
    else void this.#quoteImages(composer, message);
  };

  /** How the thread's quick reply answers its last message: Reply all when that reaches more than one other person. */
  quickReplyMode(detail: ThreadDetail): "reply" | "replyAll" {
    const message = detail.messages.at(-1);
    if (!message?.headers) return "reply";
    const { from, replyTo, to, cc } = sourceOf(message, detail.subject);
    return offersReplyAll({ from, replyTo, to, cc }, this.ownAddresses)
      ? "replyAll"
      : "reply";
  }

  /**
   * Answer the open thread's last message with what was typed in the thread's quick reply: send
   * it now, through the same draft, encryption and undo-send path as Send, or open it in the full
   * composer. A reply this device already started for the thread opens with the text added
   * instead, and anything that stops the send opens the composer to show why. Resolves false when
   * the thread can't be answered yet, so the text should stay where it was typed.
   */
  quickReply = async (
    mode: "reply" | "replyAll",
    markdown: string,
    action: "send" | "open",
  ): Promise<boolean> => {
    const detail = this.#mail.detail;
    const message = detail?.messages.at(-1);
    if (!detail || !message?.headers) return false;
    if (this.inline !== null && holdsThread(detail, this.inline.threadId)) {
      return false;
    }
    void this.contacts.ensure();
    const kept = await this.#keptAnswer(detail);
    if (this.#mail.openId !== detail.id || this.inline !== null) return false;
    if (kept) {
      const composer = this.#composer(kept);
      composer.markdown =
        kept.markdown.trim() === ""
          ? markdown
          : `${kept.markdown}\n${markdown}`;
      composer.html = null;
      this.inline = composer;
      composer.changed();
      return true;
    }
    const fields = answerFields(
      sourceOf(message, detail.subject),
      mode,
      this.ownAddresses,
      this.#fallbackFrom(),
    );
    const composer = this.#composer({
      ...blankSnapshot(randomId(), fields.from),
      ...fields,
      mode,
      threadId: message.threadId,
      markdown,
      quote: quoteOf(message),
    });
    if (action === "open") {
      this.inline = composer;
      composer.changed();
      void this.#quoteImages(composer, message);
      return true;
    }
    await this.#quoteImages(composer, message);
    const snapshot = composer.snapshot();
    const built = this.build(snapshot);
    composer.error = sendProblem(snapshot, built.size) ?? built.problem;
    if (composer.error === null && (await this.#readyToGo(composer))) {
      void this.send(composer);
    } else {
      this.#reopen(composer);
      composer.changed();
    }
    return true;
  };

  /**
   * Attach the inline images the quote of `message` shows, under their original Content-IDs, so
   * they don't show broken; ones already there are kept.
   */
  async #quoteImages(composer: Composer, message: MessageView): Promise<void> {
    const have = new Set(composer.inlineImages.map((image) => image.contentId));
    const parts = quotedContentIds(composer.quote?.html ?? null)
      .filter((id) => !have.has(id))
      .map((id) => message.body.inline.find((part) => part.contentId === id))
      .filter((part) => part !== undefined);
    if (parts.length === 0) return;
    composer.loadingFiles += parts.length;
    for (const [index, part] of parts.entries()) {
      try {
        const data = part.data
          ? decodeBase64Url(part.data)
          : part.attachmentId
            ? await this.#mail.attachmentBytes(
                message.id,
                part.partId,
                part.attachmentId,
              )
            : null;
        if (data !== null) {
          composer.inlineImages = [
            ...composer.inlineImages,
            {
              id: randomId(),
              contentId: part.contentId,
              filename: `quoted-${index + 1}.${extensionOf(part.mimeType)}`,
              mimeType: part.mimeType,
              data,
            },
          ];
        }
      } catch (error) {
        // The quote shows that image broken; the reply still goes.
        if (!isNetworkError(error)) {
          console.error("quoted image fetch failed", error);
        }
      } finally {
        composer.loadingFiles--;
      }
    }
  }

  /** The message a reply draft answers, from the open thread or else fetched; null when gone. */
  async #repliedTo(
    threadId: string | null,
    inReplyTo: string | null,
  ): Promise<MessageView | null> {
    if (threadId === null || inReplyTo === null) return null;
    const find = (messages: readonly MessageView[]) =>
      messages.find((message) => message.headers?.messageId === inReplyTo) ??
      null;
    const detail = this.#mail.detail;
    const open =
      detail !== null && holdsThread(detail, threadId)
        ? find(detail.messages)
        : null;
    if (open !== null) return open;
    try {
      return find(
        threadDetail(await this.#mail.gmail.getThread(threadId, "full"))
          .messages,
      );
    } catch (error) {
      if (!isNetworkError(error)) console.error("thread fetch failed", error);
      return null;
    }
  }

  /** Answer a thread from the list: open it, then reply once its messages are in. */
  answerThread = (
    threadId: string,
    mode: Exclude<ComposeMode, "new">,
  ): void => {
    if (this.#mail.openId === threadId && this.#mail.detail?.id === threadId) {
      void this.answer(mode);
    } else {
      if (this.#mail.openId !== threadId) this.#mail.open(threadId);
      this.pendingAnswer = { threadId, mode };
    }
  };

  // A scheduled send isn't a reply to pick up again: it shows only in the Outbox.
  async #keptAnswer(detail: ThreadDetail): Promise<ComposeSnapshot | null> {
    const kept = await this.#store.composers().catch(() => []);
    const scheduled = outboxDraftIds(this.outbox.values());
    for (const [, snapshot] of kept) {
      if (
        holdsThread(detail, snapshot.threadId) &&
        snapshot.mode !== "new" &&
        (snapshot.draftId === null || !scheduled.has(snapshot.draftId))
      ) {
        return snapshot;
      }
    }
    return null;
  }

  async #forwardFiles(composer: Composer, message: MessageView): Promise<void> {
    const files = message.body.attachments;
    if (files.length === 0) return;
    composer.loadingFiles += files.length;
    for (const file of files) {
      try {
        const data = file.data
          ? decodeBase64Url(file.data)
          : file.attachmentId
            ? await this.#mail.attachmentBytes(
                message.id,
                file.partId,
                file.attachmentId,
              )
            : new Uint8Array();
        composer.attachments = [
          ...composer.attachments,
          {
            id: randomId(),
            filename: file.filename,
            mimeType: file.mimeType,
            data,
          },
        ];
      } catch (error) {
        composer.error = `Couldn't add ${file.filename}: ${errorText(error)}`;
      } finally {
        composer.loadingFiles--;
      }
    }
    composer.changed();
  }

  /**
   * Open a saved Gmail draft, files included: in place inside its thread when that thread is open
   * and the draft answers it, else in the floating composer.
   */
  openDraft = async (draftId: string): Promise<void> => {
    if (this.parked !== null && this.parked.draftId === draftId) {
      this.unpark();
      return;
    }
    let message: GmailMessage;
    try {
      message = await this.#draftMessage(draftId);
    } catch (error) {
      const ref = this.#mail
        .offlineDrafts()
        .refs.find((entry) => entry.draftId === draftId);
      if (isNetworkError(error)) {
        await this.#openSaved({ messageId: ref?.messageId ?? "" });
      } else {
        this.#notice(`Couldn't open the draft. ${errorText(error)}`, true);
      }
      return;
    }
    try {
      await this.#showDraft(
        snapshotFromDraft(draftId, message, randomId()),
        message.id,
        true,
      );
    } catch (error) {
      this.#notice(`Couldn't open the draft. ${errorText(error)}`, true);
    }
  };

  // Open a draft read back from Gmail or the saved copy: in place inside its thread when that
  // thread is open and the draft answers it, else in the floating composer. The saved copy may be
  // older than Gmail's draft, so a draft opened from it isn't `inGmail` and Send uploads it first.
  async #showDraft(
    {
      snapshot,
      files,
      quoteText,
    }: {
      snapshot: ComposeSnapshot;
      files: DraftFiles;
      quoteText: string | null;
    },
    messageId: string,
    inGmail: boolean,
  ): Promise<void> {
    const replied =
      quoteText === null
        ? null
        : await this.#repliedTo(snapshot.threadId, snapshot.inReplyTo);
    const quote: QuotedMessage | null =
      quoteText === null
        ? null
        : replied !== null
          ? quoteOf(replied)
          : quoteFromText(quoteText);
    const composer = this.#composer({ ...snapshot, quote });
    composer.saveStatus = "saved";
    composer.inGmail = inGmail;
    if (this.#mail.inOpenThread(snapshot.threadId)) {
      if (this.inline) await this.close(this.inline);
      this.inline = composer;
    } else {
      if (this.floating) await this.close(this.floating);
      this.floating = composer;
    }
    void this.contacts.ensure();
    const fetchBytes = async (part: {
      partId: string;
      data: string | null;
      attachmentId: string | null;
    }) =>
      part.data
        ? decodeBase64Url(part.data)
        : part.attachmentId
          ? await this.#mail.attachmentBytes(
              messageId,
              part.partId,
              part.attachmentId,
            )
          : new Uint8Array();
    composer.loadingFiles = files.attachments.length + files.inline.length;
    const images: ComposeImage[] = [];
    for (const [index, part] of files.inline.entries()) {
      images.push({
        id: randomId(),
        contentId: part.contentId,
        filename: `image-${index + 1}.${extensionOf(part.mimeType)}`,
        mimeType: part.mimeType,
        data: await fetchBytes(part),
      });
      composer.loadingFiles--;
    }
    composer.inlineImages = images;
    if (replied !== null) void this.#quoteImages(composer, replied);
    for (const part of files.attachments) {
      const data = await fetchBytes(part);
      composer.attachments = [
        ...composer.attachments,
        {
          id: randomId(),
          filename: part.filename,
          mimeType: part.mimeType,
          data,
        },
      ];
      composer.loadingFiles--;
    }
  }

  /**
   * Open a draft while Gmail can't be reached: this device's own copy when it has one, else the
   * saved copy's when it holds all of it (see `offlineEditable`), else say it opens once back
   * online.
   */
  async #openSaved(target: {
    messageId?: string;
    threadIds?: readonly string[];
  }): Promise<void> {
    const threads = new Set(target.threadIds ?? []);
    for (const draft of this.localDrafts.values()) {
      const threadId = draft.draftThreadId ?? draft.threadId;
      const matches =
        (target.messageId !== undefined &&
          draft.messageId === target.messageId) ||
        (threadId !== null && threads.has(threadId));
      if (draft.state !== "draft" || !matches) continue;
      const kept = await this.#store
        .composer(draft.localId)
        .catch(() => undefined);
      if (kept === undefined) continue;
      await this.openLocalDraft(draft.localId);
      return;
    }
    const ref = offlineDraftOf(this.#mail.offlineDrafts(), target);
    const message =
      ref === null
        ? null
        : ((await this.#mail.savedThread(ref.threadId))?.drafts.find(
            (draft) => draft.id === ref.messageId,
          ) ?? null);
    const read =
      ref === null ||
      message === null ||
      !offlineEditable(savedDraftParts(message))
        ? null
        : snapshotFromSaved(ref.draftId, message, randomId());
    if (read === null || message === null) {
      this.#notice(OPENS_ONLINE, false);
      return;
    }
    try {
      await this.#showDraft(read, message.id, false);
    } catch (error) {
      this.#notice(`Couldn't open the draft. ${errorText(error)}`, true);
    }
  }

  /** A draft's message; an encrypted one decrypted (drafts are encrypted to the sender too). */
  async #draftMessage(draftId: string): Promise<GmailMessage> {
    const draft = await this.#mail.gmail.getDraft(draftId, "full");
    if (!isEncryptedMessage(draft.message)) return draft.message;
    const { raw } = (await this.#mail.gmail.getDraft(draftId, "raw")).message;
    if (!raw) throw new Error("no raw draft");
    const decrypted = await this.#mail.encryption.open(
      decodeBase64Url(raw),
      "",
    );
    return decryptedDraft(draft.message, decrypted, await loadPgp());
  }

  /** A draft read back whole, files included, without opening a composer; null when gone. */
  async #loadDraft(draftId: string): Promise<ComposeSnapshot | null> {
    const message = await this.#draftMessage(draftId);
    const { snapshot, files, quoteText } = snapshotFromDraft(
      draftId,
      message,
      randomId(),
    );
    const replied =
      quoteText === null
        ? null
        : await this.#repliedTo(snapshot.threadId, snapshot.inReplyTo);
    const quote =
      quoteText === null
        ? null
        : replied !== null
          ? quoteOf(replied)
          : quoteFromText(quoteText);
    const bytes = async (part: {
      partId: string;
      data: string | null;
      attachmentId: string | null;
    }) =>
      part.data
        ? decodeBase64Url(part.data)
        : part.attachmentId
          ? await this.#mail.attachmentBytes(
              message.id,
              part.partId,
              part.attachmentId,
            )
          : new Uint8Array();
    const inlineImages: ComposeImage[] = [];
    for (const [index, part] of files.inline.entries()) {
      inlineImages.push({
        id: randomId(),
        contentId: part.contentId,
        filename: `image-${index + 1}.${extensionOf(part.mimeType)}`,
        mimeType: part.mimeType,
        data: await bytes(part),
      });
    }
    const attachments = [];
    for (const part of files.attachments) {
      attachments.push({
        id: randomId(),
        filename: part.filename,
        mimeType: part.mimeType,
        data: await bytes(part),
      });
    }
    return { ...snapshot, quote, inlineImages, attachments };
  }

  /**
   * Open the draft of a row in Drafts in the composer, a reply's too, its thread left closed; a
   * row with no draft left opens its thread.
   */
  openThreadDraft = async (threadId: string): Promise<void> => {
    const members = this.#mail.membersOf(threadId);
    let drafts: GmailDraft[];
    try {
      drafts = await this.#mail.gmail.listDrafts();
    } catch (error) {
      if (isNetworkError(error)) {
        await this.#openSaved({ threadIds: members });
      } else {
        this.#notice(`Couldn't open the draft. ${errorText(error)}`, true);
      }
      return;
    }
    const match = draftIn(drafts, members);
    if (match) await this.openDraft(match.id);
    else this.#mail.open(threadId);
  };

  /**
   * Open a draft shown in a thread: the device's copy, else Gmail's. One with neither left goes
   * from where it was listed, saying so.
   */
  openLocalDraft = async (localId: string): Promise<void> => {
    const kept = await this.#store.composer(localId).catch(() => undefined);
    if (kept === undefined) {
      const messageId = this.localDrafts.get(localId)?.messageId;
      if (messageId && (await this.#openDraftMessage(messageId))) return;
      if (this.localDrafts.get(localId)?.state !== "draft") return;
      if (await this.#store.holds(localId).catch(() => true)) {
        this.#notice(LOCKED_DRAFT, false);
      } else {
        await this.#forget(localId);
        this.#notice(DRAFT_GONE, false);
      }
      return;
    }
    if (this.parked?.localId === localId) {
      this.unpark();
      return;
    }
    if (
      [this.floating, this.inline].some((open) => open?.localId === localId)
    ) {
      return;
    }
    const composer = this.#composer(kept);
    composer.saveStatus = this.jobs.has(localId) ? "queued" : "saved";
    void this.contacts.ensure();
    if (kept.mode !== "new" && this.#mail.inOpenThread(kept.threadId)) {
      if (this.inline) await this.close(this.inline);
      this.inline = composer;
    } else {
      if (this.floating) await this.close(this.floating);
      this.floating = composer;
    }
  };

  /** Open the Gmail draft whose message is `messageId` (a draft shown in a thread). */
  openDraftMessage = async (messageId: string): Promise<void> => {
    await this.#openDraftMessage(messageId);
  };

  // False only when Gmail answered and has no such draft.
  async #openDraftMessage(messageId: string): Promise<boolean> {
    let drafts: GmailDraft[];
    try {
      drafts = await this.#mail.gmail.listDrafts();
    } catch (error) {
      if (isNetworkError(error)) {
        await this.#openSaved({ messageId });
      } else {
        this.#notice(`Couldn't open the draft. ${errorText(error)}`, true);
      }
      return true;
    }
    const match = drafts.find((entry) => entry.message.id === messageId);
    if (match === undefined) return false;
    await this.openDraft(match.id);
    return true;
  }

  /**
   * Put a composer away, saving its draft. A blank one Gmail has no draft of is thrown away
   * instead, with a save of it still queued or under way (whose draft is deleted once it lands).
   */
  close = async (composer: Composer): Promise<void> => {
    if (this.floating === composer) this.floating = null;
    if (this.inline === composer) this.inline = null;
    if (this.parked === composer) this.parked = null;
    if (closeThrowsAway(composer.snapshot(), this.#draftIdOf(composer))) {
      composer.cancelAutosave();
      await this.#finishDiscard(composer);
      return;
    }
    if (composer.pendingAutosave || composer.saveStatus !== "saved") {
      await this.save(composer);
    }
    const busy = this.pill?.kind === "undo" || this.pill?.kind === "discarded";
    if (!busy && this.outboxOf(composer) === null) {
      this.#closed = composer;
      this.pill = {
        kind: "saved",
        localId: composer.localId,
        expiresAt: Date.now() + NOTICE_MS,
      };
    }
  };

  /**
   * Park a message on a phone: it leaves the screen for the strip at the bottom, keeping everything
   * typed, and its draft is saved now.
   */
  park = (composer: Composer): void => {
    if (this.floating !== composer && this.inline !== composer) return;
    if (this.floating === composer) this.floating = null;
    if (this.inline === composer) this.inline = null;
    this.parked = composer;
    const blank = isBlank(composer.snapshot());
    if (
      !(blank && composer.draftId === null) &&
      (composer.pendingAutosave || composer.saveStatus !== "saved")
    ) {
      void this.save(composer);
    }
  };

  /** Open the parked message again where it belongs: in its open thread for a reply there, else floating. */
  unpark = (): void => {
    const composer = this.parked;
    if (composer === null) return;
    this.parked = null;
    if (composer.mode !== "new" && this.#mail.inOpenThread(composer.threadId)) {
      this.inline = composer;
    } else {
      this.floating = composer;
    }
  };

  /** Open again the message the "Saved to Drafts" pill is about. */
  openSaved = async (): Promise<void> => {
    const pill = this.pill;
    const composer = this.#closed;
    if (pill?.kind !== "saved" || composer?.localId !== pill.localId) return;
    this.pill = null;
    this.#closed = null;
    await this.#showAgain(composer);
  };

  // Show a composer that was put away again where it belongs, putting away the one in its place.
  async #showAgain(composer: Composer): Promise<void> {
    if (composer.mode !== "new" && this.#mail.inOpenThread(composer.threadId)) {
      if (this.inline !== null && this.inline !== composer) {
        await this.close(this.inline);
      }
      this.inline = composer;
    } else {
      if (this.floating !== null && this.floating !== composer) {
        await this.close(this.floating);
      }
      this.floating = composer;
    }
  }

  /**
   * Throw a message away. It leaves the screen and its thread at once and the pill offers it back;
   * only when that offer ends is it forgotten here and its draft deleted in Gmail (a scheduled send
   * of it canceled). Nothing saved yet goes at once, with nothing to offer back.
   */
  discard = async (composer: Composer): Promise<void> => {
    composer.cancelAutosave();
    if (this.floating === composer) this.floating = null;
    if (this.inline === composer) this.inline = null;
    if (this.parked === composer) this.parked = null;
    const snapshot = $state.snapshot(composer.snapshot()) as ComposeSnapshot;
    const localId = snapshot.localId;
    if (
      isBlank(snapshot) &&
      composer.draftId === null &&
      !this.jobs.has(localId)
    ) {
      await this.#finishDiscard(composer);
      this.#notice("Draft discarded", false);
      return;
    }
    // Gone from its thread and Drafts at once; Gmail's copy stays hidden until it is deleted.
    const messageId =
      snapshot.messageId ?? this.localDrafts.get(localId)?.messageId ?? null;
    this.#throw(
      localDraftOf({ ...snapshot, messageId }, "deleted", Date.now()),
    );
    clearTimeout(this.#discarding.get(localId)?.timer);
    this.#discarding.set(localId, {
      composer,
      timer: setTimeout(() => {
        const pending = this.#discarding.get(localId);
        if (pending?.composer !== composer) return;
        this.#discarding.delete(localId);
        void this.#finishDiscard(composer);
      }, UNDO_MS),
    });
    this.pill = {
      kind: "discarded",
      localId,
      expiresAt: Date.now() + UNDO_MS,
    };
  };

  /** Take back the last discard: the message opens again where it was, its draft untouched. */
  undoDiscard = async (): Promise<void> => {
    const pill = this.pill;
    if (pill?.kind !== "discarded") return;
    this.pill = null;
    const pending = this.#discarding.get(pill.localId);
    if (pending === undefined) return;
    this.#discarding.delete(pill.localId);
    clearTimeout(pending.timer);
    this.#unthrow(pill.localId);
    await this.#showAgain(pending.composer);
  };

  /**
   * End every discard's undo now and carry it out: once sign-out is confirmed, and as the page goes
   * away, so a discarded draft doesn't come back. Resolves once the device holds each delete; the
   * deletes in Gmail run on.
   */
  finishDiscards = async (): Promise<void> => {
    const pending = [...this.#discarding.values()];
    this.#discarding.clear();
    if (this.pill?.kind === "discarded") this.pill = null;
    await Promise.all(
      pending.map(({ composer, timer }) => {
        clearTimeout(timer);
        return this.#finishDiscard(composer);
      }),
    );
  };

  // The discard itself, once its undo has passed: forget it here and delete its draft in Gmail.
  // Every device write starts before the first wait, so one made as the page goes away still lands.
  // Resolves once those writes land, without waiting for Gmail.
  async #finishDiscard(composer: Composer): Promise<void> {
    const localId = composer.localId;
    this.#discarded.add(localId);
    const draftId = this.#draftIdOf(composer);
    const forgotten =
      draftId === null
        ? this.#forget(localId).then(() => this.#unthrow(localId))
        : Promise.resolve();
    this.#dropJob(composer.localId);
    const entry = outboxForDraft(this.outbox.values(), draftId);
    if (entry !== null) {
      this.#unwrite(entry.id);
      void deleteDoc(this.#outboxRef(entry.id)).catch((error) =>
        console.error("cancel failed", error),
      );
    }
    const deleted =
      draftId === null
        ? Promise.resolve()
        : this.#queueDelete({
            localId: composer.localId,
            draftId,
            messageId:
              this.localDrafts.get(composer.localId)?.messageId ??
              composer.messageId,
            threadId: composer.threadId,
            deleted: true,
          });
    await Promise.all([forgotten, deleted]);
  }

  // Keep a draft's delete on the device in place of its composer, hidden from its thread, and try it.
  // Resolves once the device holds the delete; the delete in Gmail runs on.
  async #queueDelete(pending: PendingDelete): Promise<void> {
    this.#pendingDeletes.set(pending.localId, pending);
    this.#throw(deletedDraftOf(pending));
    await this.#store
      .putDelete(pending)
      .catch((error) => console.error("draft delete write failed", error));
    void this.#runDeletes();
  }

  // Delete thrown-away drafts in Gmail; those the network can't take yet wait for the next flush.
  async #runDeletes(): Promise<void> {
    for (const pending of [...this.#pendingDeletes.values()]) {
      if (this.#disposed) return;
      if (this.#deleting.has(pending.localId)) continue;
      this.#deleting.add(pending.localId);
      try {
        await this.#mail.gmail.deleteDraft(pending.draftId);
      } catch (error) {
        const gone = error instanceof GmailError && draftGone(error.status);
        if (!gone && (isRetryable(error) || !(error instanceof GmailError))) {
          return;
        }
        if (!gone) console.error("draft delete refused", error);
      } finally {
        this.#deleting.delete(pending.localId);
      }
      if (this.#pendingDeletes.get(pending.localId) === pending) {
        this.#pendingDeletes.delete(pending.localId);
        await this.#forget(pending.localId);
        this.#unthrow(pending.localId);
      }
    }
  }

  async #forget(localId: string): Promise<void> {
    await this.#store.deleteComposer(localId).catch(() => {});
  }

  // The Gmail draft a composer is saved as: its own, else its queued save's or its record's.
  #draftIdOf(composer: Composer): string | null {
    return (
      composer.draftId ??
      this.jobs.get(composer.localId)?.draftId ??
      this.#kept.get(composer.localId)?.draftId ??
      null
    );
  }

  #setJob(job: DraftJob): void {
    const next = new Map(this.jobs);
    next.set(job.localId, job);
    this.jobs = next;
    void this.#store
      .putJob($state.snapshot(job) as DraftJob)
      .catch((error) => console.error("draft queue write failed", error));
  }

  #dropJob(localId: string): void {
    this.#settled.add(localId);
    if (this.#docsWritten.has(localId)) {
      const written = new Map(this.#docsWritten);
      written.delete(localId);
      this.#docsWritten = written;
    }
    if (!this.jobs.has(localId)) return;
    const next = new Map(this.jobs);
    next.delete(localId);
    this.jobs = next;
    void this.#store.deleteJob(localId).catch(() => {});
  }

  #mailboxFor(email: string): Mailbox {
    return optionFor(this.fromOptions, email)?.mailbox ?? { name: "", email };
  }

  /**
   * The message as Gmail takes it (base64url) and its size in bytes, or what keeps it from being
   * built (an invalid address).
   */
  build(
    snapshot: ComposeSnapshot,
  ):
    | { raw: string; size: number; problem: null }
    | { raw: null; size: 0; problem: string } {
    try {
      const mime = buildMime(
        composeInput(
          snapshot,
          this.#mailboxFor(snapshot.from),
          this.settings.send.signature,
          randomBoundary(),
        ),
      );
      return { raw: gmailRaw(mime), size: mime.length, problem: null };
    } catch (error) {
      const problem = buildProblem(error);
      if (problem === null) console.error("building the message failed", error);
      return {
        raw: null,
        size: 0,
        problem: problem ?? "Couldn't put the message together.",
      };
    }
  }

  /** Whether `email` is an address the user can send from. */
  canSendFrom(email: string): boolean {
    return optionFor(this.fromOptions, email) !== undefined;
  }

  /**
   * Send a message made here rather than in a composer (an unsubscribe request, an RSVP) through
   * the normal path: a Gmail draft, then the outbox, queued while offline. No undo and no
   * signature; the pill reports how it went. Resolves false when the message couldn't be built.
   */
  sendMessage = async (message: {
    from: string | null;
    to: Mailbox[];
    subject: string;
    markdown: string;
    threadId: string | null;
    calendar?: CalendarPart | null;
  }): Promise<boolean> => {
    const from = this.#mailboxFor(
      message.from !== null && this.canSendFrom(message.from)
        ? message.from
        : this.#fallbackFrom(),
    );
    let mime: string;
    try {
      mime = buildMime({
        from,
        to: message.to,
        cc: [],
        bcc: [],
        subject: message.subject,
        markdown: message.markdown,
        signature: null,
        inReplyTo: null,
        references: [],
        inlineImages: [],
        attachments: [],
        calendar: message.calendar ?? null,
        extraHeaders: await this.#autocrypt(from.email),
        boundary: randomBoundary(),
      });
    } catch (error) {
      const problem = buildProblem(error);
      if (problem === null) console.error("building the message failed", error);
      this.#notice(
        `Not sent. ${problem ?? "Couldn't put the message together."}`,
        true,
      );
      return false;
    }
    const localId = randomId();
    this.#sending = withEntry(this.#sending, localId, {
      localId,
      messageId: null,
      state: "sending",
      updatedAt: Date.now(),
      threadId: message.threadId,
      from: from.email,
      to: message.to,
      cc: [],
      bcc: [],
      subject: message.subject,
      snippet: draftSnippet(message.markdown),
    });
    this.#setJob(
      queueSave(undefined, {
        localId,
        draftId: null,
        threadId: message.threadId,
        from: from.email,
        raw: gmailRaw(mime),
        // No undo is offered, so it goes at once (as on Android), not the undo delay later.
        send: {
          kind: "now",
          outboxId: null,
          queuedAt: Date.now() - UNDO_SEND_DELAY_MS,
        },
      }),
    );
    await this.flush();
    return true;
  };

  /** Look up recipients' keys in the background, for the locks on their chips. */
  lookUpKeys(addresses: readonly string[]): void {
    const encryption = this.#mail.encryption;
    if (!encryption.hasKey) return;
    for (const address of addresses) {
      const key = addressKey(address);
      if (
        key === "" ||
        this.recipientKeys.has(key) ||
        this.#lookingUp.has(key)
      ) {
        continue;
      }
      this.#lookingUp.add(key);
      void encryption.keys
        .keyFor(key)
        .catch(() => null)
        .then((found) => {
          this.#lookingUp.delete(key);
          const next = new Map(this.recipientKeys);
          next.set(key, found);
          this.recipientKeys = next;
        });
    }
  }

  /** Show the dialog that unlocks encrypted mail on this device. */
  requestUnlock = (): void => {
    this.#mail.encryption.requestUnlock();
  };

  /** How a composer's message will go: encrypted, or why not. */
  encryptionOf(composer: Composer): SendEncryption {
    const encryption = this.#mail.encryption;
    return sendEncryption(
      composer.from,
      [...composer.to, ...composer.cc, ...composer.bcc],
      this.recipientKeys,
      encryption.hasKey,
      encryption.unlocked,
    );
  }

  /** The `Autocrypt` header for mail from an address, when it is @hafa.cc and the account has a key. */
  async #autocrypt(from: string): Promise<[string, string][]> {
    if (!isEncryptingAddress(from)) return [];
    try {
      const value = await this.#mail.encryption.autocryptHeader(from);
      return value === null ? [] : [["Autocrypt", value]];
    } catch (error) {
      console.error("autocrypt header failed", error);
      return [];
    }
  }

  /**
   * What encrypting a message needs, or null when it goes as is. `strict`: throw "locked" rather
   * than go as is when everyone has a key but this device hasn't unlocked ours.
   */
  async #encryptKeys(
    from: string,
    recipients: readonly Mailbox[],
    strict: boolean,
  ): Promise<EncryptKeys | null> {
    const encryption = this.#mail.encryption;
    const own = encryption.doc?.publicKey;
    if (own === undefined || !isEncryptingAddress(from)) return null;
    if (recipients.length === 0) return null;
    const found = await Promise.all(
      recipients.map(
        async (mailbox) =>
          [
            addressKey(mailbox.email),
            await encryption.keys.keyFor(mailbox.email).catch(() => null),
          ] as const,
      ),
    );
    const keys = new Map<string, string>();
    for (const [address, key] of found) {
      if (key === null) return null;
      keys.set(address, key);
    }
    const signing = await encryption.privateKey();
    if (signing === null) {
      if (strict) throw new Error("locked");
      return null;
    }
    return { recipients: keys, own, signing };
  }

  /**
   * A composer's content as Gmail takes it (base64url): one message, or for a split encrypted send
   * the main message and then its Bcc copies. A send (`split`) never goes unencrypted for lack of an
   * unlocked key unless `plain` says the user chose that.
   */
  async #outgoing(
    snapshot: ComposeSnapshot,
    split: boolean,
    plain = false,
  ): Promise<{ raw: string; copyFor: string | null }[]> {
    const input = composeInput(
      snapshot,
      this.#mailboxFor(snapshot.from),
      this.settings.send.signature,
      randomBoundary(),
    );
    input.extraHeaders = await this.#autocrypt(snapshot.from);
    const keys = await this.#encryptKeys(
      snapshot.from,
      [...snapshot.to, ...snapshot.cc, ...snapshot.bcc],
      split && !plain,
    );
    const pgp = keys === null ? null : await loadPgp();
    const messages =
      pgp === null
        ? [buildMime(input)]
        : await outgoingMessages(input, keys, split, pgp);
    return messages.map((mime) => ({
      raw: gmailRaw(mime),
      copyFor: copyRecipient(mime),
    }));
  }

  /**
   * Send a draft Gmail already holds as it stands without saving it again: one unchanged since it
   * was saved or opened, not encrypted (an earlier save may have been made while encrypted mail
   * was locked), with no Bcc copies, while online. Writes its outbox doc and returns true; false
   * when it needs the usual save first.
   */
  #sendSaved(
    composer: Composer,
    snapshot: ComposeSnapshot,
    send: PendingSend,
  ): boolean {
    const draftId = composer.draftId;
    const threadId = snapshot.threadId ?? composer.draftThreadId;
    const encrypts =
      this.#mail.encryption.doc?.publicKey !== undefined &&
      isEncryptingAddress(snapshot.from);
    if (
      draftId === null ||
      threadId === null ||
      encrypts ||
      // Offline it waits on the device like any send.
      this.#mail.offline ||
      !composer.inGmail ||
      this.jobs.has(snapshot.localId) ||
      (send.outboxId !== null &&
        sendGroup(this.outbox.values(), send.outboxId).length > 1)
    ) {
      return false;
    }
    const job: DraftJob = {
      localId: snapshot.localId,
      draftId,
      threadId: snapshot.threadId,
      from: snapshot.from,
      raw: "",
      version: 0,
      send,
    };
    void this.#writeOutbox(
      job,
      { draftId, messageId: composer.messageId, threadId },
      send,
    ).then(() => {
      // No save is queued for it, so nothing else lets go of what the doc was written for.
      const written = new Map(this.#docsWritten);
      written.delete(snapshot.localId);
      this.#docsWritten = written;
      return this.#forget(snapshot.localId);
    });
    return true;
  }

  /** Save a composer's draft to Gmail now (queued while offline), and send it after when asked. */
  save = (
    composer: Composer,
    send: PendingSend | null = null,
  ): Promise<void> => {
    const previous =
      this.#saveChains.get(composer.localId) ?? Promise.resolve();
    const next = previous
      .then(() => this.#saveNow(composer, send))
      .finally(() => {
        if (this.#saveChains.get(composer.localId) === next) {
          this.#saveChains.delete(composer.localId);
        }
      });
    this.#saveChains.set(composer.localId, next);
    return next;
  };

  async #saveNow(composer: Composer, send: PendingSend | null): Promise<void> {
    composer.cancelAutosave();
    // The send under way covers the draft as it is; changing it now could change what goes.
    const entry = this.outboxOf(composer);
    if (isLocked(entry)) return;
    const snapshot = $state.snapshot(composer.snapshot()) as ComposeSnapshot;
    // Thrown away while this save waited its turn.
    if (this.#discarded.has(snapshot.localId)) return;
    if (send === null && isBlank(snapshot) && composer.draftId === null) return;
    void this.#store
      .putComposer(snapshot)
      .catch((error) => console.error("composer write failed", error));
    if (send?.kind === "now" || this.#sending.has(snapshot.localId)) {
      this.#markSending(snapshot);
    }
    if (send !== null && this.#sendSaved(composer, snapshot, send)) return;
    composer.saveStatus = "saving";
    // A scheduled encrypted send with Bcc is several messages; an edit saves them all again.
    const scheduled =
      send === null && entry?.status === "waiting" ? entry : null;
    let messages: { raw: string; copyFor: string | null }[];
    try {
      messages = await this.#outgoing(
        snapshot,
        send !== null || scheduled !== null,
        composer.sendPlain,
      );
    } catch (error) {
      const locked = error instanceof Error && error.message === "locked";
      const problem = buildProblem(error);
      if (!locked && problem === null) {
        console.error("building the message failed", error);
      }
      composer.saveStatus = "failed";
      composer.error = locked
        ? errorText(error)
        : (problem ?? "Couldn't encrypt the message. Try again.");
      if (send !== null) {
        this.pill = null;
        this.#notice(
          locked
            ? "Not sent: encrypted mail is locked on this device."
            : problem !== null
              ? `Not sent. ${problem}`
              : "Couldn't encrypt the message. It wasn't sent.",
          true,
        );
        // Put away on Send; bring it back to fix.
        this.#reopen(composer);
      }
      return;
    }
    const [{ raw = "" } = {}, ...rest] = messages;
    let pending = send;
    if (
      scheduled !== null &&
      (rest.length > 0 ||
        sendGroup(this.outbox.values(), scheduled.id).length > 1)
    ) {
      pending = {
        kind: "at",
        sendAt: scheduled.sendAt,
        chosenAt: scheduled.chosenAt,
        outboxId: scheduled.id,
      };
    }
    // Rewriting a send: a copy whose recipient is still in Bcc keeps its draft and doc.
    const owners =
      pending?.outboxId != null && rest.length > 0
        ? await this.#copyOwners(pending.outboxId)
        : new Map<string, OutboxDoc>();
    const copies: CopyDraft[] = rest.map(({ raw: copyRaw, copyFor }) => {
      const kept = copyFor === null ? undefined : owners.get(copyFor);
      return {
        raw: copyRaw,
        draftId: null,
        ...(copyFor === null ? {} : { to: copyFor }),
        ...(kept === undefined
          ? {}
          : { updates: kept.draftId, outboxId: kept.id }),
      };
    });
    this.#setJob(
      queueSave(this.jobs.get(snapshot.localId), {
        localId: snapshot.localId,
        draftId: snapshot.draftId,
        threadId: snapshot.threadId,
        from: snapshot.from,
        raw,
        send: pending,
        ...(pending !== null ? { copies } : {}),
      }),
    );
    await this.flush();
    if (this.jobs.has(snapshot.localId)) {
      composer.saveStatus = this.#mail.offline ? "queued" : composer.saveStatus;
    }
  }

  #composerFor(localId: string): Composer | null {
    if (this.floating?.localId === localId) return this.floating;
    if (this.inline?.localId === localId) return this.inline;
    if (this.parked?.localId === localId) return this.parked;
    return null;
  }

  /** Send queued draft saves to Gmail in order; stops when the network is down. */
  flush = async (): Promise<void> => {
    if (this.#flushing) return;
    this.#flushing = true;
    try {
      // After dispose no new save starts; one under way still records its Gmail draft.
      while (!this.#disposed) {
        const job = this.jobs.values().next().value as DraftJob | undefined;
        if (!job) break;
        const op = opFor(job);
        let saved: GmailDraft;
        try {
          if (op.kind === "create") {
            saved = await this.#mail.gmail.createDraft(job.raw, job.threadId);
          } else {
            try {
              saved = await this.#mail.gmail.updateDraft(
                op.draftId,
                job.raw,
                job.threadId,
              );
            } catch (error) {
              if (!(error instanceof GmailError && draftGone(error.status))) {
                throw error;
              }
              // Thrown away meanwhile: the draft is meant to be gone.
              if (this.#discarded.has(job.localId)) {
                this.#dropJob(job.localId);
                continue;
              }
              saved = await this.#mail.gmail.createDraft(job.raw, job.threadId);
            }
          }
        } catch (error) {
          // Anything but a Gmail answer (a lost sign-in, a token call that failed) waits too.
          if (isRetryable(error) || !(error instanceof GmailError)) {
            const composer = this.#composerFor(job.localId);
            if (composer) composer.saveStatus = "queued";
            break;
          }
          console.error("draft save refused", error);
          this.#dropJob(job.localId);
          const composer = this.#composerFor(job.localId);
          // A send that can't even be saved isn't sending any more: it is its kept draft again.
          this.#stopSending(job.localId);
          if (composer) {
            composer.saveStatus = "failed";
            composer.error = `Couldn't save the draft. ${errorText(error)}`;
          } else {
            this.#notice(`Couldn't save a draft. ${errorText(error)}`, true);
          }
          continue;
        }
        const carryOn = await this.#saved(
          job,
          op.kind === "update" ? op.draftId : null,
          saved.id,
          saved.message.id,
          saved.message.threadId,
        );
        if (!carryOn) break;
      }
      await this.#runDeletes();
    } finally {
      this.#flushing = false;
    }
  };

  // A copy's draft: the one it had, updated in place, else a new one.
  async #saveCopy(copy: CopyDraft, threadId: string | null): Promise<string> {
    if (copy.updates !== undefined) {
      try {
        return (
          await this.#mail.gmail.updateDraft(copy.updates, copy.raw, threadId)
        ).id;
      } catch (error) {
        if (!(error instanceof GmailError && draftGone(error.status))) {
          throw error;
        }
      }
    }
    return (await this.#mail.gmail.createDraft(copy.raw, threadId)).id;
  }

  // Save an encrypted send's Bcc copies as their own drafts; null when they have to wait (offline,
  // or Gmail's limits) midway.
  async #saveCopies(job: DraftJob): Promise<CopyDraft[] | null> {
    const copies = [...(job.copies ?? [])];
    for (const [index, copy] of copies.entries()) {
      if (!this.#sendStands(job)) return copies;
      if (copy.draftId !== null) continue;
      try {
        const draftId = await this.#saveCopy(copy, job.threadId);
        if (copy.to !== undefined) this.#copyOwnerCache.set(draftId, copy.to);
        copies[index] = { ...copy, draftId };
      } catch (error) {
        if (!isRetryable(error) && error instanceof GmailError) throw error;
        const latest = this.jobs.get(job.localId);
        if (latest !== undefined && this.#sendStands(job)) {
          this.#setJob({ ...latest, draftId: job.draftId, copies });
          return null;
        } else {
          // Undone or changed meanwhile; the caller drops the copies made so far.
          return copies;
        }
      }
    }
    return copies;
  }

  async #saved(
    job: DraftJob,
    previousId: string | null,
    draftId: string,
    messageId: string,
    draftThreadId: string,
  ): Promise<boolean> {
    // Each save gives the draft a new message id; the thread finds Gmail's copy by it.
    this.#patchHeld(job.localId, { messageId, draftThreadId });
    if (this.#discarded.has(job.localId)) {
      this.#dropDiscarded(job, draftId);
      return true;
    }
    const before = this.jobs.get(job.localId) ?? job;
    // The draft was made again under a new id; a schedule on the old one follows it.
    const moved = outboxForDraft(this.outbox.values(), previousId);
    const movedWrite = moved && movedDraftWrite(moved, draftId);
    if (moved && movedWrite && before.send === null) {
      void this.#writeDoc(moved.id, movedWrite).catch((error) =>
        console.error("outbox draft move failed", error),
      );
    }
    // Every composer of it learns Gmail's ids, one put away too, so none makes a second draft.
    for (const composer of this.#composersOf(job.localId)) {
      composer.draftId = draftId;
      composer.messageId = messageId;
      composer.draftThreadId = draftThreadId;
      composer.saveStatus = "saved";
      composer.inGmail =
        !composer.pendingAutosave &&
        this.jobs.get(job.localId)?.version === job.version;
    }
    if (this.previews.has(draftId)) {
      const previews = new Map(this.previews);
      previews.delete(draftId);
      this.previews = previews;
    }
    void this.#store
      .updateComposer(job.localId, (kept) => ({
        ...kept,
        draftId,
        messageId,
        draftThreadId,
      }))
      .catch((error) => console.error("composer write failed", error));
    // Read again after every wait: undo or a newer save may have come meanwhile.
    const current = this.jobs.get(job.localId);
    if (current?.send && current.version === job.version) {
      let copies: CopyDraft[] | null;
      try {
        copies = await this.#saveCopies({ ...current, draftId });
      } catch (error) {
        console.error("copy draft save refused", error);
        copies = [];
        this.#notice(`Couldn't save a Bcc copy. ${errorText(error)}`, true);
      }
      if (copies === null) return false;
      if (this.#discarded.has(job.localId)) {
        this.#dropCopyDrafts(copies);
        this.#dropDiscarded(job, draftId);
        return true;
      } else if (!this.#sendStands(current) || current.send === null) {
        // Undone, or changed, while the copies saved: this send doesn't go, and its copies go away.
        this.#dropCopyDrafts(copies);
      } else {
        await this.#writeOutbox(
          current,
          { draftId, messageId, threadId: draftThreadId },
          current.send,
          copies.flatMap((copy) =>
            copy.draftId === null
              ? []
              : [{ draftId: copy.draftId, outboxId: copy.outboxId ?? null }],
          ),
        );
        await this.#forget(job.localId);
      }
    }
    const after = this.jobs.get(job.localId);
    if (after === undefined) return true;
    const next = afterSave(after, job.version, draftId);
    if (next === null) this.#dropJob(job.localId);
    else this.#setJob(next);
    return true;
  }

  // Whether the send a save carries still holds: not undone, changed or thrown away since.
  #sendStands(job: DraftJob): boolean {
    const latest = this.jobs.get(job.localId);
    return (
      latest?.send != null &&
      latest.send === job.send &&
      latest.version === job.version &&
      !this.#discarded.has(job.localId)
    );
  }

  // A thrown-away composer's save landed anyway: delete what it made in Gmail.
  #dropDiscarded(job: DraftJob, draftId: string): void {
    this.#dropJob(job.localId);
    void this.#queueDelete({
      localId: job.localId,
      draftId,
      messageId: this.localDrafts.get(job.localId)?.messageId ?? null,
      threadId: job.threadId,
      deleted: true,
    });
  }

  // Delete the drafts a send's copies made for it (not those it took over from an earlier send).
  #dropCopyDrafts(copies: readonly CopyDraft[]): void {
    for (const copy of copies) {
      if (copy.draftId === null || copy.draftId === copy.updates) continue;
      void this.#mail.gmail
        .deleteDraft(copy.draftId)
        .catch((error) => console.error("copy draft delete failed", error));
    }
  }

  // Delete a send's Bcc copies (their docs and drafts), all of them or all but `keep`.
  async #dropCopies(
    baseId: string,
    keep: ReadonlySet<string> = new Set(),
  ): Promise<void> {
    for (const entry of sendGroup(this.outbox.values(), baseId)) {
      if (entry.id === baseId || keep.has(entry.id)) continue;
      void deleteDoc(this.#outboxRef(entry.id)).catch((error) =>
        console.error("copy cancel failed", error),
      );
      void this.#mail.gmail
        .deleteDraft(entry.draftId)
        .catch((error) => console.error("copy draft delete failed", error));
    }
  }

  #unwrite(id: string): void {
    this.#wroteSeen.delete(id);
    if (!this.#wrote.has(id)) return;
    const next = new Map(this.#wrote);
    next.delete(id);
    this.#wrote = next;
  }

  #outboxRef(id: string) {
    return doc(this.#db, "users", this.#mail.uid, "outbox", id);
  }

  #writeDoc(id: string, write: OutboxWrite): Promise<void> {
    return setDoc(this.#outboxRef(id), {
      draftId: write.draftId,
      from: write.from,
      status: "waiting",
      sendAt: Timestamp.fromMillis(write.sendAt),
      chosenAt:
        write.chosenAt === null ? null : Timestamp.fromMillis(write.chosenAt),
      threadId: write.threadId,
    });
  }

  async #writeOutbox(
    job: DraftJob,
    draft: { draftId: string; messageId: string | null; threadId: string },
    send: PendingSend,
    copies: readonly { draftId: string; outboxId: string | null }[] = [],
  ): Promise<void> {
    const { draftId } = draft;
    const id = send.outboxId ?? randomId();
    this.#wrote = new Map(this.#wrote).set(id, draft);
    if (send.kind === "now") this.#sendingOutbox.set(id, job.localId);
    this.#docsWritten = new Map(this.#docsWritten).set(job.localId, send);
    // Undo's window runs from the press of Send, not from when the draft reached Gmail.
    const sendAt =
      send.kind === "now" ? send.queuedAt + UNDO_SEND_DELAY_MS : send.sendAt;
    const chosenAt = send.kind === "now" ? null : send.chosenAt;
    const kept = new Set(
      copies.flatMap((copy) => (copy.outboxId === null ? [] : [copy.outboxId])),
    );
    const numbers = nextCopyNumbers(
      this.outbox.keys(),
      id,
      copies.length - kept.size,
    );
    await this.#dropCopies(id, kept);
    let next = 0;
    const docs: [string, string][] = [
      [id, draftId],
      ...copies.map(
        (copy) =>
          [
            copy.outboxId ?? copyOutboxId(id, numbers[next++]!),
            copy.draftId,
          ] as [string, string],
      ),
    ];
    for (const [docId, docDraftId] of docs) {
      const write: OutboxWrite = {
        draftId: docDraftId,
        from: job.from,
        sendAt,
        chosenAt,
        // A new message's draft has a thread of its own; Drafts hides it by that until Gmail lists it.
        threadId:
          docId === id ? (job.threadId ?? draft.threadId) : job.threadId,
      };
      // A doc that already says this (an edit saved in place) stands as it is.
      if (unchangedDoc(this.outbox.get(docId), write)) continue;
      this.#watching.add(docId);
      // Firestore queues the write offline, so this doesn't wait on the network.
      void this.#writeDoc(docId, write).catch((error) => {
        console.error("outbox write failed", error);
        this.#notice("Couldn't send. The message is in Drafts.", true);
      });
    }
    const pill = this.pill;
    if (
      send.kind === "now" &&
      pill?.kind === "undo" &&
      pill.localId === job.localId
    ) {
      this.pill = { ...pill, outboxId: id, expiresAt: sendAt };
    }
    if (send.kind === "now" && docs.length === 1) {
      setTimeout(
        () => void this.#sendDirect(id, draftId, job.from, sendAt),
        Math.max(0, sendAt - Date.now()),
      );
    }
  }

  /**
   * Send a send now from this device when its undo window ends, if it is still here, online and
   * from a Gmail address, skipping the server task's cold start; the server's task stays the
   * backup (`sendDirectly`, `directSendDecision`).
   */
  async #sendDirect(
    outboxId: string,
    draftId: string,
    from: string,
    wroteSendAt: number,
  ): Promise<void> {
    const decide = (doc: DirectSendDoc | null) =>
      directSendDecision({
        // Only while on screen, as on Android: a hidden tab may be frozen between the claim and
        // the handover.
        sendNow:
          !this.#disposed &&
          document.visibilityState === "visible" &&
          this.#sendingOutbox.has(outboxId),
        docs: Math.max(1, sendGroup(this.outbox.values(), outboxId).length),
        online: !this.#mail.offline,
        gmailRoute: isGmailRoute(from, this.sendAs, ADDRESS_DOMAIN),
        wroteSendAt,
        doc,
        now: Date.now(),
      });
    const seen = this.outbox.get(outboxId);
    // The doc's fields are checked on the doc as stored, in the claim.
    const seenDoc =
      seen === undefined
        ? null
        : { status: seen.status, sendAt: seen.sendAt, fields: [] };
    if (decide(seenDoc) !== "send") return;
    const ref = this.#outboxRef(outboxId);
    const result = await sendDirectly(
      {
        readDraft: async (id) => {
          const draft = await this.#mail.gmail.getDraft(id, "metadata");
          return {
            id: draft.message.id,
            headers: draft.message.payload?.headers ?? [],
          };
        },
        claim: (claim) =>
          runTransaction(this.#db, async (transaction) => {
            const data = (await transaction.get(ref)).data();
            const sendAt = data?.sendAt;
            const stored =
              data === undefined || !(sendAt instanceof Timestamp)
                ? null
                : {
                    status: String(data.status),
                    sendAt: sendAt.toMillis(),
                    fields: Object.keys(data),
                  };
            if (data?.draftId !== draftId || decide(stored) !== "send") {
              return false;
            }
            transaction.update(ref, {
              status: "sending",
              claimedAt: serverTimestamp(),
              firstAttemptAt: serverTimestamp(),
              inFlightAt: serverTimestamp(),
              via: "gmail",
              ...claim,
            });
            return true;
          }),
        sendDraft: (id) => this.#mail.gmail.sendDraft(id),
        // The claim id is left as it is, so the rules refuse the write once another claim stands.
        recordSent: (sent, deviceClaim) =>
          updateDoc(ref, {
            deviceClaim,
            status: "sent",
            sentAt: serverTimestamp(),
            messageId: sent.id,
            threadId: sent.threadId,
            filed: true,
            rejected: [],
            inFlightAt: deleteField(),
          }),
        recordRefused: (message, deviceClaim) =>
          updateDoc(ref, {
            deviceClaim,
            status: "failed",
            error: { code: "rejected", message },
            failedAt: serverTimestamp(),
            inFlightAt: deleteField(),
          }),
        newClaimId: randomId,
        now: Date.now,
      },
      { draftId, from },
    );
    if (result === "left") {
      console.warn("a send from this device is left for the server to settle");
    }
  }

  /**
   * Whether a message may go now: when every recipient has a key but this device hasn't unlocked
   * ours, ask to unlock; it goes encrypted once unlocked, unencrypted only when the user chose so.
   */
  async #readyToGo(composer: Composer): Promise<boolean> {
    const encryption = this.#mail.encryption;
    if (
      composer.sendPlain ||
      !encryption.hasKey ||
      encryption.unlocked ||
      !isEncryptingAddress(composer.from)
    ) {
      return true;
    }
    const recipients = [...composer.to, ...composer.cc, ...composer.bcc];
    if (recipients.length === 0) return true;
    const keys = await Promise.all(
      recipients.map((mailbox) =>
        encryption.keys.keyFor(mailbox.email).catch(() => null),
      ),
    );
    if (keys.some((key) => key === null)) return true;
    if (await encryption.unlockForSend()) return true;
    composer.error =
      "Not sent. Unlock encrypted mail, or choose Send without encryption.";
    return false;
  }

  /** Send now with undo: the pill offers it back for `UNDO_MS`. */
  send = async (composer: Composer): Promise<void> => {
    if (isLocked(this.outboxOf(composer))) return;
    if (!(await this.#readyToGo(composer))) return;
    const snapshot = $state.snapshot(composer.snapshot()) as ComposeSnapshot;
    const entry = this.outboxOf(composer);
    if (isLocked(entry)) return;
    this.#putAway(composer);
    this.#markSending(snapshot);
    const queuedAt = Date.now();
    this.pill = {
      kind: "undo",
      localId: snapshot.localId,
      outboxId: null,
      restore: entry && waitingWrite(entry),
      snapshot,
      expiresAt: queuedAt + UNDO_SEND_DELAY_MS,
    };
    await this.save(composer, {
      kind: "now",
      outboxId: entry?.id ?? null,
      queuedAt,
    });
  };

  /** Schedule a composer's message; `chosenAt` is the unshifted suggestion, or null for a typed time. */
  schedule = async (
    composer: Composer,
    sendAt: number,
    chosenAt: number | null,
    source: SendSource,
    keyId: string | null,
    typed: number | null = null,
  ): Promise<void> => {
    if (sendAt <= Date.now() || isLocked(this.outboxOf(composer))) return;
    if (!(await this.#readyToGo(composer)) || sendAt <= Date.now()) return;
    const statsTime = chosenAt ?? typed;
    if (statsTime !== null) {
      this.#stats.commit(Date.now(), statsTime, source, keyId);
    }
    const entry = this.outboxOf(composer);
    this.#putAway(composer);
    this.#notice(`Scheduled for ${formatWakeTime(sendAt, Date.now())}`, false);
    await this.save(composer, {
      kind: "at",
      sendAt,
      chosenAt,
      outboxId: entry?.id ?? null,
    });
  };

  /** Put away a scheduled message after editing it: the draft saves and the schedule stays. */
  keepSchedule = async (composer: Composer): Promise<void> => {
    const edited = composer.pendingAutosave || composer.saveStatus !== "saved";
    if (edited && !(await this.#readyToGo(composer))) return;
    const entry = this.outboxOf(composer);
    await this.close(composer);
    if (entry?.status === "waiting") {
      this.#notice(
        `Still scheduled for ${formatWakeTime(entry.sendAt, Date.now())}`,
        false,
      );
    }
  };

  // Show a composer put away on Send again, where it was, unless another took its place.
  #reopen(composer: Composer): void {
    if (
      this.floating === composer ||
      this.inline === composer ||
      this.parked === composer
    ) {
      return;
    }
    if (composer.mode !== "new" && this.#mail.inOpenThread(composer.threadId)) {
      this.inline ??= composer;
    } else {
      this.floating ??= composer;
    }
  }

  #putAway(composer: Composer): void {
    composer.cancelAutosave();
    if (this.floating === composer) this.floating = null;
    if (this.inline === composer) this.inline = null;
    if (this.parked === composer) this.parked = null;
  }

  /** Take back the last send: stop it before it goes and reopen the message. */
  undoSend = async (): Promise<void> => {
    const pill = this.pill;
    if (pill?.kind !== "undo") return;
    this.pill = null;
    const job = this.jobs.get(pill.localId);
    if (job?.send) {
      // Copies saved for this send before it was held up belong to nothing now.
      const { copies = [], ...rest } = job;
      this.#dropCopyDrafts(copies);
      this.#setJob({ ...rest, send: null });
    }
    if (pill.outboxId !== null) {
      this.#unwrite(pill.outboxId);
      this.#watching.delete(pill.outboxId);
      this.#sendingOutbox.delete(pill.outboxId);
      for (const entry of sendGroup(this.outbox.values(), pill.outboxId)) {
        this.#watching.delete(entry.id);
      }
      await this.#dropCopies(pill.outboxId);
      const restore = pill.restore;
      const undo =
        restore !== null && restore.sendAt > Date.now()
          ? this.#writeDoc(pill.outboxId, restore)
          : deleteDoc(this.#outboxRef(pill.outboxId));
      await undo.catch((error) => console.error("undo send failed", error));
    }
    const draftId = pill.outboxId
      ? (this.outbox.get(pill.outboxId)?.draftId ?? job?.draftId ?? null)
      : (job?.draftId ?? null);
    const snapshot = {
      ...pill.snapshot,
      draftId: draftId ?? pill.snapshot.draftId,
      messageId:
        this.localDrafts.get(pill.localId)?.messageId ??
        pill.snapshot.messageId,
    };
    void this.#store
      .putComposer(snapshot)
      .catch((error) => console.error("composer write failed", error));
    this.#stopSending(pill.localId);
    const composer = this.#composer(snapshot);
    composer.saveStatus = "saved";
    if (snapshot.mode !== "new" && this.#mail.inOpenThread(snapshot.threadId)) {
      this.inline = composer;
    } else {
      if (this.floating) await this.close(this.floating);
      this.floating = composer;
    }
    // An encrypted send with Bcc left its draft without the Bcc list; save it whole again.
    if (snapshot.bcc.length > 0) composer.changed();
  };

  #notice(text: string, failed: boolean): void {
    this.pill = {
      kind: "notice",
      text,
      failed,
      expiresAt: Date.now() + NOTICE_MS,
    };
  }

  #onOutbox(docs: ReadonlyMap<string, OutboxDoc>): void {
    const now = Date.now();
    for (const id of this.#wrote.keys()) {
      const entry = docs.get(id);
      if (entry === undefined ? this.#wroteSeen.has(id) : !isPending(entry)) {
        this.#unwrite(id);
      } else if (entry !== undefined) {
        this.#wroteSeen.add(id);
      }
    }
    for (const composer of [this.floating, this.inline, this.parked]) {
      if (composer === null || composer.draftId === null) continue;
      const draftId = composer.draftId;
      const went = [...docs.values()].some(
        (entry) => entry.draftId === draftId && entry.status === "sent",
      );
      if (went) {
        // Sending used up the draft, so later edits start a new one.
        composer.draftId = null;
        composer.error =
          "This message was sent. Edits now save as a new draft.";
      }
    }
    for (const entry of docs.values()) {
      this.#settleSending(entry);
      if (isCopyId(entry.id) && this.#watching.has(entry.id)) {
        if (entry.status === "failed" || entry.status === "blocked") {
          this.#watching.delete(entry.id);
          this.#notice(
            "Not sent to a Bcc recipient. It's in the Outbox.",
            true,
          );
        } else if (entry.status === "sent") {
          this.#watching.delete(entry.id);
        }
      } else if (this.#watching.has(entry.id)) {
        if (entry.status === "sent") {
          this.#watching.delete(entry.id);
          this.#retryReported.delete(entry.id);
          if (this.#mail.inOpenThread(entry.threadId)) {
            void this.#mail.refresh();
          }
          this.#notice(
            entry.rejected.length > 0
              ? `Sent, but refused for ${entry.rejected.join(", ")}`
              : "Sent",
            entry.rejected.length > 0,
          );
        } else if (entry.status === "failed") {
          this.#watching.delete(entry.id);
          this.#retryReported.delete(entry.id);
          this.#notice("Couldn't send. It's in the Outbox.", true);
        } else if (
          entry.status === "retrying" &&
          !this.#retryReported.has(entry.id)
        ) {
          this.#retryReported.add(entry.id);
          this.#notice("Not sent yet. It's in the Outbox, trying again.", true);
        } else if (entry.status === "blocked") {
          this.#watching.delete(entry.id);
          this.#retryReported.delete(entry.id);
          this.#notice(`Not sent yet. ${blockedText(entry)}.`, true);
        }
      }
      if (
        isFinished(entry) &&
        !this.#watching.has(entry.id) &&
        now - (entry.sentAt ?? 0) > SENT_KEEP_MS
      ) {
        void deleteDoc(this.#outboxRef(entry.id)).catch(() => {});
      }
    }
    for (const entry of outboxDocs(docs.values(), now)) {
      void this.preview(entry.draftId);
    }
  }

  // A send with undo that went stays in its thread until Gmail's copy shows; one that didn't go
  // leaves its thread, which shows Gmail's draft again.
  #settleSending(entry: OutboxDoc): void {
    const localId = this.#sendingOutbox.get(entry.id);
    if (localId === undefined) return;
    if (entry.status === "sent") {
      this.#sendingOutbox.delete(entry.id);
      this.#sentLocal.set(localId, this.#mail.detail?.historyId ?? null);
      this.#settleSent(this.#mail.detail);
    } else if (entry.status === "failed" || entry.status === "blocked") {
      this.#sendingOutbox.delete(entry.id);
      this.#stopSending(localId);
    }
  }

  /** Fetch an outbox draft's recipients, subject and snippet for its row. */
  async preview(draftId: string): Promise<void> {
    if (this.previews.has(draftId) || this.#previewLoading.has(draftId)) return;
    this.#previewLoading.add(draftId);
    try {
      const draft = await this.#mail.gmail.getDraft(draftId, "metadata");
      if (this.#disposed) return;
      const headers = draft.message.payload?.headers;
      const next = new Map(this.previews);
      const text = rowText(
        headerValue(headers, "Subject")?.trim() ?? "",
        draft.message.snippet ?? "",
        undefined,
      );
      next.set(draftId, {
        to: [
          ...parseAddressList(headerValue(headers, "To")),
          ...parseAddressList(headerValue(headers, "Cc")),
        ],
        bcc: parseAddressList(headerValue(headers, "Bcc")),
        subject: text.subject,
        snippet: text.snippet,
      });
      this.previews = next;
    } catch (error) {
      if (!isNetworkError(error)) console.error("draft preview failed", error);
    } finally {
      this.#previewLoading.delete(draftId);
    }
  }

  /** Take a message out of the outbox: it stops being sent and the draft stays in Drafts. */
  cancelSend = async (entry: OutboxDoc): Promise<void> => {
    const baseId = baseOutboxId(entry.id);
    const group = sendGroup(this.outbox.values(), baseId);
    // The rules refuse deleting a doc mid-attempt.
    if (group.some((member) => member.status === "sending")) {
      this.#notice("It's being sent right now, so it can't be canceled.", true);
      return;
    }
    for (const member of group) {
      this.#watching.delete(member.id);
      this.#retryReported.delete(member.id);
    }
    const bcc = await this.#copyRecipients(baseId);
    await this.#dropCopies(baseId);
    this.#unwrite(baseId);
    await deleteDoc(this.#outboxRef(baseId)).catch((error) =>
      console.error("cancel failed", error),
    );
    const main = group.find((member) => member.id === baseId);
    if (main && bcc.length > 0) void this.#restoreBcc(main.draftId, bcc);
    const mayHaveGone =
      entry.status === "sending" ||
      (entry.status === "retrying" && entry.lastError?.code === "network");
    this.#notice(
      mayHaveGone
        ? "Canceled. It may have gone already: check Sent."
        : entry.status === "waiting"
          ? "Schedule canceled. The message is in Drafts."
          : "Canceled. The message is in Drafts.",
      false,
    );
  };

  /** Take a send held on this device out of the outbox before it has a doc: the message stays a draft. */
  cancelHeld = async (held: HeldSend): Promise<void> => {
    const job = this.jobs.get(held.localId);
    if (!job?.send) return;
    const scheduled = job.send.kind === "at";
    const { copies = [], ...rest } = job;
    this.#dropCopyDrafts(copies);
    this.#setJob({ ...rest, send: null });
    this.#stopSending(held.localId);
    this.#notice(
      scheduled
        ? "Schedule canceled. The message is in Drafts."
        : "Canceled. The message is in Drafts.",
      false,
    );
    // An encrypted send with Bcc queued its draft without the Bcc list; it saves whole again.
    if (copies.length > 0) {
      try {
        const kept = await this.#store.composer(held.localId);
        if (kept !== undefined) {
          const [{ raw = "" } = {}] = await this.#outgoing(kept, false);
          const latest = this.jobs.get(held.localId);
          if (latest !== undefined && latest.send === null) {
            this.#setJob(queueSave(latest, { ...rest, raw, send: null }));
          }
        }
      } catch (error) {
        console.error("restoring Bcc failed", error);
      }
    }
    void this.flush();
  };

  /** Send a scheduled, failed or blocked message now, with the usual undo. */
  sendScheduledNow = async (entry: OutboxDoc): Promise<void> => {
    if (entry.status !== "waiting" && !canSendAgain(entry)) return;
    const group = isCopyId(entry.id)
      ? [entry]
      : sendGroup(this.outbox.values(), entry.id);
    const open = [this.floating, this.inline, this.parked].find(
      (composer) => composer !== null && composer.draftId === entry.draftId,
    );
    if (open) {
      await this.send(open);
      return;
    }
    for (const member of group) {
      if (member.status !== "waiting" && !canSendAgain(member)) continue;
      const write = resendWrite(member, Date.now());
      this.#watching.add(member.id);
      await this.#writeDoc(member.id, write).catch((error) => {
        console.error("send now failed", error);
        this.#notice("Couldn't send. Try again.", true);
      });
    }
  };

  // The Bcc recipients of a send's copies, read from their drafts.
  async #copyRecipients(baseId: string): Promise<Mailbox[]> {
    const copies = sendGroup(this.outbox.values(), baseId).filter(
      (entry) => entry.id !== baseId,
    );
    const found = await Promise.all(
      copies.map(async (entry) => {
        try {
          const draft = await this.#mail.gmail.getDraft(
            entry.draftId,
            "metadata",
          );
          const bcc = parseAddressList(
            headerValue(draft.message.payload?.headers, "Bcc"),
          );
          const [first] = bcc;
          if (first !== undefined) {
            this.#copyOwnerCache.set(entry.draftId, first.email);
          }
          return bcc;
        } catch {
          return [];
        }
      }),
    );
    return found.flat();
  }

  // Each copy doc of a send by its recipient (lower-cased), from their drafts' outer Bcc.
  async #copyOwners(baseId: string): Promise<Map<string, OutboxDoc>> {
    const copies = sendGroup(this.outbox.values(), baseId).filter(
      (entry) => entry.id !== baseId,
    );
    if (copies.some((entry) => !this.#copyOwnerCache.has(entry.draftId))) {
      await this.#copyRecipients(baseId);
    }
    const owners = new Map<string, OutboxDoc>();
    for (const entry of copies) {
      const owner = this.#copyOwnerCache.get(entry.draftId);
      if (owner !== undefined && !owners.has(owner)) owners.set(owner, entry);
    }
    return owners;
  }

  // Put a canceled split send's Bcc list back into its draft, saved whole (encrypted to all).
  async #restoreBcc(draftId: string, bcc: Mailbox[]): Promise<void> {
    try {
      const loaded = await this.#loadDraft(draftId);
      if (loaded === null) return;
      const snapshot = { ...loaded, bcc: [...loaded.bcc, ...bcc] };
      const [{ raw = "" } = {}] = await this.#outgoing(snapshot, false);
      this.#setJob(
        queueSave(undefined, {
          localId: randomId(),
          draftId,
          threadId: snapshot.threadId,
          from: snapshot.from,
          raw,
          send: null,
        }),
      );
      await this.flush();
    } catch (error) {
      console.error("restoring Bcc failed", error);
    }
  }

  /**
   * Open an outbox message: a scheduled or failed one to edit (edits save to the same draft while
   * the schedule stays), one under way read-only.
   */
  editScheduled = async (entry: OutboxDoc): Promise<void> => {
    const baseId = baseOutboxId(entry.id);
    const main = this.outbox.get(baseId) ?? entry;
    const bcc = await this.#copyRecipients(baseId);
    await this.openDraft(main.draftId);
    const composer = [this.floating, this.inline].find(
      (open) => open !== null && open.draftId === main.draftId,
    );
    if (composer && bcc.length > 0) {
      composer.bcc = [...composer.bcc, ...bcc];
      composer.inGmail = false;
      composer.showCopies = true;
    }
  };

  /** Forget a failed send whose draft is gone. */
  dismissFailed = async (entry: OutboxDoc): Promise<void> => {
    await deleteDoc(this.#outboxRef(entry.id)).catch(() => {});
  };
}

/** Context accessors for the signed-in ComposeHub. */
export const [getComposeHub, setComposeHub] = createContext<ComposeHub>();
