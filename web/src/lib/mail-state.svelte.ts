import {
  type AccountChange,
  changeRefused,
  changeSent,
  enqueueAccount,
  filterMade,
  filtersWithPending,
  joinAccountQueues,
  type LabelColorInput,
  labelMade,
  labelsWithPending,
  type NewAccountChange,
  newLocalId,
  nextAccountChange,
  relabelChanges,
  sendableChanges,
} from "@inbox/shared/account-queue";
import {
  BUNDLE_FIRST_PAGE,
  BUNDLE_PAGE_SIZE,
  bundleSweepIds,
  bundlesToFetch,
  bundlesToRemember,
  bundlesToTopUp,
  inboxExclusion,
  inboxNextPage,
  inboxThreadIds,
  mergeFirstPage,
  PINNED_LABEL,
  PINNED_PAGE,
  touchedBundles,
} from "@inbox/shared/bundles";
import { categoryChange, keepListed } from "@inbox/shared/categories";
import type {
  FilterEditor,
  GmailFilter,
  GmailFilterAction,
  GmailFilterCriteria,
} from "@inbox/shared/filters";
import { focusQuery, importantChange } from "@inbox/shared/focus";
import {
  type ConnectionEvent,
  FIRST_BACKOFF_MS,
  offlineAfter,
} from "@inbox/shared/gmail-pace";
import {
  type HeldPlaces,
  holdPlaces,
  NO_HELD_PLACES,
} from "@inbox/shared/held-places";
import { type SavedMessages, threadLabels } from "@inbox/shared/history-apply";
import { labelNamed, renames, sortLabels } from "@inbox/shared/label-names";
import { CATEGORY_TONES, labelPrefsFrom } from "@inbox/shared/label-prefs";
import {
  type BundlePaging,
  doneAllIds,
  doneAllTodos,
  focusInboxSections,
  inboxSections,
  layoutKeys,
  layoutSections,
  openPlaces,
  plainSections,
  rowTodo,
  snoozedSections,
} from "@inbox/shared/list-model";
import {
  bundleRowKey,
  emptyListState,
  type ListEvent,
  type ListState as ListModel,
  type ListTarget,
  listStep,
  openBundleTail,
  rowThreadId,
  rowTodoId,
  selectableKeys,
  selectedThreads,
  selectedTodos,
  threadRowKey,
  todoRowKey,
} from "@inbox/shared/list-state";
import { isoToEpoch } from "@inbox/shared/local-time";
import {
  bytesToBase64,
  decodeBase64Url,
  type InlinePart,
} from "@inbox/shared/mime";
import {
  type OfflineDraftsInput,
  type OfflineLocalDraft,
  offlineDraftThreads,
} from "@inbox/shared/offline-drafts";
import {
  applyChange,
  enqueueAll,
  inReplayOrder,
  type LabelChange,
  type NewLabelChange,
  replay,
} from "@inbox/shared/offline-queue";
import {
  draftThreadCount,
  type GmailDraftRef,
  type OutboxDraftDoc,
  onlyInOutbox,
  outboxDraftIds,
  outboxDrafts,
  type SendingDraft,
} from "@inbox/shared/outbox-drafts";
import { unreadAfter, withQueued } from "@inbox/shared/queued-rows";
import {
  searchEmailTodos,
  searchScope,
  searchTodos,
} from "@inbox/shared/search-scope";
import {
  allSelected as everySelected,
  type SelectedThread,
  type SelectedTodo,
  type SetAction,
  selectionActions,
  selectionAfter,
  selectionPlan,
  todoSelectionPlan,
} from "@inbox/shared/selection";
import {
  lastRow as computeLastRow,
  quickTimes as computeQuickTimes,
  type DevicePartition,
  emptyPartition,
  rankDeep,
  type SnoozeSource,
  shownRows,
} from "@inbox/shared/snooze-suggest";
import {
  newerMail,
  type SnoozeDoc,
  type SnoozeStatus,
  snoozeArchivedElsewhere,
  snoozeStatus,
} from "@inbox/shared/thread-snooze";
import {
  activeKey,
  type EmailTodo,
  isSnoozed,
  labelTagNames,
  labelTags,
  labelTodos,
  limitTodosView,
  matchesFilter,
  newestTodosFirst,
  positionAbove,
  type RepeatDraft,
  rowEmailTodo,
  type Todo,
  type TodosViewEmail,
  taggedFilter,
  taggedThreadIds,
  tagLabel,
  todoLabelIds,
  todoLimit,
  todosLoaded,
  todosView,
  todosViewCount,
  todosViewPlace,
  type UnlabeledTag,
  unlabeledTags,
} from "@inbox/shared/todos";
import {
  mergeUndoItems,
  restoresSnooze,
  type UndoKind,
  undoChange,
  undoLabels,
} from "@inbox/shared/undo";
import {
  type FilteredThread,
  inboxAction,
  inboxPlan,
  keepsThread,
  type ViewFilter,
  viewFilter,
} from "@inbox/shared/view-filter";
import {
  inLapseWindow,
  recentlyLapsed,
  scanStart,
  stillFound,
} from "@inbox/shared/wake";
import type { Firestore } from "firebase/firestore";
import { createContext, untrack } from "svelte";
import { SvelteMap } from "svelte/reactivity";
import { ACCOUNT_QUEUE_KEY } from "./account-queue";
import { openDuration } from "./components/open-motion.svelte";
import { errorText } from "./error-text";
import { sameFilter } from "./filters/filter-view";
import { db } from "./firebase";
import { focusMode } from "./focus.svelte";
import {
  type CallPriority,
  GmailClient,
  GmailError,
  isNetworkError,
  isRateLimited,
  isRetryable,
  type ListOptions,
  mapLimited,
  moreUrgent,
  type TokenSource,
} from "./gmail/client";
import { touchesDrafts } from "./gmail/draft-history";
import { isLaterHistory } from "./gmail/history-id";
import { reportingFetch } from "./gmail/request";
import type {
  GmailHistoryRecord,
  GmailLabel,
  GmailLabelInput,
  GmailMessage,
  GmailThread,
  GmailThreadStub,
} from "./gmail/types";
import { deleteDatabase, Idb } from "./idb";
import {
  expandChanges,
  expandSnoozes,
  joinedRows,
  type OwnSnooze,
  openRowLead,
  ThreadJoins,
  withOpenRow,
} from "./joined-threads";
import {
  deleteLabelPrefs,
  type LabelPrefsPatch,
  watchLabelPrefs,
  writeLabelPrefs,
} from "./label-prefs";
import { LABEL_COLORS, labelDot } from "./labels";
import type { Row, SectionEntry, SectionModel } from "./list-model";
import { missingThreadIds } from "./list-status";
import { ListMenus } from "./menu-layer";
import {
  idbOfflineStore,
  OfflineCopy,
  savedListLabels,
  webLock,
} from "./offline-copy";
import {
  afterPaint,
  openFrom,
  openMark,
  openMarkDrawn,
  openStarted,
  openTimed,
} from "./open-timing";
import {
  type AccountStores,
  park,
  parkingSlot,
  RETAGS_KEY,
  type TagRename,
  unpark,
} from "./parked";
import {
  ENCRYPTED_SEARCH_LIMIT,
  ENCRYPTED_SEARCH_PAGE,
  type EncryptedProgress,
  encryptedSearch,
  matchesTerms,
  uncheckedMessages,
} from "./pgp/encrypted-search";
import { EncryptionState } from "./pgp/encryption.svelte";
import { loadPgp } from "./pgp/load";
import {
  binaryString,
  decryptedSubject,
  decryptedView,
  openDetail,
  type PgpStatus,
  sealDetail,
} from "./pgp/message-crypto";
import { forgetCleaned } from "./sanitize";
import { SavedBodies } from "./saved-bodies";
import { searchLocal, searchPlan } from "./search/search-plan";
import {
  deleteSnoozeDoc,
  watchSnoozeDocs,
  writeSnoozeDoc,
} from "./snooze-docs";
import { SnoozeStatsStore, type SnoozeUndo } from "./snooze-stats-store";
import {
  holdsThread,
  joinDetails,
  type MessageView,
  relabelDetail,
  savedSummary,
  type ThreadDetail,
  threadDetail,
  threadPart,
  withAddedMessages,
} from "./thread-detail";
import {
  CATEGORY_NAMES,
  isInInbox,
  isPinned,
  LABEL,
  sentDated,
  summarizeThread,
  type ThreadSummary,
  userLabelsOf,
} from "./thread-summary";
import { TodoState, type TodoUndo } from "./todos/todo-state.svelte";
import { oneClickUnsubscribe } from "./unsubscribe/unsubscribe-call";
import { Unsubscribes } from "./unsubscribe/unsubscribes";
import {
  filteredView,
  listOptions,
  searchLabelName,
  searchOriginAfter,
  type View,
  viewKey,
} from "./views";

/** How long the undo pill stays. */
export const UNDO_MS = 5_000;
/** How long a notice pill (a result, or a change Gmail refused) stays. */
export const NOTICE_MS = 6_000;

/** Which part of the snooze menu shows: the choices, the date and time picker, or a todo's repeat rule. */
export type SnoozeMenuStep = "menu" | "custom" | "repeat";

/** What `snoozeMenuFor` and `labelMenuFor` hold while the selection bar's menu is open. */
export const SELECTION_MENU = "selection";

const FETCH_CONCURRENCY = 8;

// Each thread on a page costs a metadata read, so a page holds about two screens, not more.
const PAGE_SIZE = 25;
const REFRESH_INTERVAL_MS = 60_000;
const MAX_NOW_STALENESS_MS = 60_000;
const MAX_HISTORY_PAGES = 5;
const LABELS_KEY = "labels";
// Most threads an action over a whole search (archive all from a sender, apply a filter) touches.
const MAX_BULK_THREADS = 500;
// Bundle labels whose own list found inbox threads, fetched before the next session's first inbox page.
const BUNDLES_KEY = "bundles";
// Most entries kept on the device per store, the least recently used dropped first.
const SUMMARY_CAP = 5_000;
const LIST_CAP = 100;
// A saved search's list older than this is dropped.
const SEARCH_LIST_MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000;
// A body saved this recently stays though the saved copy doesn't name it yet.
const UNOWNED_BODY_GRACE_MS = 10 * 60 * 1000;
const PRUNE_EVERY_MS = 60 * 60 * 1000;
// Attachments kept in memory for the session, the least recently asked for dropped first.
const ATTACHMENT_CACHE_SIZE = 20;
// Most inline images' data URLs held in memory.
const INLINE_URLS_HELD = 100;

// The list key of a bundle label's own inbox list.
const bundleKey = (labelId: string): string => `bundle:${labelId}`;
// The list key of the inbox's pinned threads.
const PINNED_KEY = "pinned:inbox";
// The list key of the inbox in focus mode, which Gmail's query narrows to pinned threads.
const FOCUS_KEY = "focus:inbox";

/** A list's ids and how far it has loaded. */
export type ListState = {
  ids: string[];
  nextPageToken: string | null;
  loading: boolean;
  loaded: boolean;
  error: string | null;
  /** answered from the device's cache while offline */
  local?: boolean;
  /** the Gmail query `nextPageToken` belongs to */
  query?: string;
  /** why the last page after the first failed to load */
  moreError?: string | null;
  /** whether a later page (a bundle's "Show more") is being fetched, not a refresh of the first */
  loadingMore?: boolean;
};

type UndoItem = {
  id: string;
  // the row it showed in (a joined row's lead)
  row: string;
  labelIds: string[];
  snooze: SnoozeDoc | undefined;
};

/** Where an action was asked from: a row, the open thread, the selection, or a notification. */
export type ActFrom = "row" | "thread" | "selection" | "notification";

/** The list model as the mail lists use it: thread rows draw from a `Row`, todo rows from a `Todo`. */
export type MailList = ListModel<Row, Todo>;

/** The one action undo can take back. */
export type UndoEntry = {
  kind: UndoKind;
  items: UndoItem[];
  // The labels a label change, move or read change touched, which undo puts back.
  labels?: string[];
  // The todos the action changed, as they were.
  todos?: TodoUndo;
  statsUndo?: SnoozeUndo;
  // Whether the action pinned (rather than unpinned), for the pill's text.
  pinned?: boolean;
};

/** An action of the selection bar's that {@link MailState.actOnSelection} applies. */
export type SelectionBarAction =
  | "done"
  | "inbox"
  | "wake"
  | "pin"
  | "unread"
  | "spam"
  | "delete";

const INBOX_FILTER = viewFilter({ kind: "inbox" });
const SNOOZED_FILTER = viewFilter({ kind: "snoozed" });

// A thread as a list's filter sees it.
function filtered(thread: ThreadSummary, status: SnoozeStatus): FilteredThread {
  return { labelIds: thread.labelIds, snoozed: status === "snoozed" };
}

const EMPTY_LIST: ListState = {
  ids: [],
  nextPageToken: null,
  loading: false,
  loaded: false,
  error: null,
};

/** The encrypted-mail part of a search: whether it runs, and the threads it found. */
export type EncryptedSearchState = {
  /** the search's view key */
  key: string;
  /** whether a run is checking messages now */
  running: boolean;
  /** threads matched inside encrypted mail */
  matches: ReadonlySet<string>;
};

function pgpFailure(error: unknown): PgpStatus {
  const code = (error as { code?: string; name?: string }).code;
  if ((error as { name?: string }).name === "PgpError") {
    return code === "wrong-key" ? "wrong-key" : "malformed";
  }
  return isNetworkError(error) ? "failed" : "locked";
}

// Gmail's answer to a label name already taken.
function isDuplicateLabel(error: unknown): boolean {
  return error instanceof GmailError && error.status === 409;
}

// Gmail's answer to a filter that already exists.
function isDuplicateFilter(error: unknown): boolean {
  return (
    error instanceof GmailError &&
    error.status === 400 &&
    /exists/i.test(error.message)
  );
}

/** Everything the signed-in screens share: lists, threads, snoozes, the offline queue and undo. */
export class MailState {
  /** Firebase user id. */
  readonly uid: string;
  /** The Gmail address. */
  readonly email: string;
  /** Gmail REST calls with this account's token. */
  readonly gmail: GmailClient;
  /** Google access tokens, for the other Google APIs. */
  readonly tokens: TokenSource;
  #db: Firestore;
  #cache: Idb;
  #stats: SnoozeStatsStore;
  #disposers: (() => void)[] = [];
  // Set by `dispose`: async work finishing later changes nothing.
  #disposed = false;
  // The open thread's next load after Gmail's usage limit failed it.
  #detailRetry: ReturnType<typeof setTimeout> | undefined;
  #flushing = false;
  // Whether `#start` has read the stored label queue.
  #queueRead = false;
  // Who hears each row that opens, and who waits for an open row's messages to show.
  #openListeners = new Set<(id: string) => void>();
  #detailWaits: {
    id: string;
    resolve: (detail: ThreadDetail | null) => void;
  }[] = [];
  #prunedAt = 0;
  #attachmentCache = new Map<string, Promise<Uint8Array>>();
  // Data URLs of inline images shown this session, by message id and content id, least recently
  // used first: a message drawn again has them in its first frame.
  #inlineUrls = new Map<string, string>();
  // Cached threads opened for offline search, by thread id, so another search doesn't decrypt them
  // again; dropped when mail locks.
  #searchDetails = new Map<string, ThreadDetail>();
  // View keys that loaded past their first page this session, whose later pages a refresh keeps.
  #paged = new Set<string>();
  // Bundle labels whose own list was asked for this session, each with its first fetch.
  #bundleRequests = new Map<string, Promise<boolean>>();
  // Bundle labels whose own list came back this session; only these leave the inbox's own list.
  #bundlesFetched = new Set<string>();
  // Bundle labels the device remembers holding inbox threads, last saved as `BUNDLES_KEY`.
  #bundlesRemembered: ReadonlySet<string> = new Set();
  // Bundle labels to fetch again when the inbox shows (history touched them, or a fetch failed);
  // each stays until a fetch succeeds.
  #staleBundles = new Set<string>();
  // Bundle fetches in flight, by label, and whether each is a next page.
  #bundleLoads = new Map<string, { more: boolean; done: Promise<boolean> }>();
  // Whether the pinned list was fetched since the inbox's last first page.
  #pinnedFresh = false;
  // Threads Gmail answered "not found" for this session, not asked for again.
  #gone = new Set<string>();
  // Label renames the todos' tags still have to follow, oldest first; kept on the device.
  #pendingRetags: TagRename[] = [];
  // The run applying `#pendingRetags`, while one runs.
  #retagRun: Promise<void> | null = null;
  // Why the last thread summary read failed, for the list it left short.
  #summaryFailure: unknown = null;
  // View keys to load again once the load running for them ends, at the most urgent priority asked.
  #reloadAfter = new Map<string, CallPriority>();
  #refreshing: Promise<void> | null = null;
  // A refresh asked for while one ran (and whether forced), run when it ends.
  #refreshQueued: { force: boolean } | null = null;
  #wakeupCheck: Promise<void> | null = null;
  // A check's history scan its page limit cut short, which the next check goes on with.
  #wakeScan: {
    start: string;
    pageToken: string | null;
    records: GmailHistoryRecord[];
  } | null = null;
  #snoozedFetch: Promise<void> | null = null;
  #snoozedFetchAgain = false;
  // Messages decrypted this session, by message id, so refreshes don't decrypt them again.
  #decrypted = new Map<string, MessageView>();
  #decrypting = new Set<string>();
  // Messages that can't be opened (another key, damaged), so refreshes don't fetch them again.
  #unopenable = new Map<string, PgpStatus>();
  /** The account's OpenPGP key, this device's unlocked copy, and recipients' keys. */
  readonly encryption: EncryptionState;
  /** The mail saved on this device for reading offline. */
  readonly savedMail: OfflineCopy;
  /** How many threads are saved for reading offline. */
  offlineCount = $state(0);
  /** Days of mail saved for reading offline besides the inbox; null until the setting is read. */
  offlineDays = $state<number | null>(null);
  /** The account's todos, while the Show todos setting is on. */
  readonly todos: TodoState;
  /** Resolves once unsent work kept from a forced sign-out is back in the cache. */
  readonly unparked: Promise<void>;
  /** The search inside encrypted mail for the current search view. */
  encryptedSearch = $state.raw<EncryptedSearchState | null>(null);
  // Bumped by each encrypted search run and by locking, so only the latest run goes on.
  #encryptedRun = 0;
  // Per search view key, the encrypted messages checked this session and the threads that matched.
  #encryptedProgress = new Map<string, EncryptedProgress>();

  /** The mailbox shown. */
  view = $state.raw<View>({ kind: "inbox" });
  /** Every thread seen, by id. */
  summaries = $state.raw<ReadonlyMap<string, ThreadSummary>>(new Map());
  /** Loaded thread ids per view key. */
  lists = $state.raw<Readonly<Record<string, ListState>>>({});
  /** Snoozes from Firestore, by thread id. */
  snoozeDocs = $state.raw<ReadonlyMap<string, SnoozeDoc>>(new Map());
  /** Snoozed threads a newer message woke. */
  woken = $state.raw<ReadonlySet<string>>(new Set());
  /** Snoozed threads that got a newer message a Gmail filter kept out of the inbox. */
  skippedInbox = $state.raw<ReadonlySet<string>>(new Set());
  /** The account's Gmail labels. */
  labels = $state.raw<GmailLabel[]>([]);
  /** Whether `labels` came from the device or Gmail yet, rather than being empty for want of either. */
  labelsLoaded = $state(false);
  /** The user labels a todo's `#tag` names, by the tag's key (`labelTags`). */
  readonly labelTags = $derived(labelTags(this.labels));
  /**
   * The tags of the todos a label could bundle (of their own, not done) that name no label yet, each
   * with the label that would (Settings › Labels); none until the labels have loaded.
   */
  readonly unlabeledTags: UnlabeledTag[] = $derived.by(() =>
    this.todos.enabled && this.labelsLoaded
      ? unlabeledTags(
          this.todos.todos
            .filter((todo) => todo.state !== "DONE")
            .map((todo) => todo.text),
          this.labels,
        )
      : [],
  );
  /** The user labels as tags, offered as a todo's `#tag` is typed. */
  readonly labelTagNames = $derived(labelTagNames(this.labelTags));

  /**
   * The color of the label a todo's `#tag` (its name without `#`) names, as CSS; null when it names
   * none. A new function each time the labels change, so what holds it can tell.
   */
  readonly tagColor = $derived.by(() => {
    const tags = this.labelTags;
    return (name: string): string | null => {
      const label = tagLabel(tags, name);
      return label === null ? null : labelDot(label);
    };
  });
  // Threads with the DRAFT label, as Gmail counts them.
  #draftLabelThreads = $state(0);
  // Gmail's drafts as last listed, while the Outbox holds any; and which outbox drafts that was for.
  #draftRefs = $state.raw<readonly GmailDraftRef[]>([]);
  #draftRefsFor: string | null = null;
  // Counts listings of Gmail's drafts, to tell the latest.
  #draftRefsCall = 0;
  // The outbox drafts the last listing that came back was made for.
  #draftRefsListedFor = $state.raw<ReadonlySet<string>>(new Set());
  // The drafts this device is sending, as the compose hub gives them.
  #sendingDrafts = $state.raw<() => readonly SendingDraft[]>(() => []);
  // The outbox docs, as the compose hub last saw them.
  #outboxDocs = $state.raw<readonly OutboxDraftDoc[]>([]);
  // The drafts this device holds, as the compose hub gives them.
  #localDrafts: () => readonly OfflineLocalDraft[] = () => [];
  // Whether Drafts was last listed from the saved copy (filled, or Gmail couldn't be reached).
  #draftsFromSaved = false;
  /** The Gmail drafts of scheduled sends, which show only in the Outbox (see `outboxDrafts`). */
  readonly outboxDrafts = $derived(
    outboxDrafts(
      this.#draftRefs,
      this.#outboxDocs,
      this.#sendingDrafts(),
      this.#draftRefsListedFor,
    ),
  );
  /** Threads in Drafts, those only the Outbox holds left out. */
  readonly draftCount = $derived(
    draftThreadCount(this.#draftLabelThreads, this.outboxDrafts),
  );
  /** The clock lists are computed against. */
  now = $state(Date.now());
  /** Whether Gmail was last unreachable. */
  offline = $state(false);
  /** Label changes not yet sent to Gmail. */
  queue = $state.raw<LabelChange[]>([]);
  /** Label and filter changes not yet sent to Gmail. */
  accountQueue = $state.raw<AccountChange[]>([]);
  /** What became of a queued change Gmail refused, while it shows. */
  notice = $state.raw<{ text: string; expiresAt: number } | null>(null);
  /** The account's Gmail filters as last fetched, or null before they come back. */
  fetchedFilters = $state.raw<GmailFilter[] | null>(null);
  /** The filters as shown: as fetched, with queued new ones, deletes and replacements applied. */
  readonly filters = $derived(
    this.fetchedFilters === null && this.accountQueue.length === 0
      ? null
      : filtersWithPending(this.fetchedFilters ?? [], this.accountQueue),
  );
  // Gmail's ids for local ones made this session, or null for one Gmail refused, so a change made
  // from a screen still holding the local id names the right label.
  #madeIds = new Map<string, string | null>();
  #noticeTimer: ReturnType<typeof setTimeout> | undefined;

  /** How the current list is used: its rows, focus, the open thread and bundle, selection and undo; changed only by {@link dispatch}. */
  list = $state.raw<MailList>(emptyListState());
  /** Where menus and dropdowns report opening, closing and presses away, as list events. */
  readonly menus = new ListMenus(
    (event) => this.dispatch(event),
    () => this.list.menu,
  );
  /** The focused row's key (see `threadRowKey`, `todoRowKey`, `bundleRowKey`). */
  readonly focusKey = $derived(this.list.focus);
  /** The thread open in place. */
  readonly openId = $derived(this.list.open);
  /** Whether the open thread took the place of the one before it (a sideways swipe), nothing growing or shrinking. */
  openedInPlace = $state(false);
  // Set while `open` swaps the open thread in place.
  #openingInPlace = false;
  /** The label of the open bundle. */
  readonly openBundle = $derived(this.list.bundle?.labelId ?? null);
  // The focused thread, whose pinning moves it only once focus leaves.
  readonly #focusedThread = $derived(rowThreadId(this.focusKey));
  readonly #focusedTodo = $derived(rowTodoId(this.focusKey));
  /** The open thread's messages, once loaded. */
  detail = $state.raw<ThreadDetail | null>(null);
  /** Why the open thread couldn't load. */
  detailError = $state<string | null>(null);
  /** Where unsubscribing from each message stands this session. */
  readonly unsubscribes = new Unsubscribes(
    new SvelteMap(),
    oneClickUnsubscribe,
  );
  // Places the open thread and bundles keep until they close and their close has played, and the
  // focused thread's pinning.
  #held = $state.raw<HeldPlaces>(NO_HELD_PLACES);
  // The view the held places belong to.
  #heldView = "";
  // The held places whose close is playing, and the timer that lets them go when it ends.
  #closing = "";
  #releaseTimer: ReturnType<typeof setTimeout> | undefined;
  /** The thread whose snooze menu is open. */
  snoozeMenuFor = $state<string | null>(null);
  /** Which step of the snooze menu shows. */
  snoozeMenuStep = $state<SnoozeMenuStep>("menu");
  /** The thread whose label menu is open. */
  labelMenuFor = $state<string | null>(null);
  /**
   * The filter open in Settings › Filters' editor: a new one (id null) or the Gmail filter `id`,
   * and which opening it is, so each opening starts the editor fresh; null while none is.
   */
  filterEditing = $state.raw<{
    id: string | null;
    editor: FilterEditor;
    opening: number;
  } | null>(null);

  /** The action undo would take back. */
  lastUndo = $state.raw<UndoEntry | null>(null);
  /** When the undo offer ends. */
  undoExpiresAt = $state<number | null>(null);

  #partition = $state.raw<DevicePartition>(emptyPartition("shared"));
  #deepRows = $derived(rankDeep([this.#partition], this.now));
  /** The suggestion rows of the snooze menu, in time order. */
  readonly snoozeRows = $derived(shownRows(this.#deepRows));
  /** The last custom time, when it isn't already a row. */
  readonly snoozeLastRow = $derived(
    computeLastRow([this.#partition], this.now, this.snoozeRows),
  );
  /** The picker's learned clock times. */
  readonly snoozeQuickTimes = $derived(
    computeQuickTimes([this.#partition], this.now),
  );

  /** Stored label settings, `users/{uid}/labelPrefs`, by label id. */
  labelPrefDocs = $state.raw(new Map<string, unknown>());

  /** User labels and Gmail's categories, by name: chips, label views and the label settings. */
  readonly userLabels = $derived(
    sortLabels(
      this.labels.flatMap((label) => {
        const category = CATEGORY_NAMES[label.id];
        if (category !== undefined) {
          const tone = CATEGORY_TONES[label.id];
          const color = LABEL_COLORS.find((entry) => entry.tone === tone);
          return [
            {
              ...label,
              name: category,
              color: color && {
                backgroundColor: color.backgroundColor,
                textColor: color.textColor,
              },
            },
          ];
        }
        return label.type === "user" ? [label] : [];
      }),
    ),
  );
  /** Each label's settings, defaults filled in. */
  readonly labelPrefs = $derived(
    new Map(
      this.userLabels.map((label) => [
        label.id,
        labelPrefsFrom(this.labelPrefDocs.get(label.id), label),
      ]),
    ),
  );
  /** The labels the sidebar lists. */
  readonly sidebarLabels = $derived(
    this.userLabels.filter((label) => this.labelPrefs.get(label.id)?.sidebar),
  );
  readonly #userLabelIds = $derived(this.userLabels.map((label) => label.id));
  readonly #bundleLabelIds = $derived(
    this.userLabels
      .filter((label) => this.labelPrefs.get(label.id)?.bundle)
      .map((label) => label.id),
  );
  // Bundle labels Gmail is known to have no more of: their own list loaded, with no next page.
  readonly #exhaustedBundles = $derived(
    new Set(
      this.#bundleLabelIds.filter((labelId) => {
        const list = this.lists[bundleKey(labelId)];
        return !!list?.loaded && !list.loading && !list.nextPageToken;
      }),
    ),
  );
  readonly #userLabelById = $derived(
    new Map(this.userLabels.map((label) => [label.id, label])),
  );

  /** Active todos as the lists place them. */
  readonly activeTodos: Todo[];

  /** Todos still snoozed. */
  readonly snoozedTodos: Todo[];

  // The list todo pages were counted in, and how many of its pages show.
  #todoPages = $state({ list: "", pages: 1 });
  readonly #todoList = $derived(viewKey(this.view));

  /** The most todos the current list shows; {@link showMoreTodos} shows more. */
  readonly todoLimit = $derived(
    this.#todoPages.list === this.#todoList
      ? todoLimit(this.#todoPages.pages)
      : todoLimit(1),
  );

  // The emails with a todo this device has, as rows, for the Todos view.
  readonly #emailTodoRows = $derived.by((): TodosViewEmail<Row>[] => {
    if (!this.todos.enabled || this.view.kind !== "todos") return [];
    const byThread = this.todos.byThread;
    const rows = joinedRows(this.#loaded(byThread.keys()), (id) =>
      this.#ownSnooze(id),
    );
    const held = this.#held.threads;
    return rows.flatMap((row) => {
      const todo = rowTodo(byThread, row);
      const place = held.get(row.thread.id);
      return todo === null
        ? []
        : [
            {
              email: row,
              todo,
              inbox: isInInbox(row.thread),
              snoozed: row.status === "snoozed",
              pinned: isPinned(row.thread),
              sortDate: row.sortDate,
              held:
                place === undefined
                  ? null
                  : { date: place.date, pinned: place.pinned },
            },
          ];
    });
  });

  // The open email's place in the Todos view, to hold until it closes.
  #todosViewOpenPlaces(open: HeldPlaces): HeldPlaces {
    const openId = this.openId;
    if (openId === null || this.view.kind !== "todos") return open;
    const email = this.#emailTodoRows.find(
      (entry) => entry.email.thread.id === openId,
    );
    const place = email === undefined ? null : todosViewPlace(email);
    if (place === null) return open;
    const threads = new Map(open.threads);
    threads.set(openId, { ...place, single: true });
    return { ...open, threads };
  }

  /** The Todos view's rows under their headings: active todos and the todos of active emails. */
  readonly todosViewBuckets = $derived.by(() =>
    todosView(this.todos.placed, this.#emailTodoRows, this.now),
  );

  /** The Todos view's headings and rows as far as {@link todoLimit} reaches. */
  readonly shownTodosView = $derived(
    limitTodosView(this.todosViewBuckets, this.todoLimit),
  );

  /** The todos a search finds, in its list's order; none when it asks for mail only. */
  readonly #searchFound = $derived.by((): Todo[] => {
    const view = this.view;
    if (view.kind !== "search" || !this.todos.enabled) return [];
    const found = searchTodos(
      this.todos.placed,
      view.query,
      this.now,
      this.labelTags,
    );
    return searchPlan(view.query).snoozedOnly ? found : newestTodosFirst(found);
  });

  /** How many todos the current search finds, shown or not: its own todos and its emails' listed. */
  get searchTodoCount(): number {
    return (
      this.#searchFound.length +
      this.#listedRows.filter((row) => this.todoOfRow(row) !== null).length
    );
  }

  /** The todo of a row's email, or null (always while todos are off). */
  todoOfRow = (row: Row): EmailTodo | null =>
    this.todos.enabled ? rowTodo(this.todos.byThread, row) : null;

  // The search's todos that may show among the threads loaded so far, before the todo limit.
  readonly #searchLoaded = $derived.by((): Todo[] => {
    const view = this.view;
    if (view.kind !== "search") return [];
    const plan = searchPlan(view.query);
    if (plan.todosOnly || plan.snoozedOnly) return this.#searchFound;
    const oldest = this.#listedRows.reduce(
      (least, row) => Math.min(least, row.sortDate),
      Number.POSITIVE_INFINITY,
    );
    return todosLoaded(this.#searchFound, oldest, this.#moreThreads(view));
  });

  // Whether `view` may have threads still to load: its first page, or a next one.
  #moreThreads(view: View): boolean {
    const list = this.lists[viewKey(view)];
    return !list?.loaded || !!list.nextPageToken;
  }

  /** How many todos the current list leaves out until {@link showMoreTodos}. */
  readonly todosHidden = $derived.by(() => {
    if (this.view.kind === "todos") {
      return Math.max(
        0,
        todosViewCount(this.todosViewBuckets) - this.todoLimit,
      );
    } else {
      return Math.max(0, this.#searchLoaded.length - this.todoLimit);
    }
  });

  /** Show another page of the current list's todos. */
  showMoreTodos = (): void => {
    const list = this.#todoList;
    const pages = this.#todoPages.list === list ? this.#todoPages.pages : 1;
    this.#todoPages = { list, pages: pages + 1 };
  };

  // The threads the inbox has loaded: Gmail's INBOX, its pinned list and each bundle's own list, plus threads whose snooze ended.
  readonly #inboxIds = $derived.by((): ReadonlySet<string> => {
    const bundles = this.#bundleLabelIds.map((labelId) => {
      const list = this.lists[bundleKey(labelId)];
      return { labelId, ids: list?.ids ?? [], more: !!list?.nextPageToken };
    });
    bundles.push({
      labelId: PINNED_LABEL,
      ids: this.lists[PINNED_KEY]?.ids ?? [],
      more: false,
    });
    const ids = new Set(
      inboxThreadIds(
        this.lists[viewKey({ kind: "inbox" })]?.ids ?? [],
        bundles,
        (id) => this.summaries.get(id)?.labelIds,
      ),
    );
    if (focusMode.on) {
      for (const id of this.lists[FOCUS_KEY]?.ids ?? []) ids.add(id);
    }
    for (const [id, doc] of this.snoozeDocs) {
      if (recentlyLapsed(doc.snoozeUntil, this.now)) ids.add(id);
    }
    return ids;
  });

  /** Rows of the inbox: the threads it loaded, minus those still snoozed. */
  readonly inboxRows = $derived(
    joinedRows(this.#loaded(this.#inboxIds), (id) =>
      this.#ownSnooze(id),
    ).filter((row) =>
      keepsThread(INBOX_FILTER, filtered(row.thread, row.status)),
    ),
  );

  /**
   * Rows of Snoozed: every thread still snoozed. The inbox's threads join them, so a reply Gmail
   * split off after a snooze ends it here too.
   */
  readonly snoozedRows = $derived(
    joinedRows(
      this.#loaded(new Set([...this.snoozeDocs.keys(), ...this.#inboxIds])),
      (id) => this.#ownSnooze(id),
    ).filter((row) =>
      keepsThread(SNOOZED_FILTER, filtered(row.thread, row.status)),
    ),
  );

  // The summaries of `ids` this device has, less threads only the Outbox shows.
  #loaded(ids: Iterable<string>): ThreadSummary[] {
    return [...ids].flatMap((id) => {
      const thread = this.summaries.get(id);
      return thread && !onlyInOutbox(id, thread.messageCount, this.outboxDrafts)
        ? [thread]
        : [];
    });
  }

  // The current view's filter: which of its listed threads it keeps (see `viewFilter`).
  readonly #viewFilter = $derived.by((): ViewFilter | null => {
    const view = filteredView(this.view);
    return view === null ? null : viewFilter(view, this.#labelIdOf);
  });

  // A user label's id by the name a search's `label:` term gives it.
  #labelIdOf = (name: string): string | null => {
    const wanted = searchLabelName(name);
    return (
      this.labels.find(
        (label) =>
          label.type === "user" && searchLabelName(label.name) === wanted,
      )?.id ?? null
    );
  };

  // Rows of a list other than the inbox and Snoozed: the threads Gmail listed that its filter keeps.
  readonly #listedRows = $derived.by((): Row[] => {
    const view = this.view;
    const filter = this.#viewFilter;
    if (filter === null || view.kind === "inbox" || view.kind === "snoozed") {
      return [];
    }
    const dated = view.kind === "sent" ? sentDated : null;
    // Scheduled sends show only in the Outbox.
    const outboxOnly =
      view.kind === "drafts" ? this.outboxDrafts.threadIds : null;
    const listed = (this.lists[viewKey(view)]?.ids ?? []).filter(
      (id) => !outboxOnly?.has(id),
    );
    // An email's todo puts it in a label's list by its tags, though Gmail doesn't give it the
    // label, and in a search by its text.
    const tagged = new Set(taggedThreadIds(this.#todoThreadIds, listed));
    const threads = this.#loaded([...listed, ...tagged]);
    const relaxed = taggedFilter(filter, this.labelTags);
    return joinedRows(dated === null ? threads : threads.map(dated), (id) =>
      this.#ownSnooze(id),
    ).filter((row) =>
      keepsThread(
        (row.members ?? [row.thread.id]).some((id) => tagged.has(id))
          ? relaxed
          : filter,
        filtered(row.thread, row.status),
      ),
    );
  });

  // The emails the current list shows for their todo's text: a label's, by the todo's tags, or a
  // search's (`searchEmailTodos`), whose own state the list's filter then holds to its terms.
  readonly #todoThreadIds = $derived.by((): string[] => {
    const view = this.view;
    if (!this.todos.enabled) {
      return [];
    } else if (view.kind === "label") {
      return labelTodos(
        this.todos.emailTodos,
        view.labelId,
        this.labelTags,
      ).map((todo) => todo.threadId);
    } else if (view.kind === "todos") {
      return [...this.todos.byThread.keys()];
    } else if (view.kind === "search") {
      // `is:snoozed` alone lists every snoozed thread from the device, their todos inside.
      const plan = searchPlan(view.query);
      if (plan.snoozedOnly && plan.gmailQuery === "") return [];
      return searchEmailTodos(
        this.todos.emailTodos,
        view.query,
        this.labelTags,
      );
    } else {
      return [];
    }
  });

  /** The current view's sections before focus mode. */
  readonly #allSections = $derived(this.#sectionsFor(this.openId));

  // The current list's joined rows, which actions on a row apply to each Gmail thread of.
  // The Gmail threads the open row stood for when opened, lead first.
  #openMembers = $state.raw<readonly string[]>([]);
  // Saved threads' bodies, the latest shown or read ahead held ready (see `SavedBodies`).
  readonly #savedBodies: SavedBodies;

  readonly #joins = $derived(
    new ThreadJoins(
      this.#allSections.flatMap((section) =>
        section.entries.flatMap((entry) =>
          entry.kind === "thread"
            ? [entry.thread]
            : entry.kind === "group"
              ? entry.threads
              : [],
        ),
      ),
    ),
  );

  // The current view's sections before focus mode, with `openId` as the open thread.
  #sectionsFor(openId: string | null): SectionModel[] {
    const view = this.view;
    if (view.kind === "inbox") {
      return inboxSections(
        this.#withOpen(this.inboxRows, openId),
        this.now,
        this.#bundleLabelIds,
        this.#held,
        this.activeTodos,
        this.#exhaustedBundles,
        (todo) => todoLabelIds(todo.text, this.labelTags),
        (row) => {
          const todo = this.todoOfRow(row);
          return todo === null ? [] : todoLabelIds(todo.text, this.labelTags);
        },
      );
    } else if (view.kind === "snoozed") {
      return snoozedSections(
        this.#withOpen(this.snoozedRows, openId),
        this.now,
        this.snoozedTodos,
      );
    } else if (view.kind === "todos") {
      return this.shownTodosView.map((bucket) => ({
        key: `todos:${bucket.label}`,
        title: bucket.label,
        canDoneAll: false,
        entries: bucket.entries.map(
          (entry): SectionEntry =>
            entry.kind === "todo"
              ? entry
              : { kind: "thread", thread: entry.email },
        ),
      }));
    } else if (view.kind === "done") {
      const rows = this.#listedRows;
      const oldest = rows.reduce(
        (least, row) => Math.min(least, row.sortDate),
        Number.POSITIVE_INFINITY,
      );
      return plainSections(
        this.#withOpen(rows, openId),
        this.now,
        this.#held,
        todosLoaded(
          this.todos.placed.filter((todo) => todo.state === "DONE"),
          oldest,
          this.#moreThreads(view),
        ),
      );
    } else if (view.kind === "search") {
      const plan = searchPlan(view.query);
      const todos = this.#searchLoaded.slice(0, this.todoLimit);
      if (plan.todosOnly) {
        const rows = this.#withOpen(this.#listedRows, openId);
        return plan.snoozedOnly
          ? snoozedSections(rows, this.now, todos)
          : plainSections(rows, this.now, this.#held, todos);
      } else if (plan.snoozedOnly) {
        const rows =
          plan.gmailQuery === "" ? this.snoozedRows : this.#listedRows;
        return snoozedSections(this.#withOpen(rows, openId), this.now, todos);
      } else {
        return plainSections(
          this.#withOpen(this.#listedRows, openId),
          this.now,
          this.#held,
          todos,
        );
      }
    } else if (this.#viewFilter === null) {
      return [];
    } else if (view.kind === "label") {
      const rows = this.#listedRows;
      const oldest = rows.reduce(
        (least, row) => Math.min(least, row.sortDate),
        Number.POSITIVE_INFINITY,
      );
      return plainSections(
        this.#withOpen(rows, openId),
        this.now,
        this.#held,
        todosLoaded(
          labelTodos(this.todos.placed, view.labelId, this.labelTags),
          oldest,
          this.#moreThreads(view),
        ),
      );
    } else {
      const dated = view.kind === "sent" ? sentDated : undefined;
      return plainSections(
        this.#withOpen(this.#listedRows, openId, dated),
        this.now,
        this.#held,
      );
    }
  }

  // Focus mode applies to the inbox only; other lists are ones the user went looking in.
  readonly #focused = $derived(
    this.#focus(this.#allSections, this.view, this.openId),
  );

  #focus(
    sections: SectionModel[],
    view: View,
    openId: string | null,
  ): { sections: SectionModel[]; hidden: number } {
    return focusMode.on && view.kind === "inbox"
      ? focusInboxSections(sections, openId, this.#held)
      : { sections, hidden: 0 };
  }

  /** The current view's sections. */
  readonly sections = $derived(this.#focused.sections);

  /** Rows loaded for this view that focus mode hides. */
  readonly focusHidden = $derived(this.#focused.hidden);

  /** The current view's sections as the list model takes them (see {@link dispatch}). */
  readonly layout = $derived(
    layoutSections(this.sections, (labelId) => this.#bundlePaging(labelId)),
  );

  /** The selected rows' keys, in the order picked. */
  readonly selected = $derived(this.list.selection);

  /** The selected threads, in the order picked. */
  readonly selectedThreadIds = $derived(selectedThreads(this.list));

  /** The selected todos' ids, in the order picked. */
  readonly selectedTodoIds = $derived(selectedTodos(this.list));

  /** Whether every row shown that can be selected is. */
  readonly allSelected = $derived(
    everySelected(this.list.selection, selectableKeys(this.list)),
  );

  /** The current view's list state. */
  readonly currentList = $derived(
    listOptions(this.view) === null
      ? { ...EMPTY_LIST, loaded: true }
      : (this.lists[this.#listKey(this.view)] ?? EMPTY_LIST),
  );

  // The key of the list a view loads: in focus mode, the inbox's pinned threads alone.
  #listKey(view: View): string {
    return view.kind === "inbox" && focusMode.on ? FOCUS_KEY : viewKey(view);
  }

  /** Threads and todos still snoozed, for the sidebar. */
  readonly snoozedCount: number;

  /** Inbox threads per user label, for the sidebar. */
  readonly labelCounts = $derived.by(() => {
    const counts = new Map<string, number>();
    for (const row of this.inboxRows) {
      for (const label of row.thread.labelIds) {
        counts.set(label, (counts.get(label) ?? 0) + 1);
      }
    }
    return counts;
  });

  constructor(uid: string, email: string, tokens: TokenSource) {
    this.uid = uid;
    this.email = email;
    this.tokens = tokens;
    // `offline` follows only what the calls say: each Gmail call, and the token call before it.
    this.gmail = new GmailClient(
      {
        get: () =>
          tokens.get().catch((error: unknown) => {
            if (isNetworkError(error)) {
              this.noteConnection({ kind: "unanswered" });
            }
            throw error;
          }),
        invalidate: () => tokens.invalidate(),
      },
      reportingFetch(fetch, (event) => this.noteConnection(event)),
    );
    this.#db = db();
    this.#cache = new Idb(`inbox:${uid}`, {
      onBlocked: () =>
        this.#showNotice("Close Inbox in your other tabs to finish updating."),
      // Another tab updated the app or signed out; this one starts over to match.
      onClosedElsewhere: () => location.reload(),
    });
    this.unparked = unpark(uid, this.#accountStores(), parkingSlot()).then(
      () => {},
      (error) => console.error("restoring unsent work failed", error),
    );
    this.encryption = new EncryptionState(uid, this.#cache);
    this.savedMail = new OfflineCopy(
      this.gmail,
      idbOfflineStore(this.#cache),
      {
        snoozed: () => this.#snoozedNow(),
        changedHere: () => this.#locallyChanged(),
        save: (threads, changedBefore) =>
          this.#saveRead(threads, changedBefore),
        append: (threadId, messages, labels) =>
          this.#appendSaved(threadId, messages, labels),
        relabel: (labels) => this.#relabelSaved(labels),
        remove: (ids) => this.#removeGone(ids),
        // Lists built from Gmail's pages until now come from the saved copy.
        rebuilt: () => void this.refresh(true),
        caughtUp: () => void this.refresh(true),
        changed: () => {
          this.offlineCount = this.savedMail.count;
          this.offlineDays = this.savedMail.days;
        },
        reloaded: (ids) => void this.#showSavedElsewhere(ids),
      },
      Date.now,
      webLock(`inbox-offline:${uid}`),
    );
    this.#disposers.push(() => this.savedMail.dispose());
    this.#savedBodies = new SavedBodies({
      saved: (id) => this.savedMail.has(id),
      ready: () => this.savedMail.ready,
      read: async (ids) => {
        const reading = performance.now();
        const stored = await this.#cache.getMany("threads", ids);
        // A prefetch of another row isn't part of the open being timed.
        if (ids.some((id) => this.inOpenThread(id))) {
          openMark(
            `stored bodies read, ${ids.length} threads`,
            performance.now() - reading,
          );
        }
        const deviceKey = this.encryption.deviceKey();
        return Promise.all(
          stored.map((body) =>
            body === undefined
              ? undefined
              : openDetail(body, deviceKey).catch(() => body),
          ),
        );
      },
    });
    this.todos = new TodoState(this.#db, uid);
    this.todos.onClearedOut = (id) => this.deleteTodos([id]);
    this.activeTodos = $derived(
      this.todos.placed.filter((todo) =>
        matchesFilter(todo, "ACTIVE", this.now),
      ),
    );
    this.snoozedTodos = $derived(
      this.todos.placed.filter((todo) =>
        matchesFilter(todo, "SNOOZED", this.now),
      ),
    );
    this.snoozedCount = $derived(
      this.snoozedRows.length + this.snoozedTodos.length,
    );
    this.#disposers.push(() => this.todos.dispose());
    this.#stats = new SnoozeStatsStore(this.#db, uid, (partition) => {
      this.#partition = partition;
    });
    this.#disposers.push(() => this.#stats.dispose());
    this.#disposers.push(
      watchLabelPrefs(this.#db, uid, (docs) => {
        this.labelPrefDocs = docs;
      }),
    );
    this.#disposers.push(
      watchSnoozeDocs(this.#db, uid, (docs) => {
        this.snoozeDocs = docs;
        void this.#fetchSnoozedSummaries();
      }),
    );

    // Bodies held from before showed what that lock state could open.
    $effect(() => {
      void this.encryption.generation;
      untrack(() => {
        this.#savedBodies.clear();
        forgetCleaned();
      });
    });

    // A device that just unlocked opens the encrypted messages of the open thread.
    $effect(() => {
      void this.encryption.generation;
      const id = this.openId;
      if (id !== null && this.encryption.unlocked) {
        untrack(() => void this.#decryptOpen(id));
      }
    });

    // A bundle label on a loaded inbox thread gets its own list; focus mode shows no bundles.
    $effect(() => {
      if (this.view.kind !== "inbox" || focusMode.on) return;
      if (this.#dueBundles().length === 0) return;
      untrack(() => void this.#fetchDueBundles());
    });

    // A label no longer bundled comes back into the inbox's own list.
    $effect(() => {
      const bundled = new Set(this.#bundleLabelIds);
      untrack(() => {
        const dropped = [...this.#bundleRequests.keys()].filter(
          (labelId) => !bundled.has(labelId),
        );
        if (dropped.length === 0) return;
        for (const labelId of dropped) {
          this.#bundleRequests.delete(labelId);
          this.#bundlesFetched.delete(labelId);
          this.#staleBundles.delete(labelId);
        }
        this.#rememberBundles();
        // A load already running asked with the label left out, so this one waits for it.
        void this.loadView({ kind: "inbox" });
      });
    });

    // Switching focus mode lists the inbox again, its own list or only its pinned threads.
    this.#disposers.push(
      focusMode.onChange(() => {
        if (this.view.kind !== "inbox") return;
        const view = this.view;
        void this.#showCached(this.#listKey(view)).then(() =>
          this.loadView(view),
        );
      }),
    );

    // A bundle whose loaded threads ran out or short, or whose end shows, fetches its next page.
    $effect(() => {
      if (this.view.kind !== "inbox" || focusMode.on) return;
      const shown = new Map<string, number>();
      for (const row of this.inboxRows) {
        for (const labelId of row.thread.labelIds) {
          shown.set(labelId, (shown.get(labelId) ?? 0) + 1);
        }
      }
      const open = new Set(this.openBundle === null ? [] : [this.openBundle]);
      const tail = openBundleTail(this.list);
      const wanted = bundlesToTopUp(
        this.#bundleLabelIds.map((labelId) => {
          const list = this.lists[bundleKey(labelId)];
          return {
            labelId,
            shown: shown.get(labelId) ?? 0,
            more: !!list?.nextPageToken,
            loading: !!list?.loading,
            failed: !!list?.moreError,
            open: open.has(labelId),
            endShown: this.bundleEndsShown.has(labelId),
            afterOpen: tail?.labelId === labelId ? tail.after : null,
          };
        }),
      );
      untrack(() => {
        for (const labelId of wanted) {
          void this.#loadBundle(
            labelId,
            true,
            open.has(labelId) ? "visible" : "background",
          );
        }
      });
    });

    // Acting on an open thread or bundle doesn't move it until it closes; pinning the focused
    // thread doesn't move it until focus leaves it.
    $effect(() => {
      const open = this.#todosViewOpenPlaces(
        openPlaces(
          this.sections,
          this.openBundle,
          this.openId,
          this.#focusedThread,
          this.#focusedTodo,
        ),
      );
      const view = viewKey(this.view);
      untrack(() => this.#hold(open, view));
    });
    this.#disposers.push(() => clearTimeout(this.#releaseTimer));
    this.#disposers.push(() => clearTimeout(this.#detailRetry));

    // Locking mail puts away everything decrypted this session, the open thread's messages too.
    $effect(() => {
      if (!this.encryption.unlocked)
        untrack(() => void this.#forgetDecrypted());
    });

    // Nothing is written when a snooze ends, so the clock has to advance itself.
    $effect(() => {
      let soonest = Number.POSITIVE_INFINITY;
      const todoTimes = this.todos.todos.flatMap((todo) =>
        todo.state === "DONE" || todo.snoozeUntil === null
          ? []
          : [isoToEpoch(todo.snoozeUntil)],
      );
      for (const until of [
        ...[...this.snoozeDocs.values()].map((doc) => doc.snoozeUntil),
        ...todoTimes,
      ]) {
        if (until !== null && until > this.now && until < soonest) {
          soonest = until;
        }
      }
      const wakeAt = Math.min(soonest, Date.now() + MAX_NOW_STALENESS_MS);
      const timer = setTimeout(
        this.refreshNow,
        Math.max(100, wakeAt - Date.now()),
      );
      return () => clearTimeout(timer);
    });

    $effect(() => {
      const expiresAt = this.undoExpiresAt;
      if (expiresAt === null) return;
      const timer = setTimeout(
        () => this.#clearUndo(),
        Math.max(0, expiresAt - Date.now()),
      );
      return () => clearTimeout(timer);
    });

    $effect(() => {
      // The clock effect above already keeps `now` current while the page runs.
      const poll = () => {
        if (document.visibilityState !== "visible") return;
        this.#stats.reattach();
        void this.flush();
        void this.refresh(false);
        void this.#runRetags();
        if (Date.now() - this.#prunedAt > PRUNE_EVERY_MS) void this.#prune();
      };
      const wake = () => {
        if (document.visibilityState !== "visible") return;
        this.refreshNow();
        poll();
      };
      const goOnline = () => {
        this.noteConnection({ kind: "networkUp" });
        wake();
      };
      const goOffline = () => {
        this.noteConnection({ kind: "networkDown" });
      };
      // Typing not yet saved is saved as the page goes away or out of sight; the edit stays open.
      const saveTyping = (event: Event) => {
        if (event.type === "pagehide" || document.visibilityState === "hidden")
          this.todos.flush();
      };
      const timer = setInterval(poll, REFRESH_INTERVAL_MS);
      document.addEventListener("visibilitychange", wake);
      document.addEventListener("visibilitychange", saveTyping);
      window.addEventListener("pagehide", saveTyping);
      // A page restored from the back-forward cache sends no visibility change.
      const restored = (event: PageTransitionEvent) => {
        if (event.persisted) wake();
      };
      window.addEventListener("online", goOnline);
      window.addEventListener("pageshow", restored);
      window.addEventListener("offline", goOffline);
      return () => {
        clearInterval(timer);
        document.removeEventListener("visibilitychange", wake);
        document.removeEventListener("visibilitychange", saveTyping);
        window.removeEventListener("pagehide", saveTyping);
        window.removeEventListener("online", goOnline);
        window.removeEventListener("pageshow", restored);
        window.removeEventListener("offline", goOffline);
      };
    });

    void this.#start();
  }

  async #start(): Promise<void> {
    // Saved mail the browser may otherwise clear when space runs low.
    void navigator.storage?.persist?.().catch(() => false);
    await this.unparked;
    if (this.#disposed) return;
    try {
      const [labels, queued, bundles, accountQueued, retags] =
        await Promise.all([
          this.#cache.get("meta", LABELS_KEY),
          this.#cache.entries("queue"),
          this.#cache.get("meta", BUNDLES_KEY),
          this.#cache.get("meta", ACCOUNT_QUEUE_KEY),
          this.#cache.get("meta", RETAGS_KEY),
        ]);
      if (this.#disposed) return;
      if (Array.isArray(retags) && retags.length > 0) {
        this.#setPendingRetags([
          ...(retags as TagRename[]),
          ...this.#pendingRetags,
        ]);
        void this.#runRetags();
      }
      // Changes made while the device was being read go after the ones it kept.
      if (Array.isArray(accountQueued)) {
        const early = this.accountQueue;
        this.accountQueue = joinAccountQueues(
          accountQueued as AccountChange[],
          early,
        );
        if (early.length > 0) this.#setAccountQueue(this.accountQueue);
      }
      if (Array.isArray(labels)) {
        this.labels = labelsWithPending(
          labels as GmailLabel[],
          this.accountQueue,
        );
        this.labelsLoaded = true;
      }
      if (Array.isArray(bundles)) {
        this.#bundlesRemembered = new Set(bundles as string[]);
      }
      // Changes made while the device was being read were only kept in memory: number them after
      // the stored ones and store them now.
      const early = this.queue;
      this.queue = enqueueAll(
        inReplayOrder(queued.map(([, change]) => change)),
        early.map(({ id: _id, seq: _seq, ...change }) => change),
      );
      this.#queueRead = true;
      this.#storeQueue(this.queue.slice(queued.length));
    } catch (error) {
      console.error("cache read failed", error);
      this.#queueRead = true;
    }
    await Promise.all([
      this.#showCached(viewKey(this.view)),
      this.#showCached(this.#listKey(this.view)),
      this.#showCached(PINNED_KEY),
    ]);
    if (this.#disposed) return;
    void this.flush();
    await this.refresh();
    void this.#prune();
  }

  // Keep the device's copies of lists, rows and opened threads from growing without end.
  // Another tab syncing the saved copy prunes too; this one tries again at the next prune.
  async #prune(): Promise<void> {
    this.#prunedAt = Date.now();
    try {
      await this.savedMail.whileHeld(async () => {
        const now = Date.now();
        await this.#cache.prune(
          "lists",
          LIST_CAP,
          (key, usedAt) =>
            key.startsWith("search:") && now - usedAt > SEARCH_LIST_MAX_AGE_MS,
        );
        const saved = new Set(this.savedMail.savedIds([]));
        await this.#cache.prune("summaries", SUMMARY_CAP, undefined, saved);
        // Bodies the saved copy doesn't name. One saved lately may be waiting for its record.
        await this.#cache.dropUnowned(
          "threads",
          "offline",
          now - UNOWNED_BODY_GRACE_MS,
        );
      });
    } catch (error) {
      console.error("cache prune failed", error);
    }
  }

  /** This account's on-device cache. */
  get cache(): Idb {
    return this.#cache;
  }

  /** Take what `event` says of the connection: each Gmail call's outcome, and the network coming or going. */
  noteConnection(event: ConnectionEvent): void {
    this.offline = offlineAfter(this.offline, event);
  }

  /** Stop listeners and timers. */
  dispose(): void {
    this.#disposed = true;
    for (const dispose of this.#disposers) dispose();
    this.#disposers = [];
    this.#openListeners.clear();
    this.#settleDetailWaits();
    this.encryption.dispose();
    forgetCleaned();
    void this.#cache.close();
  }

  /** Drop this account's cache from the device, as on sign-out. */
  async clearDevice(): Promise<void> {
    this.dispose();
    await deleteDatabase(`inbox:${this.uid}`);
  }

  /**
   * Drop this account's cache from the device as on a sign-out forced by lost Gmail access, but
   * first keep its unsent work (`parked.ts`) for when it signs in again.
   */
  async parkAndClear(): Promise<void> {
    await park(this.uid, this.#accountStores(), parkingSlot()).catch((error) =>
      console.error("keeping unsent work failed", error),
    );
    await this.clearDevice();
  }

  #accountStores(): AccountStores {
    const cache = this.#cache;
    return {
      entries: (name) => cache.entries(name),
      get: (name, key) => cache.get(name, key),
      put: (name, key, value) => cache.put(name, key, value as never),
    };
  }

  /** Move the clock lists are computed against to now. */
  refreshNow = (): void => {
    this.now = Date.now();
  };

  /** A thread's user labels, in sidebar order. */
  labelsOf(thread: ThreadSummary): GmailLabel[] {
    return userLabelsOf(thread, this.#userLabelIds).map(
      (id) => this.#userLabelById.get(id)!,
    );
  }

  /** The snooze status of a row right now: over all its Gmail threads when it is a joined row. */
  statusOf(id: string): SnoozeStatus {
    return this.#joins.joinedRow(id)?.status ?? this.#ownSnooze(id).status;
  }

  // A Gmail thread's own snooze, whatever row it shows in.
  #ownSnooze(id: string): OwnSnooze {
    const doc = this.snoozeDocs.get(id);
    return { doc, status: snoozeStatus(doc, this.now, this.woken.has(id)) };
  }

  /** The Gmail threads row `id` of the current list stands for, lead first. */
  membersOf(id: string): readonly string[] {
    return this.#joins.membersOf(id);
  }

  // A row's summary: a joined row's combined one, else the thread's own.
  #threadOf(id: string): ThreadSummary | undefined {
    return this.#joins.joinedRow(id)?.thread ?? this.summaries.get(id);
  }

  /** The id of the row of the current list Gmail thread `id` shows in: a joined row's lead, else its own. */
  rowOf(id: string): string {
    return this.#joins.rowOf(id);
  }

  /** Whether Gmail thread `threadId` is the open thread or one shown with it. */
  inOpenThread(threadId: string | null): boolean {
    const open = this.openId;
    if (threadId === null || open === null) return false;
    const detail = this.detail;
    return (
      this.rowOf(threadId) === open ||
      (detail?.id === open && holdsThread(detail, threadId))
    );
  }

  /** A row's decrypted subject when this device has one: its lead's, else any of its other threads'. */
  decryptedSubjectOf(id: string): string | undefined {
    const subjects = this.encryption.subjects;
    return this.#joins
      .membersOf(id)
      .map((member) => subjects.get(member))
      .find((subject) => subject !== undefined);
  }

  // A place no longer open is let go once its close has played, at once in another view. A close
  // while another plays starts the wait over, so neither is let go before its close ends.
  #hold(open: HeldPlaces, view: string): void {
    const duration = openDuration();
    const release = view !== this.#heldView || duration === 0;
    this.#heldView = view;
    const next = holdPlaces(this.#held, open, release);
    if (next !== this.#held) this.#held = next;
    const closing = [
      ...[...next.threads.keys()].filter((id) => !open.threads.has(id)),
      ...[...next.bundles.keys()]
        .filter((id) => !open.bundles.has(id))
        .map((id) => `bundle:${id}`),
    ].join(" ");
    if (closing === "") {
      // Forgotten, so closing the same places again gets its own full wait.
      this.#closing = "";
      clearTimeout(this.#releaseTimer);
      this.#releaseTimer = undefined;
      return;
    }
    if (closing === this.#closing) return;
    this.#closing = closing;
    clearTimeout(this.#releaseTimer);
    this.#releaseTimer = setTimeout(() => {
      this.#releaseTimer = undefined;
      this.#closing = "";
      this.#held = holdPlaces(
        this.#held,
        this.#todosViewOpenPlaces(
          openPlaces(
            this.sections,
            this.openBundle,
            this.openId,
            this.#focusedThread,
            this.#focusedTodo,
          ),
        ),
      );
    }, duration);
  }

  // The open thread shows in place, so its row stays until it closes, though it left the list
  // (archived elsewhere, a label taken off) or was never in it (opened from a notification).
  // A joined row stays joined while open, though its lead left the list.
  #withOpen(
    rows: readonly Row[],
    id: string | null,
    dated: (thread: ThreadSummary) => ThreadSummary = (thread) => thread,
  ): readonly Row[] {
    if (id === null) return rows;
    const open = this.#openMembers[0] === id ? this.#openMembers : [id];
    return withOpenRow(
      rows,
      open,
      (member) => {
        const thread = this.summaries.get(member);
        return thread && dated(thread);
      },
      (member) => this.#ownSnooze(member),
    );
  }

  #setList(key: string, patch: Partial<ListState>): void {
    if (this.#disposed) return;
    this.lists = {
      ...this.lists,
      [key]: { ...(this.lists[key] ?? EMPTY_LIST), ...patch },
    };
  }

  #putSummaries(threads: readonly ThreadSummary[]): void {
    if (this.#disposed || threads.length === 0) return;
    const next = new Map(this.summaries);
    for (const thread of threads) next.set(thread.id, thread);
    this.summaries = next;
    void this.#cache
      .putMany(
        "summaries",
        threads.map((thread) => [thread.id, thread]),
      )
      .catch((error) => console.error("cache write failed", error));
  }

  // Rows the device's cache has, of `ids`; none when it can't be read.
  async #readCachedSummaries(ids: readonly string[]): Promise<ThreadSummary[]> {
    if (ids.length === 0) return [];
    const cached = await this.#cache
      .getMany("summaries", ids)
      .catch(() => [] as (ThreadSummary | undefined)[]);
    return cached.filter(
      (summary): summary is ThreadSummary => summary !== undefined,
    );
  }

  // Show the rows of `ids` not in memory yet from the device's cache.
  async #showCachedSummaries(ids: readonly string[]): Promise<void> {
    const found = await this.#readCachedSummaries(
      ids.filter((id) => !this.summaries.has(id)),
    );
    if (this.#disposed || found.length === 0) return;
    const next = new Map(this.summaries);
    for (const summary of found) {
      if (!next.has(summary.id)) next.set(summary.id, summary);
    }
    this.summaries = next;
  }

  async #showCached(key: string): Promise<void> {
    if (this.lists[key]?.loaded) return;
    try {
      const list = await this.#cache.get("lists", key);
      const ids = list?.ids ?? [];
      await this.#showCachedSummaries(ids);
      if (list && !this.lists[key]?.loaded) {
        this.#setList(key, {
          ids: list.ids,
          nextPageToken: list.nextPageToken,
          loaded: true,
        });
      }
      if (list) {
        void Promise.all([
          this.#cache.touch("lists", [key]),
          this.#cache.touch("summaries", ids),
        ]).catch(() => {});
      }
    } catch (error) {
      console.error("cache read failed", error);
    }
  }

  /**
   * Fetch summaries for threads whose history changed or that aren't known yet; resolves whether
   * every read came back. A thread Gmail no longer has is remembered in `#gone`, not a failure.
   */
  async #fetchSummaries(
    stubs: readonly { id: string; historyId?: string }[],
    priority: CallPriority = "background",
  ): Promise<boolean> {
    const changedBefore = this.#locallyChanged();
    const stale = stubs.filter((stub) => {
      const known = this.summaries.get(stub.id);
      return !known || known.historyId !== stub.historyId;
    });
    const results = await mapLimited(stale, FETCH_CONCURRENCY, (stub) =>
      this.gmail.getThread(stub.id, "metadata", priority),
    );
    if (this.#disposed) return false;
    const fetched: ThreadSummary[] = [];
    const fresh: ThreadSummary[] = [];
    const missing: string[] = [];
    let complete = true;
    results.forEach((result, index) => {
      if (result.status === "fulfilled") {
        const summary = summarizeThread(result.value);
        fresh.push(summary);
        fetched.push(this.#withQueued(summary));
      } else if (
        result.reason instanceof GmailError &&
        result.reason.status === 404
      ) {
        missing.push(stale[index]!.id);
      } else {
        complete = false;
        this.#summaryFailure = result.reason;
      }
    });
    this.#putSummaries(fetched);
    if (missing.length > 0) this.#removeGone(missing);
    this.#dropSnoozesArchivedElsewhere(fresh, changedBefore);
    return complete;
  }

  // Gmail doesn't know about queued changes yet, so a fresh copy would undo them on screen.
  #withQueued(summary: ThreadSummary): ThreadSummary {
    return withQueued(summary, this.queue);
  }

  // Threads #fetchTaggedSummaries is reading, so a todo edited meanwhile doesn't read them again.
  #taggedReading = new Set<string>();

  /** Whether emails with a todo that this device hasn't got are being read (the Todos view shows Loading meanwhile). */
  readingTodoEmails = $state(false);

  /** Read the emails with a todo the Todos view lists that this device hasn't got. */
  loadTodoEmails = (): void => {
    if (this.view.kind === "todos") void this.#fetchTaggedSummaries();
  };

  // Read the threads the current list adds for their todos that this device hasn't got: from the
  // cache, else Gmail.
  async #fetchTaggedSummaries(): Promise<void> {
    const ids = taggedThreadIds(this.#todoThreadIds, []).filter(
      (id) =>
        !this.summaries.has(id) &&
        !this.#gone.has(id) &&
        !this.#taggedReading.has(id),
    );
    if (ids.length === 0) return;
    for (const id of ids) this.#taggedReading.add(id);
    this.readingTodoEmails = true;
    try {
      await this.#showCachedSummaries(ids);
      if (this.#disposed) return;
      const missing = ids.filter((id) => !this.summaries.has(id));
      await this.#fetchSummaries(missing.map((id) => ({ id })));
    } catch {
      // Tried again at the next refresh.
    } finally {
      for (const id of ids) this.#taggedReading.delete(id);
      this.readingTodoEmails = this.#taggedReading.size > 0;
    }
  }

  // Threads whose snooze is still ahead, which the saved copy keeps with the inbox.
  #snoozedNow(): ReadonlySet<string> {
    const now = Date.now();
    const ids = new Set<string>();
    for (const [id, doc] of this.snoozeDocs) {
      if (doc.snoozeUntil !== null && doc.snoozeUntil > now) ids.add(id);
    }
    return ids;
  }

  // Rows and bodies of threads the saved copy read whole. Rows only the saved copy needs go to the
  // device's cache, not the screen's, which keeps the inbox's and those already loaded.
  async #saveRead(
    threads: readonly GmailThread[],
    changed: ReadonlySet<string>,
  ): Promise<void> {
    if (this.#disposed) return;
    const fresh = threads.map(summarizeThread);
    const rows = fresh.map((summary) => this.#withQueued(summary));
    const shown = rows.filter(
      (summary) =>
        this.summaries.has(summary.id) ||
        summary.labelIds.includes(LABEL.inbox),
    );
    this.#putSummaries(shown);
    const stored = rows.filter((summary) => !shown.includes(summary));
    await this.#cache
      .putMany(
        "summaries",
        stored.map((summary) => [summary.id, summary]),
      )
      .catch((error) => console.error("cache write failed", error));
    this.#dropSnoozesArchivedElsewhere(fresh, changed);
    await Promise.all(
      threads.map((thread) => this.#keepDetail(threadDetail(thread))),
    );
    if (threads.some((thread) => this.inOpenThread(thread.id))) {
      await this.#reshowSaved(true);
    }
  }

  // A saved thread's new messages, read alone, added to its saved body and row; false when its
  // body isn't saved.
  async #appendSaved(
    threadId: string,
    messages: readonly GmailMessage[],
    labels: SavedMessages,
  ): Promise<boolean> {
    if (this.#disposed) return true;
    const changedBefore = this.#locallyChanged();
    let stored: ThreadDetail | undefined;
    try {
      stored = await this.#cache.get("threads", threadId);
    } catch (error) {
      console.error("thread cache read failed", error);
      return false;
    }
    if (stored === undefined) return false;
    const newest = (start: string) =>
      messages.reduce(
        (best, message) =>
          message.historyId !== undefined &&
          isLaterHistory(message.historyId, best)
            ? message.historyId
            : best,
        start,
      );
    const body = {
      ...withAddedMessages(threadPart(stored, threadId), messages),
      historyId: newest(stored.historyId),
    };
    try {
      await this.#cache.put("threads", threadId, body);
    } catch (error) {
      console.error("thread cache write failed", error);
      return false;
    }
    this.#savedBodies.forget([threadId]);
    const base =
      this.summaries.get(threadId) ??
      (await this.#cache.get("summaries", threadId).catch(() => undefined));
    if (this.#disposed) return true;
    const fresh = savedSummary(
      body,
      labels,
      base,
      newest(base?.historyId ?? "0"),
    );
    const row = this.#withQueued(fresh);
    if (this.summaries.has(threadId) || row.labelIds.includes(LABEL.inbox)) {
      this.#putSummaries([row]);
    } else {
      await this.#cache
        .put("summaries", threadId, row)
        .catch((error) => console.error("cache write failed", error));
    }
    this.#dropSnoozesArchivedElsewhere([fresh], changedBefore);
    void this.#noteAutocrypt(
      { id: threadId, messages: [...messages] },
      new Set(),
    );
    if (this.inOpenThread(threadId)) await this.#reshowSaved(true);
    return true;
  }

  // Saved threads' rows and bodies with the labels history gave their messages.
  async #relabelSaved(
    messages: ReadonlyMap<string, SavedMessages>,
  ): Promise<void> {
    const labels = new Map(
      [...messages].map(([id, own]) => [id, threadLabels(own)] as const),
    );
    await this.#relabelSavedBodies(messages);
    const ids = [...labels.keys()];
    const cached = await this.#readCachedSummaries(
      ids.filter((id) => !this.summaries.has(id)),
    );
    if (this.#disposed) return;
    const stored = new Map(cached.map((summary) => [summary.id, summary]));
    const rows: ThreadSummary[] = [];
    const snoozedOut: string[] = [];
    for (const [id, own] of labels) {
      const summary = this.summaries.get(id) ?? stored.get(id);
      if (summary === undefined) continue;
      rows.push(
        this.#withQueued({
          ...summary,
          labelIds: own,
          unread: own.includes(LABEL.unread),
        }),
      );
      if (this.snoozeDocs.has(id) && !own.includes(LABEL.inbox)) {
        snoozedOut.push(id);
      }
    }
    this.#putSummaries(rows);
    // A snoozed thread archived elsewhere is Done; its fresh row tells (see `snoozeArchivedElsewhere`).
    if (snoozedOut.length > 0) {
      void this.#fetchSummaries(snoozedOut.map((id) => ({ id })));
    }
  }

  // Saved bodies' per-message flags, under the thread's own id and its joined row's.
  async #relabelSavedBodies(
    messages: ReadonlyMap<string, SavedMessages>,
  ): Promise<void> {
    const keys = [
      ...new Set([...messages.keys()].flatMap((id) => [id, this.rowOf(id)])),
    ];
    try {
      const stored = await this.#cache.getMany("threads", keys);
      const changed: [string, ThreadDetail][] = [];
      stored.forEach((detail, position) => {
        if (detail === undefined) return;
        let next = detail;
        for (const [threadId, own] of messages) {
          next = relabelDetail(next, threadId, own);
        }
        if (next !== detail) changed.push([keys[position]!, next]);
      });
      await this.#cache.putMany("threads", changed);
      for (const [threadId, own] of messages) {
        this.#savedBodies.update(threadId, (body) =>
          relabelDetail(body, threadId, own),
        );
      }
      let open = this.detail;
      for (const [threadId, own] of open === null ? [] : messages) {
        open = relabelDetail(open!, threadId, own);
      }
      if (open !== this.detail) this.detail = open;
    } catch (error) {
      console.error("thread cache write failed", error);
    }
  }

  // Rows another tab's sync changed, from the device's cache, and the inbox's lists from the saved
  // copy as it now stands.
  async #showSavedElsewhere(ids: readonly string[]): Promise<void> {
    const shown = ids.filter((id) => this.summaries.has(id));
    const stored = await this.#readCachedSummaries(shown);
    if (this.#disposed) return;
    this.#savedBodies.forget(ids);
    if (ids.some((id) => this.inOpenThread(id))) void this.#reshowSaved(false);
    const next = new Map(this.summaries);
    for (const summary of stored) {
      next.set(summary.id, this.#withQueued(summary));
    }
    this.summaries = next;
    if (
      this.savedMail.filled &&
      (this.view.kind === "inbox" || this.view.kind === "drafts")
    ) {
      void this.loadView(this.view, false, "background");
    }
  }

  // Threads Gmail no longer has leave the screen and the device.
  #removeGone(ids: readonly string[]): void {
    if (this.#disposed) return;
    for (const id of ids) this.#gone.add(id);
    this.#savedBodies.forget(ids);
    const next = new Map(this.summaries);
    for (const id of ids) next.delete(id);
    this.summaries = next;
    void this.#cache
      .deleteMany("summaries", ids)
      .catch((error) => console.error("cache write failed", error));
  }

  // Fetch the summaries snoozes need; one fetch at a time, a call meanwhile runs once after it.
  #fetchSnoozedSummaries(): Promise<void> {
    if (this.#snoozedFetch !== null) {
      this.#snoozedFetchAgain = true;
      return this.#snoozedFetch;
    }
    this.#snoozedFetch = (async () => {
      try {
        do {
          this.#snoozedFetchAgain = false;
          await this.#fetchSnoozedSummariesOnce();
        } while (this.#snoozedFetchAgain && !this.#disposed);
      } finally {
        this.#snoozedFetch = null;
      }
    })();
    return this.#snoozedFetch;
  }

  async #fetchSnoozedSummariesOnce(): Promise<void> {
    const ids = [...this.snoozeDocs]
      .filter(
        ([id, doc]) =>
          !this.summaries.has(id) &&
          !this.#gone.has(id) &&
          inLapseWindow(doc.snoozeUntil, this.now),
      )
      .map(([id]) => id);
    if (ids.length === 0) return;
    await this.#showCachedSummaries(ids);
    if (this.#disposed) return;
    const missing = ids.filter((id) => !this.summaries.has(id));
    try {
      await this.#fetchSummaries(missing.map((id) => ({ id })));
    } catch {
      // Tried again at the next refresh.
    }
    if (this.#disposed) return;
    // A snooze on a thread Gmail no longer has (deleted for good) can never wake; clear it.
    for (const id of missing) {
      if (this.#gone.has(id) && this.snoozeDocs.has(id)) {
        void deleteSnoozeDoc(this.#db, this.uid, id).catch((error) =>
          console.error("snooze clear failed", error),
        );
      }
    }
  }

  // Check for newer mail on snoozed threads; a call while one runs shares it.
  #checkWakeups(): Promise<void> {
    this.#wakeupCheck ??= this.#checkWakeupsOnce().finally(() => {
      this.#wakeupCheck = null;
    });
    return this.#wakeupCheck;
  }

  // Snoozes the check for newer mail watches, by thread: watermarks of those not long ended.
  #wakeWatermarks(): Map<string, string> {
    const watermarks = new Map<string, string>();
    for (const [id, doc] of this.snoozeDocs) {
      if (
        doc.snoozeWatermark !== null &&
        inLapseWindow(doc.snoozeUntil, this.now)
      ) {
        watermarks.set(id, doc.snoozeWatermark);
      }
    }
    return watermarks;
  }

  // Reads history a few pages per check; a scan longer than that goes on at the next check, and
  // what it found is used only once it reached the end.
  async #checkWakeupsOnce(): Promise<void> {
    const watermarks = this.#wakeWatermarks();
    const from = scanStart(watermarks, this.#wakeScan?.start ?? null);
    if (from === null) {
      this.#wakeScan = null;
      return;
    }
    const scan =
      from.resume && this.#wakeScan !== null
        ? this.#wakeScan
        : { start: from.start, pageToken: null, records: [] };
    this.#wakeScan = scan;
    try {
      for (let page = 0; page < MAX_HISTORY_PAGES; page++) {
        const result = await this.gmail.listHistory(
          scan.start,
          scan.pageToken ?? undefined,
        );
        scan.records.push(...(result.history ?? []));
        scan.pageToken = result.nextPageToken ?? null;
        if (scan.pageToken === null) break;
      }
      if (scan.pageToken !== null || this.#disposed) return;
      this.#wakeScan = null;
      const { inInbox, skippedInbox } = newerMail(scan.records, watermarks);
      const current = this.#wakeWatermarks();
      this.woken = stillFound(inInbox, watermarks, current);
      this.skippedInbox = stillFound(skippedInbox, watermarks, current);
    } catch {
      // Nothing learned: offline, or (404) the watermark is older than Gmail keeps history.
      this.#wakeScan = null;
    }
  }

  /** Fetch the labels again, as after one was made or changed. */
  reloadLabels = (): Promise<void> => this.#loadLabels();

  // The labels and Drafts' count; Gmail's drafts are listed again only when `draftsChanged` (or
  // the last listing is for other outbox drafts, or failed).
  // Bumped by each `#loadLabels`, so only the latest one's answer is shown.
  #labelsCall = 0;

  async #loadLabels(draftsChanged = false): Promise<void> {
    if (this.#disposed) return;
    const draftRefs =
      draftsChanged || this.#outboxKey() !== this.#draftRefsFor
        ? this.#loadDraftRefs()
        : Promise.resolve();
    const call = ++this.#labelsCall;
    try {
      const [labels, drafts] = await Promise.all([
        this.gmail.listLabels(),
        this.gmail.getLabel(LABEL.draft),
        draftRefs,
      ]);
      // An earlier call answering last would put back a list from before a label was made.
      if (this.#disposed || call !== this.#labelsCall) return;
      this.labels = labelsWithPending(labels, this.accountQueue);
      this.labelsLoaded = true;
      this.#draftLabelThreads = drafts.threadsTotal ?? 0;
      void this.#cache
        .put("meta", LABELS_KEY, labels)
        .catch((error) => console.warn("label cache write failed", error));
    } catch {
      // Tried again at the next refresh.
    }
  }

  /** Take the outbox docs; Gmail's drafts are listed again when the drafts they hold changed. */
  setOutboxDocs = (docs: readonly OutboxDraftDoc[]): void => {
    this.#outboxDocs = docs;
    if (this.#outboxKey() !== this.#draftRefsFor) void this.#loadDraftRefs();
    const known = this.#sendingDrafts().flatMap((send) =>
      send.draftId === null || send.messageId === null
        ? []
        : [
            {
              draftId: send.draftId,
              messageId: send.messageId,
              threadId: send.threadId ?? "",
            },
          ],
    );
    void this.savedMail
      .noteSent(docs, known)
      .then(() => this.reloadSavedDrafts());
  };

  /** Take where the drafts this device holds come from, for Drafts offline. */
  setLocalDrafts = (source: () => readonly OfflineLocalDraft[]): void => {
    this.#localDrafts = source;
  };

  /** What the saved copy and this device know of drafts, to list and open them offline. */
  offlineDrafts(): OfflineDraftsInput {
    return {
      saved: this.savedMail.savedDrafts(),
      refs: this.savedMail.draftRefs,
      docs: this.#outboxDocs,
      sending: this.#sendingDrafts(),
      sent: this.savedMail.sentDrafts,
      local: this.#localDrafts(),
    };
  }

  /** A saved thread's messages and drafts, from the saved copy; null when it isn't saved. */
  savedThread(threadId: string): Promise<ThreadDetail | null> {
    return this.#savedBodies.load([threadId]).catch(() => null);
  }

  /** List Drafts from the saved copy again when it was last listed from there (a draft changed here). */
  reloadSavedDrafts = async (): Promise<void> => {
    if (this.view.kind !== "drafts" || !this.#draftsFromSaved) return;
    await this.#loadSavedDrafts(this.#listKey(this.view));
  };

  // Drafts from the saved copy and this device's own drafts: always once the copy is filled, and
  // while Gmail can't be reached before that. Resolves whether every thread on it has a row.
  #loadSavedDrafts(key: string): Promise<boolean> {
    this.#draftsFromSaved = true;
    return this.#loadSavedIds(
      key,
      offlineDraftThreads(this.offlineDrafts()),
      "",
    );
  }

  /** Take where the drafts this device is sending come from, read again as they change. */
  setSendingDrafts = (source: () => readonly SendingDraft[]): void => {
    this.#sendingDrafts = source;
  };

  #outboxKey(): string {
    return [...outboxDraftIds(this.#outboxDocs)].sort().join(" ");
  }

  // Gmail's drafts, to tell which the Outbox holds; none are listed while it holds none. Only the
  // latest call's answer counts, so an older listing finishing late can't replace a newer one.
  async #loadDraftRefs(): Promise<void> {
    const call = ++this.#draftRefsCall;
    const key = this.#outboxKey();
    this.#draftRefsFor = key;
    const held = outboxDraftIds(this.#outboxDocs);
    if (key === "") {
      this.#draftRefs = [];
      this.#draftRefsListedFor = held;
      return;
    }
    try {
      const drafts = await this.gmail.listDrafts();
      if (call !== this.#draftRefsCall || this.#disposed) return;
      this.#draftRefsListedFor = held;
      this.#draftRefs = drafts.map((draft) => ({
        draftId: draft.id,
        messageId: draft.message.id,
        threadId: draft.message.threadId,
      }));
    } catch {
      if (call !== this.#draftRefsCall) return;
      this.#draftRefsFor = null;
    }
  }

  // The view the shown search was started from, which leaving search returns to.
  #searchOrigin: View = { kind: "inbox" };

  /** Show a view: its cached copy at once, then Gmail's. */
  setView = (view: View): void => {
    this.#searchOrigin = searchOriginAfter(
      untrack(() => this.view),
      view,
      this.#searchOrigin,
    );
    this.view = view;
    this.dispatch({ type: "reset" });
    // The new view's rows at once, so keys reach them before its list draws (Settings has none).
    this.dispatch({ type: "layout", sections: untrack(() => this.layout) });
    this.snoozeMenuFor = null;
    this.labelMenuFor = null;
    this.todos.setEditing(null);
    void Promise.all([
      this.#showCached(viewKey(view)),
      this.#showCached(this.#listKey(view)),
    ]).then(async () => {
      await this.loadView(view);
      void this.#loadStaleBundles();
    });
  };

  /** Leave the shown search for the view it was started from. */
  closeSearch = (): void => {
    if (this.view.kind === "search") this.setView(this.#searchOrigin);
  };

  /**
   * Fetch a view's first page (or its next one) from Gmail; resolves whether the page and every
   * thread on it came back. A first page asked for while one loads is fetched again after it. A
   * background refetch asks with `priority` "background".
   */
  async loadView(
    view: View,
    more = false,
    priority: CallPriority = "visible",
  ): Promise<boolean> {
    if (this.#disposed) return false;
    const key = this.#listKey(view);
    const options = listOptions(view);
    // Lists that show whether a thread is still snoozed check for newer mail on snoozed threads.
    const snoozeChecks =
      !more &&
      (view.kind === "inbox" ||
        view.kind === "snoozed" ||
        (view.kind === "search" && searchScope(view.query).snoozedOnly));
    if (options === null) {
      if (snoozeChecks) this.#snoozeChecks();
      // `is:todo` asks Gmail for nothing, but lists the emails with a todo it finds.
      if (view.kind === "search" || view.kind === "todos") {
        void this.#fetchTaggedSummaries();
      }
      return true;
    }
    const current = this.lists[key] ?? EMPTY_LIST;
    if (current.loading) {
      if (!more) {
        const asked = this.#reloadAfter.get(key);
        if (asked === undefined || moreUrgent(priority, asked)) {
          this.#reloadAfter.set(key, priority);
        }
      }
      return false;
    }
    if (more && !current.nextPageToken) return true;
    if (snoozeChecks) this.#snoozeChecks();
    this.#setList(key, { loading: true, error: null, moreError: null });
    let complete = false;
    try {
      let request = { q: options.q ?? "", pageToken: current.nextPageToken };
      let pinned: Promise<boolean> = Promise.resolve(true);
      if (key === FOCUS_KEY) {
        request = { ...request, q: focusQuery(request.q) };
        if (!more) void this.#loadInboxBehindFocus();
      } else if (view.kind === "inbox") {
        // Fetched bundles' threads come from their own lists, so the inbox's pages leave them
        // out; a bundle due now comes back (or fails) first.
        await this.#fetchDueBundles(priority);
        const query = this.#inboxExclusion();
        request = more
          ? inboxNextPage(query, current.nextPageToken, current.query ?? "")
          : { q: query, pageToken: null };
        if (!more) this.#pinnedFresh = false;
        if (query !== "" && !this.#pinnedFresh) {
          pinned = this.#loadPinned(priority);
        }
      }
      // Drafts, like the inbox, comes whole from the saved copy once it is filled.
      const savedDrafts = view.kind === "drafts" && this.savedMail.filled;
      const [listed, pinnedListed] = await Promise.all([
        savedDrafts
          ? this.#loadSavedDrafts(key)
          : this.#loadPage(
              key,
              { ...options, q: request.q },
              PAGE_SIZE,
              more,
              more ? request.pageToken : null,
              priority,
            ),
        pinned,
      ]);
      complete = listed && pinnedListed;
      if (view.kind === "drafts" && !more && !savedDrafts) {
        this.#draftsFromSaved = false;
      }
      if (!more && (view.kind === "label" || view.kind === "search")) {
        void this.#fetchTaggedSummaries();
      }
      if (view.kind === "search" && !more) {
        void this.#searchEncrypted(view.query, key);
      }
    } catch (error) {
      if (view.kind === "search" && isNetworkError(error) && !more) {
        await this.#searchOffline(view.query, key);
      } else if (
        view.kind === "drafts" &&
        isNetworkError(error) &&
        !more &&
        this.savedMail.filled
      ) {
        await this.#loadSavedDrafts(key);
      } else {
        this.#setList(key, {
          loading: false,
          loaded: true,
          error: this.lists[key]?.ids.length
            ? null
            : errorText(error, this.gmail.gate.pausedUntil),
          moreError: more
            ? errorText(error, this.gmail.gate.pausedUntil)
            : null,
        });
      }
    }
    const again = this.#reloadAfter.get(key);
    if (again !== undefined) {
      this.#reloadAfter.delete(key);
      return this.loadView(view, false, again);
    }
    return complete;
  }

  // The inbox's own first page while focus mode lists only its pinned threads, when neither the
  // cache nor an earlier load has it, so the inbox's count stays right.
  async #loadInboxBehindFocus(): Promise<void> {
    const key = viewKey({ kind: "inbox" });
    const list = this.lists[key];
    if (list?.loaded || list?.loading) return;
    try {
      await this.#loadPage(
        key,
        { labelIds: [LABEL.inbox], q: this.#inboxExclusion() },
        PAGE_SIZE,
        false,
        null,
        "background",
      );
    } catch (error) {
      this.#setList(key, {
        loading: false,
        loaded: true,
        error: this.lists[key]?.ids.length
          ? null
          : errorText(error, this.gmail.gate.pausedUntil),
      });
    }
    // The inbox asked for while this ran (focus mode turned off) was waiting on it.
    const again = this.#reloadAfter.get(key);
    if (again !== undefined) {
      this.#reloadAfter.delete(key);
      void this.loadView({ kind: "inbox" }, false, again);
    }
  }

  // Snoozed threads' wake-ups and summaries, once per first page; calls meanwhile share them.
  #snoozeChecks(): void {
    void this.#checkWakeups();
    void this.#fetchSnoozedSummaries();
  }

  // One page of a list from Gmail into `lists` and the device's cache; resolves whether every
  // thread on it was read, and throws what Gmail threw for the list.
  async #loadPage(
    key: string,
    options: ListOptions,
    pageSize: number,
    more: boolean,
    pageToken = more ? (this.lists[key]?.nextPageToken ?? null) : null,
    priority: CallPriority = "visible",
  ): Promise<boolean> {
    const saved = this.savedMail.filled ? savedListLabels(options) : null;
    if (saved !== null) return this.#loadSaved(key, saved, options.q ?? "");
    this.#setList(key, { loading: true, error: null });
    const page = await this.gmail.listThreads(
      { ...options, maxResults: pageSize, pageToken },
      priority,
    );
    const stubs = page.threads ?? [];
    const complete = await this.#fetchSummaries(stubs, priority);
    if (this.#disposed) return false;
    // Threads whose read failed have no row; the list stops paging and offers Retry.
    const failedReads = complete
      ? {}
      : {
          moreError: errorText(
            this.#summaryFailure,
            this.gmail.gate.pausedUntil,
          ),
        };
    const pageIds = stubs.map((stub) => stub.id);
    const loaded = this.lists[key];
    // A refresh of a list scrolled past its first page keeps the pages after it, as does one of
    // the open bundle (its later threads may have come from the device's copy of the list).
    const keepsTail =
      this.#paged.has(key) ||
      (this.openBundle !== null && key === bundleKey(this.openBundle));
    const merged =
      !more && keepsTail && page.nextPageToken
        ? mergeFirstPage(loaded?.ids ?? [], pageIds)
        : null;
    const ids = more
      ? [...new Set([...(loaded?.ids ?? []), ...pageIds])]
      : (merged ?? pageIds);
    const nextPageToken =
      merged !== null
        ? (loaded?.nextPageToken ?? null)
        : (page.nextPageToken ?? null);
    const query = merged !== null ? (loaded?.query ?? "") : (options.q ?? "");
    if (more || merged !== null) this.#paged.add(key);
    else this.#paged.delete(key);
    this.#setList(key, {
      ids,
      nextPageToken,
      query,
      loading: false,
      loaded: true,
      local: false,
      ...failedReads,
    });
    void this.#cache
      .put("lists", key, { ids, nextPageToken, savedAt: Date.now() })
      .catch((error) => console.warn("list cache write failed", error));
    return complete;
  }

  // A list of the inbox's own threads, whole, from the saved copy, newest first; resolves whether
  // every thread on it has a row (one whose row the device lacks is read from Gmail).
  async #loadSaved(
    key: string,
    labels: readonly string[],
    query: string,
  ): Promise<boolean> {
    return this.#loadSavedIds(key, this.savedMail.savedIds(labels), query);
  }

  // A list of saved threads `ids`, newest first; resolves whether every one has a row.
  async #loadSavedIds(
    key: string,
    ids: readonly string[],
    query: string,
  ): Promise<boolean> {
    await this.#showCachedSummaries(ids);
    if (this.#disposed) return false;
    const unread = ids.filter((id) => !this.summaries.has(id));
    const complete =
      unread.length === 0 ||
      (await this.#fetchSummaries(unread.map((id) => ({ id }))));
    if (this.#disposed) return false;
    const listed = ids
      .filter((id) => this.summaries.has(id))
      .sort(
        (left, right) =>
          this.summaries.get(right)!.lastDate -
          this.summaries.get(left)!.lastDate,
      );
    this.#paged.delete(key);
    this.#setList(key, {
      ids: listed,
      nextPageToken: null,
      query,
      loading: false,
      loaded: true,
      local: false,
      moreError: null,
    });
    void this.#cache
      .put("lists", key, {
        ids: listed,
        nextPageToken: null,
        savedAt: Date.now(),
      })
      .catch((error) => console.warn("list cache write failed", error));
    return complete;
  }

  // The inbox's pinned threads: its own list leaves them out with their bundles' labels.
  async #loadPinned(priority: CallPriority): Promise<boolean> {
    this.#pinnedFresh = true;
    try {
      return await this.#loadPage(
        PINNED_KEY,
        { labelIds: [LABEL.inbox, PINNED_LABEL] },
        PINNED_PAGE,
        false,
        null,
        priority,
      );
    } catch {
      this.#pinnedFresh = false;
      this.#setList(PINNED_KEY, { loading: false });
      return false;
    }
  }

  // Bundle labels on loaded inbox threads whose own list wasn't asked for yet.
  #dueBundles(): string[] {
    const inboxLabels = (this.lists[viewKey({ kind: "inbox" })]?.ids ?? []).map(
      (id) => this.summaries.get(id)?.labelIds ?? [],
    );
    return bundlesToFetch(
      this.#bundleLabelIds,
      inboxLabels,
      new Set(this.#bundleRequests.keys()),
      this.#bundlesRemembered,
    );
  }

  // Save which fetched bundles hold inbox threads, when that changed.
  #rememberBundles(): void {
    const remembered = bundlesToRemember(
      this.#bundleLabelIds
        .filter((labelId) => this.#bundlesFetched.has(labelId))
        .map((labelId) => {
          const list = this.lists[bundleKey(labelId)];
          return {
            labelId,
            ids: list?.ids ?? [],
            more: !!list?.nextPageToken,
          };
        }),
    );
    if (
      remembered.length === this.#bundlesRemembered.size &&
      remembered.every((labelId) => this.#bundlesRemembered.has(labelId))
    ) {
      return;
    }
    this.#bundlesRemembered = new Set(remembered);
    void this.#cache
      .put("meta", BUNDLES_KEY, remembered)
      .catch((error) => console.warn("bundle cache write failed", error));
  }

  // Ask for the due bundles' own lists (the saved copy, then Gmail's first page); resolves once
  // each list asked for so far came back or failed.
  #fetchDueBundles(priority: CallPriority = "background"): Promise<unknown> {
    for (const labelId of this.#dueBundles()) {
      this.#bundleRequests.set(
        labelId,
        this.#showCached(bundleKey(labelId)).then(() =>
          this.#loadBundle(labelId, false, priority),
        ),
      );
    }
    return Promise.all(this.#bundleRequests.values());
  }

  // The query leaving the bundles whose own list came back out of the inbox's own list.
  #inboxExclusion(): string {
    return inboxExclusion(
      this.userLabels.filter(
        (label) =>
          this.#bundlesFetched.has(label.id) &&
          this.#bundleLabelIds.includes(label.id),
      ),
      this.userLabels,
    );
  }

  /**
   * A bundle label's own inbox list: its first few (a paged one keeps its later pages), or with
   * `more` its next page; one of the same kind already running is shared, another is waited for
   * first. Resolves whether it all came back. A first page is a background fetch; a next page is
   * on screen unless said otherwise.
   */
  async #loadBundle(
    labelId: string,
    more = false,
    priority: CallPriority = more ? "visible" : "background",
  ): Promise<boolean> {
    const running = this.#bundleLoads.get(labelId);
    if (running !== undefined) {
      if (more === running.more) return running.done;
      await running.done;
      return this.#loadBundle(labelId, more, priority);
    }
    const key = bundleKey(labelId);
    if (more && !this.lists[key]?.nextPageToken) return true;
    if (more) this.#setList(key, { moreError: null, loadingMore: true });
    const done = (async () => {
      try {
        const complete = await this.#loadPage(
          key,
          { labelIds: [LABEL.inbox, labelId] },
          more ? BUNDLE_PAGE_SIZE : BUNDLE_FIRST_PAGE,
          more,
          undefined,
          priority,
        );
        // Bundling turned off meanwhile leaves the label in the inbox's own list.
        if (this.#bundleRequests.has(labelId))
          this.#bundlesFetched.add(labelId);
        if (!more && complete) this.#staleBundles.delete(labelId);
        else if (!more) this.#staleBundles.add(labelId);
        this.#rememberBundles();
        return complete;
      } catch {
        this.#setList(key, {
          loading: false,
          loaded: true,
          ...(more ? { moreError: "Couldn't load more." } : {}),
        });
        if (!more) this.#staleBundles.add(labelId);
        return false;
      } finally {
        this.#bundleLoads.delete(labelId);
        if (more) this.#setList(key, { loadingMore: false });
      }
    })();
    this.#bundleLoads.set(labelId, { more, done });
    return done;
  }

  // Fetch again the first page of stale bundles once the inbox shows them; resolves whether all
  // came back (true while another view or focus mode shows, as they wait for the inbox).
  async #loadStaleBundles(): Promise<boolean> {
    if (this.view.kind !== "inbox" || focusMode.on) return true;
    const results = await Promise.all(
      [...this.#staleBundles]
        .filter((labelId) => this.#bundleLabelIds.includes(labelId))
        .map((labelId) => this.#loadBundle(labelId)),
    );
    return results.every(Boolean);
  }

  /** Whether Gmail has more of a bundle's inbox threads than were fetched. */
  bundleHasMore(labelId: string): boolean {
    return !!this.lists[bundleKey(labelId)]?.nextPageToken;
  }

  /** Whether a bundle's next page is being fetched (not a refresh of its first, which shows nothing). */
  bundleLoading(labelId: string): boolean {
    return !!this.lists[bundleKey(labelId)]?.loadingMore;
  }

  /** Why a bundle's last next page failed to load, or null. */
  bundleError(labelId: string): string | null {
    return this.lists[bundleKey(labelId)]?.moreError ?? null;
  }

  /** Bundle labels whose open list's end is on screen. */
  bundleEndsShown = $state.raw<ReadonlySet<string>>(new Set());

  /** Note whether the end of a bundle's open list is on screen. */
  noteBundleEnd = (labelId: string, shown: boolean): void => {
    if (this.bundleEndsShown.has(labelId) === shown) return;
    const next = new Set(this.bundleEndsShown);
    if (shown) next.add(labelId);
    else next.delete(labelId);
    this.bundleEndsShown = next;
  };

  /** Fetch the next page of a bundle's inbox threads; after a failure, first the threads that didn't load. */
  loadMoreBundle = (labelId: string): Promise<void> => {
    const running = this.#bundleMore.get(labelId);
    if (running !== undefined) return running;
    const key = bundleKey(labelId);
    // Loading from the ask on, so the list model sees it start and end around the page.
    if (this.lists[key]?.nextPageToken) {
      this.#setList(key, { loadingMore: true });
    }
    const done = (async () => {
      try {
        if (await this.#readMissing(key)) {
          await this.#loadBundle(labelId, true);
        }
      } finally {
        this.#bundleMore.delete(labelId);
        this.#setList(key, { loadingMore: false });
      }
    })();
    this.#bundleMore.set(labelId, done);
    return done;
  };

  // Each bundle's "load more" running now.
  readonly #bundleMore = new Map<string, Promise<void>>();

  /** Try the current list again after a page failed: first the threads that didn't load, then its next page. */
  retryList = async (): Promise<void> => {
    const view = this.view;
    if (!(await this.#readMissing(this.#listKey(view)))) return;
    if (this.lists[this.#listKey(view)]?.nextPageToken) {
      await this.loadView(view, true);
    }
  };

  // After a failed page, read again the list's threads that have no row; false (with the list's
  // error set again) when some still fail.
  async #readMissing(key: string): Promise<boolean> {
    const list = this.lists[key];
    if (!list?.moreError) return true;
    this.#setList(key, { moreError: null });
    const missing = missingThreadIds(
      list.ids,
      (id) => this.summaries.has(id),
      this.#gone,
    );
    if (
      await this.#fetchSummaries(
        missing.map((id) => ({ id })),
        "visible",
      )
    ) {
      return true;
    }
    this.#setList(key, {
      moreError: errorText(this.#summaryFailure, this.gmail.gate.pausedUntil),
    });
    return false;
  }

  // Gmail is out of reach: match the threads saved on this device instead.
  async #searchOffline(query: string, key: string): Promise<void> {
    try {
      const [summaries, details] = await Promise.all([
        this.#cache.entries("summaries"),
        this.#cache.entries("threads"),
      ]);
      const deviceKey = this.encryption.deviceKey();
      const opened = await Promise.all(
        details.map(
          async ([id, detail]) =>
            [String(id), await this.#searchDetail(detail, deviceKey)] as const,
        ),
      );
      const detailById = new Map(opened);
      const subjects = this.encryption.subjects;
      const ids = searchLocal(
        summaries.map(([, summary]) => {
          const current = this.summaries.get(summary.id) ?? summary;
          const subject = subjects.get(summary.id);
          return {
            summary: subject === undefined ? current : { ...current, subject },
            detail: detailById.get(summary.id) ?? null,
          };
        }),
        searchPlan(query),
        this.labels,
      );
      const matched = new Set(ids);
      const next = new Map(this.summaries);
      for (const [, summary] of summaries) {
        if (matched.has(summary.id) && !next.has(summary.id)) {
          next.set(summary.id, summary);
        }
      }
      this.summaries = next;
      this.#setList(key, {
        ids,
        nextPageToken: null,
        loading: false,
        loaded: true,
        error: null,
        local: true,
      });
    } catch (error) {
      console.error("offline search failed", error);
      this.#setList(key, {
        loading: false,
        loaded: true,
        error: errorText(error),
      });
    }
  }

  // A cached thread with its sealed messages opened, decrypting each version once per session.
  async #searchDetail(
    stored: ThreadDetail,
    deviceKey: CryptoKey | null,
  ): Promise<ThreadDetail> {
    if (
      deviceKey === null ||
      !stored.messages.some((message) => message.pgp?.sealed !== undefined)
    ) {
      return stored;
    }
    const known = this.#searchDetails.get(stored.id);
    if (known?.historyId === stored.historyId) return known;
    const opened = await openDetail(stored, deviceKey);
    if (this.encryption.unlocked) this.#searchDetails.set(stored.id, opened);
    return opened;
  }

  /**
   * Reload the current view and labels, and the open thread when it changed. Unless `force`, a
   * cheap history read first checks whether anything changed in Gmail since the last reload that
   * came back whole, and skips the reload when nothing did and that one did. A forced reload
   * fetches every bundle's first page again. A refresh asked for while one runs runs once after it.
   */
  refresh = (force = true): Promise<void> => {
    if (this.#refreshing !== null) {
      this.#refreshQueued = {
        force: force || (this.#refreshQueued?.force ?? false),
      };
      return this.#refreshing;
    }
    this.#refreshing = (async () => {
      try {
        let next: { force: boolean } | null = { force };
        while (next !== null && !this.#disposed) {
          this.#refreshQueued = null;
          await this.#refreshOnce(next.force);
          next = this.#refreshQueued;
        }
      } finally {
        this.#refreshing = null;
      }
    })();
    return this.#refreshing;
  };

  async #refreshOnce(force: boolean): Promise<void> {
    await this.savedMail.ready;
    // Once the saved copy is filled, its own history sync says what changed; until then it fills
    // in the background while lists come from Gmail's pages.
    const saved = this.savedMail.filled;
    const synced = saved ? await this.savedMail.sync() : null;
    if (!saved) void this.savedMail.sync();
    // Another sync is under way: what it applies shows when it's done, so this one knows of nothing new.
    const outcome = synced === "busy" ? null : synced;
    const records = saved
      ? synced === "busy"
        ? []
        : (outcome?.records ?? null)
      : force
        ? null
        : await this.#historySinceSync();
    if (this.#disposed) return;
    if (
      !force &&
      records !== null &&
      records.length === 0 &&
      !this.#loadFailed
    ) {
      return;
    }
    const history = force ? null : records;
    // Gmail's history id from before the reload, kept only once the reload came back whole, so
    // a failed one is tried again at the next check.
    const point: Promise<string | null> = saved
      ? Promise.resolve(null)
      : this.gmail.getProfile().then(
          (profile) => profile.historyId,
          () => null,
        );
    const fetched = [...this.#bundlesFetched];
    for (const labelId of history === null
      ? fetched
      : touchedBundles(history, fetched)) {
      this.#staleBundles.add(labelId);
    }
    // The saved copy already brought its own threads' rows up to date.
    const touched = saved
      ? (outcome?.touched ?? []).filter((id) => this.summaries.has(id))
      : history === null
        ? []
        : this.#knownThreadsIn(history);
    const before = touched.map((id) => this.summaries.get(id));
    void this.#loadLabels(history === null || touchesDrafts(history));
    const loaded = await this.loadView(this.view, false, "background");
    const bundles = await this.#loadStaleBundles();
    // Threads history changed that no page just fetched holds (deep in a paged list) are read alone.
    const missed = touched.filter(
      (id, index) => this.summaries.get(id) === before[index],
    );
    const read = await this.#fetchSummaries(missed.map((id) => ({ id })));
    const historyId = await point;
    if (this.#disposed) return;
    const complete =
      loaded &&
      bundles &&
      read &&
      (saved ? synced !== null : historyId !== null);
    this.#loadFailed = !complete;
    if (complete && !saved) this.#syncedHistoryId = historyId;
    const id = this.openId;
    if (id === null) return;
    // A saved row's sync already brought it up to date (see `#reshowSaved`).
    const members = this.#joins.membersOf(id);
    if (members.every((member) => this.savedMail.has(member))) return;
    const known = this.summaries.get(id)?.historyId;
    if (known === undefined || known !== this.detail?.historyId) {
      void this.#loadDetail(id, false);
    }
  }

  // Gmail's history id as of the last reload that came back whole, or null before one.
  #syncedHistoryId: string | null = null;
  // Whether the last reload missed something (a list, a thread, a bundle), so the next check reloads.
  #loadFailed = false;

  // The threads with a summary on the device that history records changed.
  #knownThreadsIn(records: readonly GmailHistoryRecord[]): string[] {
    const ids = new Set<string>();
    for (const record of records) {
      for (const entry of [
        ...(record.messagesAdded ?? []),
        ...(record.messagesDeleted ?? []),
        ...(record.labelsAdded ?? []),
        ...(record.labelsRemoved ?? []),
      ]) {
        const id = entry.message.threadId;
        if (id && this.summaries.has(id)) ids.add(id);
      }
    }
    return [...ids];
  }

  // Gmail's history after the last whole reload, one page of every kind; null when unknown or longer.
  async #historySinceSync(): Promise<GmailHistoryRecord[] | null> {
    const start = this.#syncedHistoryId;
    if (start === null) return null;
    try {
      const result = await this.gmail.listHistory(start, undefined, 500, null);
      return result.nextPageToken ? null : (result.history ?? []);
    } catch {
      return null;
    }
  }

  // Threads with a change of this device's own not yet in Gmail: queued, or open to undo.
  #locallyChanged(): Set<string> {
    const changed = new Set(this.queue.map((change) => change.threadId));
    for (const item of this.lastUndo?.items ?? []) changed.add(item.id);
    return changed;
  }

  // A fresh Gmail copy of a snoozed thread without INBOX was archived elsewhere: that's Done.
  // `changedBefore` holds threads changed here when the fetch began, whose change may have reached
  // Gmail only after it.
  #dropSnoozesArchivedElsewhere(
    threads: readonly ThreadSummary[],
    changedBefore: ReadonlySet<string>,
  ): void {
    const now = Date.now();
    const changed = this.#locallyChanged();
    for (const thread of threads) {
      const hasLocalChange =
        changedBefore.has(thread.id) || changed.has(thread.id);
      if (
        !snoozeArchivedElsewhere(
          this.snoozeDocs.get(thread.id),
          thread.labelIds,
          thread.historyId,
          now,
          hasLocalChange,
        )
      ) {
        continue;
      }
      void deleteSnoozeDoc(this.#db, this.uid, thread.id).catch((error) =>
        console.error("snooze clear failed", error),
      );
    }
  }

  /** Queue a label change, show it at once, and send it when online. */
  #change(id: string, add: string[], remove: string[]): void {
    this.#changeAll([{ threadId: id, add, remove }]);
  }

  /**
   * Queue label changes to several threads at once: one rows update, one queue write. A change to
   * a joined row's lead reaches each of its Gmail threads (see `expandChanges`).
   */
  #changeAll(asked: readonly NewLabelChange[]): void {
    const changes = expandChanges(
      asked.map((change) => ({
        ...change,
        add: this.#gmailIds(change.add),
        remove: this.#gmailIds(change.remove),
      })),
      this.#joins,
      (id) => this.summaries.get(id)?.labelIds,
    );
    if (changes.length === 0) return;
    const updated: ThreadSummary[] = [];
    for (const change of changes) {
      const thread =
        updated.findLast((entry) => entry.id === change.threadId) ??
        this.summaries.get(change.threadId);
      if (thread) {
        updated.push({
          ...thread,
          labelIds: applyChange(thread.labelIds, change),
          unread: unreadAfter(thread.unread, change),
        });
      }
    }
    this.#putSummaries(updated);
    const before = this.queue.length;
    this.queue = enqueueAll(this.queue, changes);
    this.#storeQueue(this.queue.slice(before));
    void this.flush();
  }

  // Store queued changes; before the stored queue is read they would take its numbers, so they
  // wait in memory until `#start` numbers them after it.
  #storeQueue(entries: readonly LabelChange[]): void {
    if (!this.#queueRead || entries.length === 0) return;
    void this.#cache
      .putMany(
        "queue",
        entries.map((entry) => [entry.id, entry]),
      )
      .catch((error) => console.error("queue write failed", error));
  }

  // Label ids with each local one Gmail already made named by Gmail's id, and refused ones left out.
  #gmailIds(ids: readonly string[]): string[] {
    return ids.flatMap((id) => {
      const made = this.#madeIds.get(id);
      return made === undefined ? [id] : made === null ? [] : [made];
    });
  }

  /**
   * Send queued changes to Gmail in order, including ones queued meanwhile: label and filter
   * changes first, one at a time, then thread label changes (a thread's wait while they name a
   * label Gmail hasn't made yet). Each stops at the first one the network or Gmail can't take yet.
   */
  flush = async (): Promise<void> => {
    // `#start` flushes once the stored queue is read, so nothing goes out of order.
    if (this.#flushing || !this.#queueRead) return;
    this.#flushing = true;
    try {
      while (!this.#disposed) {
        const account = await this.#flushAccount();
        await this.#flushThreads();
        if (account === "waiting" || this.accountQueue.length === 0) break;
      }
    } finally {
      this.#flushing = false;
    }
  };

  async #flushThreads(): Promise<void> {
    await replay(
      () => (this.#disposed ? [] : sendableChanges(this.queue)),
      async (change) => {
        try {
          await this.gmail.modifyThread(
            change.threadId,
            change.add,
            change.remove,
          );
        } catch (error) {
          // Anything but a Gmail answer (a lost sign-in, say) waits too.
          if (isRetryable(error) || !(error instanceof GmailError)) {
            return "later";
          }
          console.error("label change dropped", change, error);
          this.#settleChange(change);
          this.#changeRefused(change.threadId, error);
          return "settled";
        }
        this.#settleChange(change);
        return "settled";
      },
      FETCH_CONCURRENCY,
    );
  }

  // Take a thread's change, and the ones before it, off the queue.
  #settleChange(change: LabelChange): void {
    const sent = this.queue.filter(
      (entry) => entry.threadId === change.threadId && entry.seq <= change.seq,
    );
    this.queue = this.queue.filter((entry) => !sent.includes(entry));
    void this.#cache
      .deleteMany(
        "queue",
        sent.map((entry) => entry.id),
      )
      .catch((error) => console.error("queue write failed", error));
  }

  // Gmail refused a thread's change for good (the thread is gone, say): say so, bring back a row
  // the change took out, and show the thread as Gmail has it.
  #changeRefused(threadId: string, error: GmailError): void {
    this.#showNotice(
      `Couldn't change a conversation. Gmail said: ${error.message}`,
    );
    this.dispatch({
      type: "restore",
      keys: [threadRowKey(this.rowOf(threadId))],
    });
    void this.#fetchSummaries([{ id: threadId }], "action").catch((reason) =>
      console.error("thread reread failed", reason),
    );
  }

  // Save or clear threads' snoozes, shown at once so an action's rows leave with it rather than
  // when Firestore echoes the write. A write that fails puts back what was shown, and with
  // `restore` the rows it took out too. One for a joined row's lead reaches each of its Gmail
  // threads (see `expandSnoozes`).
  #setSnoozes(
    asked: readonly [string, SnoozeDoc | null][],
    restore = false,
  ): void {
    if (asked.length === 0) return;
    const entries = expandSnoozes(
      asked,
      this.#joins,
      (id) => this.summaries.get(id)?.historyId,
    );
    const next = new Map(this.snoozeDocs);
    for (const [id, snooze] of entries) {
      if (snooze === null) next.delete(id);
      else next.set(id, snooze);
    }
    const before = this.snoozeDocs;
    this.snoozeDocs = next;
    for (const [id, snooze] of entries) {
      const write =
        snooze === null
          ? deleteSnoozeDoc(this.#db, this.uid, id)
          : writeSnoozeDoc(this.#db, this.uid, id, snooze);
      void write.catch((error) => {
        console.error("snooze write failed", error);
        if (this.snoozeDocs.get(id) === (snooze ?? undefined)) {
          const reverted = new Map(this.snoozeDocs);
          const was = before.get(id);
          if (was === undefined) reverted.delete(id);
          else reverted.set(id, was);
          this.snoozeDocs = reverted;
        }
        if (restore) {
          this.dispatch({
            type: "restore",
            keys: [threadRowKey(this.rowOf(id))],
          });
        }
      });
    }
  }

  // What undo puts back of row `id`: each of its Gmail threads as it is now.
  #undoItems(id: string): UndoItem[] {
    return this.#joins.membersOf(id).flatMap((member) => {
      const thread = this.summaries.get(member);
      return thread
        ? [
            {
              id: member,
              row: id,
              labelIds: thread.labelIds,
              snooze: this.snoozeDocs.get(member),
            },
          ]
        : [];
    });
  }

  // What undo holds of `ids`, each thread once: a thread not read yet counts as having `assumed`
  // labels, or is skipped without them.
  #undoItemsAssuming(
    ids: readonly string[],
    assumed?: readonly string[],
  ): UndoItem[] {
    const asked = ids.flatMap((id): UndoItem[] => {
      const known = this.#undoItems(id);
      return known.length > 0 || assumed === undefined
        ? known
        : [
            {
              id,
              row: id,
              labelIds: [...assumed],
              snooze: this.snoozeDocs.get(id),
            },
          ];
    });
    // Ids Gmail listed for a bundle can name a joined row's other threads as well.
    const seen = new Set<string>();
    return asked.filter((item) => !seen.has(item.id) && seen.add(item.id));
  }

  #setUndo(entry: UndoEntry): void {
    this.lastUndo = entry;
    this.undoExpiresAt = Date.now() + UNDO_MS;
  }

  #clearUndo(): void {
    this.lastUndo = null;
    this.undoExpiresAt = null;
    this.dispatch({ type: "undoExpired" });
  }

  // Whether an action ran inside another (Done on a selection runs Done on its threads), which reports for both.
  #acting = false;

  // Run an action, then tell the list which of its rows the action took out: those the list held
  // that the data, changed at once, no longer lists. `clearSelection` says, after it ran, whether
  // the selection it acted on ends.
  #act(
    from: ActFrom,
    run: () => void,
    clearSelection: () => boolean = () => false,
  ): void {
    if (this.#acting) {
      run();
      return;
    }
    untrack(() => {
      const listed = layoutKeys(this.list.layout);
      const undoBefore = this.lastUndo;
      this.#acting = true;
      try {
        run();
      } finally {
        this.#acting = false;
      }
      const open = this.list.open;
      const left = layoutKeys(
        layoutSections(
          this.#focus(this.#sectionsFor(open), this.view, open).sections,
          (labelId) => this.#bundlePaging(labelId),
        ),
      );
      this.dispatch({
        type: "act",
        removes: [...listed].filter((key) => !left.has(key)),
        from,
        undoable: this.lastUndo !== undoBefore,
        clearSelection: clearSelection(),
      });
    });
  }

  // Where an action on `ids` comes from: the open thread, else a row.
  #fromFor(ids: readonly string[]): ActFrom {
    return this.openId !== null && ids.includes(this.openId) ? "thread" : "row";
  }

  // Taking the open thread out of the list (done, snooze, spam, unread) returns to the list.
  #closeIfOpen(ids: readonly string[]): void {
    if (this.openId !== null && ids.includes(this.openId)) {
      this.dispatch({ type: "closeThread" });
    }
  }

  #bundlePaging(labelId: string): BundlePaging {
    const list = this.lists[bundleKey(labelId)];
    return { more: !!list?.nextPageToken, loading: !!list?.loadingMore };
  }

  /**
   * Done: archive in Gmail, and drop any snooze. A thread not read yet counts for undo as having
   * `assumed` labels, or is skipped without them.
   */
  done = (ids: readonly string[], assumed?: readonly string[]): void => {
    this.#act(this.#fromFor(ids), () => {
      const items = this.#undoItemsAssuming(ids, assumed);
      if (items.length === 0) return;
      this.#closeIfOpen(ids);
      this.#changeAll(
        items.map((item) => ({
          threadId: item.id,
          add: [],
          remove: [LABEL.inbox],
        })),
      );
      this.#setSnoozes(
        items.filter((item) => item.snooze).map((item) => [item.id, null]),
      );
      this.#setUndo({ kind: "done", items });
    });
  };

  /** Move to inbox: put threads that are neither in the inbox nor snoozed back in it, undoable like Done. */
  moveToInbox = (ids: readonly string[]): void => {
    this.#act(this.#fromFor(ids), () => {
      const items = ids
        .filter((id) => this.inboxActionOf(id) === "inbox")
        .flatMap((id) => this.#undoItems(id));
      if (items.length === 0) return;
      this.#closeIfOpen(items.map((item) => item.id));
      this.#changeAll(
        items.map((item) => ({
          threadId: item.id,
          add: [LABEL.inbox],
          remove: [],
        })),
      );
      // Undo of "notSpam" says "Moved to Inbox" and takes INBOX back off.
      this.#setUndo({ kind: "notSpam", items, labels: [LABEL.inbox] });
    });
  };

  /** The inbox action thread `id` offers (see `inboxAction`): Done, Move to inbox, or neither. */
  inboxActionOf(id: string): "done" | "inbox" | null {
    const thread = this.#threadOf(id);
    return thread === undefined
      ? null
      : inboxAction(filtered(thread, this.statusOf(id)));
  }

  /** Done or Move to inbox on thread `id`, whichever it offers. */
  toggleInbox = (id: string): void => {
    const action = this.inboxActionOf(id);
    if (action === "done") this.done([id]);
    else if (action === "inbox") this.moveToInbox([id]);
  };

  /** Mark spam: add `SPAM`, take it out of the inbox, and drop any snooze. */
  markSpam = (ids: readonly string[]): void => {
    this.#moveToSpam(ids, true);
  };

  /** Not spam: take `SPAM` off and put it back in the inbox. */
  notSpam = (ids: readonly string[]): void => {
    this.#moveToSpam(ids, false);
  };

  #moveToSpam(ids: readonly string[], spam: boolean): void {
    this.#act(this.#fromFor(ids), () => {
      const items = ids.flatMap((id) => this.#undoItems(id));
      if (items.length === 0) return;
      this.#closeIfOpen(ids);
      this.#changeAll(
        items.map((item) => ({
          threadId: item.id,
          add: spam ? [LABEL.spam] : [LABEL.inbox],
          remove: spam ? [LABEL.inbox] : [LABEL.spam],
        })),
      );
      if (spam) {
        this.#setSnoozes(
          items.filter((item) => item.snooze).map((item) => [item.id, null]),
        );
      }
      this.#setUndo({ kind: spam ? "spam" : "notSpam", items });
    });
  }

  /**
   * Delete: move to the trash (add `TRASH`, take it out of the inbox) and drop any snooze. A thread
   * not read yet counts for undo as having `assumed` labels, or is skipped without them.
   */
  trash = (ids: readonly string[], assumed?: readonly string[]): void => {
    this.#moveToTrash(ids, true, assumed);
  };

  /** Take out of the trash and put back in the inbox. */
  untrash = (ids: readonly string[]): void => {
    this.#moveToTrash(ids, false);
  };

  #moveToTrash(
    ids: readonly string[],
    trash: boolean,
    assumed?: readonly string[],
  ): void {
    this.#act(this.#fromFor(ids), () => {
      const items = this.#undoItemsAssuming(ids, assumed);
      if (items.length === 0) return;
      this.#closeIfOpen(ids);
      this.#changeAll(
        items.map((item) => ({
          threadId: item.id,
          add: trash ? [LABEL.trash] : [LABEL.inbox],
          remove: trash ? [LABEL.inbox] : [LABEL.trash],
        })),
      );
      if (trash) {
        this.#setSnoozes(
          items.filter((item) => item.snooze).map((item) => [item.id, null]),
        );
      }
      this.#setUndo({ kind: trash ? "trash" : "notSpam", items });
    });
  }

  /** Add and remove labels on a thread (queued while offline). */
  changeLabels = (id: string, add: string[], remove: string[]): void => {
    this.changeLabelsAll([id], add, remove);
  };

  /** Add and remove labels on several threads at once (queued while offline). */
  changeLabelsAll = (
    ids: readonly string[],
    add: string[],
    remove: string[],
  ): void => {
    if (add.length === 0 && remove.length === 0) return;
    this.#act(this.#fromFor(ids), () =>
      this.#changeWithUndo(
        "label",
        ids.map((threadId) => ({ threadId, add, remove })),
      ),
    );
  };

  /**
   * Queue label changes and offer to undo them; a label change made while another's undo is still
   * on offer joins that undo.
   */
  #changeWithUndo(
    kind: "label" | "move" | "read" | "unread",
    changes: readonly NewLabelChange[],
  ): void {
    const items = changes.flatMap((change) => this.#undoItems(change.threadId));
    this.#changeAll(changes);
    if (items.length === 0) return;
    const touched = changes.flatMap((change) => [
      ...change.add,
      ...change.remove,
    ]);
    const previous = this.lastUndo;
    if (kind === "label" && previous?.kind === "label") {
      this.#setUndo({
        kind,
        items: mergeUndoItems(previous.items, items),
        labels: undoLabels(kind, [...(previous.labels ?? []), ...touched]),
      });
    } else {
      this.#setUndo({ kind, items, labels: undoLabels(kind, touched) });
    }
  }

  /** Move a thread into one of Gmail's categories (queued while offline). */
  changeCategory = (id: string, category: string): void => {
    this.changeCategoryAll([id], category);
  };

  /** Move several threads into one of Gmail's categories (queued while offline). */
  changeCategoryAll = (ids: readonly string[], category: string): void => {
    this.#act(this.#fromFor(ids), () => this.#changeCategory(ids, category));
  };

  #changeCategory(ids: readonly string[], category: string): void {
    const changes: NewLabelChange[] = [];
    const key = viewKey({ kind: "inbox" });
    let listed = this.lists[key]?.ids ?? [];
    for (const id of ids) {
      const thread = this.#threadOf(id);
      if (!thread) continue;
      const { add, remove } = categoryChange(thread.labelIds, category);
      if (add.length === 0 && remove.length === 0) continue;
      for (const member of this.#joins.membersOf(id)) {
        const own = this.summaries.get(member);
        if (own === undefined || !isInInbox(own)) continue;
        // A thread leaving a bundle is in no list the inbox reads until the lists are fetched again.
        listed = keepListed(
          listed,
          member,
          (other) => this.summaries.get(other)?.lastDate,
        );
      }
      changes.push({ threadId: id, add, remove });
    }
    if (changes.length === 0) return;
    if (listed !== (this.lists[key]?.ids ?? [])) {
      this.#setList(key, { ids: listed });
    }
    this.#changeWithUndo("move", changes);
  }

  /**
   * Make a user label: it shows at once under a local id, which threads and filters can use right
   * away, and is made in Gmail when online.
   */
  createLabel = (name: string, color?: LabelColorInput): GmailLabel => {
    const labelId = newLocalId();
    this.#queueAccount([
      { kind: "createLabel", labelId, name, ...(color ? { color } : {}) },
    ]);
    return (
      this.labels.find((label) => label.id === labelId) ?? {
        id: labelId,
        name,
        type: "user",
      }
    );
  };

  /**
   * Rename a label, the labels nested under it following, and rewrite the todos' tags that name
   * them (`renameTags`): shown at once, sent when online.
   */
  renameLabel = (id: string, newName: string): void => {
    const label = this.labels.find((candidate) => candidate.id === id);
    const name = newName.trim();
    if (!label || name === "" || label.name === name) return;
    this.patchLabels(
      renames(this.labels, id, name).map((change) => ({
        id: change.id,
        patch: { name: change.name },
      })),
    );
    if (label.type === "user") {
      this.#setPendingRetags([
        ...this.#pendingRetags,
        { from: label.name, to: name },
      ]);
      void this.#runRetags();
    }
  };

  #setPendingRetags(retags: TagRename[]): void {
    this.#pendingRetags = retags;
    void this.#cache
      .put("meta", RETAGS_KEY, retags)
      .catch((error) => console.error("cache write failed", error));
  }

  // Carry the todos' tags through queued label renames in order; one that fails (offline, say)
  // stops the run and waits for the next.
  #runRetags(): Promise<void> {
    this.#retagRun ??= (async () => {
      try {
        while (!this.#disposed) {
          const [next] = this.#pendingRetags;
          if (next === undefined) break;
          await this.todos.retag(next.from, next.to);
          this.#setPendingRetags(
            this.#pendingRetags.filter((retag) => retag !== next),
          );
        }
      } catch (error) {
        console.warn("todo tag rename waits", error);
      } finally {
        this.#retagRun = null;
      }
    })();
    return this.#retagRun;
  }

  /** Change labels' names or colors: shown at once, sent when online. */
  patchLabels = (
    patches: readonly { id: string; patch: Partial<GmailLabelInput> }[],
  ): void => {
    this.#queueAccount(
      patches.flatMap(({ id, patch }) =>
        this.#gmailIds([id]).map((labelId) => ({
          kind: "patchLabel" as const,
          labelId,
          ...(patch.name === undefined ? {} : { name: patch.name }),
          ...(patch.color ? { color: patch.color } : {}),
        })),
      ),
    );
  };

  /** Fetch the account's Gmail filters. */
  loadFilters = async (): Promise<void> => {
    this.fetchedFilters = await this.gmail.listFilters();
  };

  /**
   * Save a filter, in place of `replacing` when given (Gmail has no edit, so the old one is
   * deleted once the new one is made): shown at once, sent when online.
   */
  saveFilter = (
    filter: { criteria: GmailFilterCriteria; action: GmailFilterAction },
    replacing: string | null,
  ): void => {
    const { addLabelIds, removeLabelIds } = filter.action;
    const action: GmailFilterAction = {
      ...filter.action,
      ...(addLabelIds ? { addLabelIds: this.#gmailIds(addLabelIds) } : {}),
      ...(removeLabelIds
        ? { removeLabelIds: this.#gmailIds(removeLabelIds) }
        : {}),
    };
    const gmailReplacing =
      replacing === null ? undefined : this.#gmailIds([replacing])[0];
    this.#queueAccount([
      {
        kind: "createFilter",
        filterId: newLocalId(),
        criteria: filter.criteria,
        action,
        ...(gmailReplacing ? { replacing: gmailReplacing } : {}),
      },
    ]);
  };

  /** Delete a filter: gone at once, deleted in Gmail when online. */
  deleteFilter = (id: string): void => {
    const made = this.#madeIds.get(id);
    if (made === null) return;
    this.#queueAccount([{ kind: "deleteFilter", filterId: made ?? id }]);
  };

  #queueAccount(changes: readonly NewAccountChange[]): void {
    if (changes.length === 0) return;
    let queue = this.accountQueue;
    for (const change of changes) queue = enqueueAccount(queue, change);
    this.#setAccountQueue(queue);
    void this.flush();
  }

  // Keep the label and filter queue, on screen and on the device, and show labels with it.
  #setAccountQueue(queue: AccountChange[]): void {
    this.accountQueue = queue;
    this.labels = labelsWithPending(this.labels, queue);
    void this.#cache
      .put("meta", ACCOUNT_QUEUE_KEY, queue)
      .catch((error) => console.error("queue write failed", error));
  }

  // Send queued label and filter changes in order, including ones queued meanwhile; answers
  // whether the queue emptied or a change has to wait.
  async #flushAccount(): Promise<"empty" | "waiting"> {
    let labelsChanged = false;
    try {
      for (;;) {
        if (this.#disposed) return "waiting";
        const change = nextAccountChange(this.accountQueue);
        if (change === undefined) return "empty";
        try {
          await this.#sendAccountChange(change);
        } catch (error) {
          // Anything but a Gmail answer (a lost sign-in, say) waits too.
          if (isRetryable(error) || !(error instanceof GmailError)) {
            return "waiting";
          }
          console.error("account change dropped", change, error);
          this.#refused(change, error);
        }
        if (change.kind === "createLabel" || change.kind === "patchLabel") {
          labelsChanged = true;
        }
      }
    } finally {
      if (labelsChanged) void this.#loadLabels();
    }
  }

  // Send one change and take it off the queue; throws what Gmail or the network answered.
  async #sendAccountChange(change: AccountChange): Promise<void> {
    const sent = () => changeSent(this.accountQueue, change.seq);
    if (change.kind === "createLabel") {
      let label: GmailLabel;
      try {
        label = await this.gmail.createLabel({
          name: change.name,
          labelListVisibility: "labelShow",
          messageListVisibility: "show",
          ...(change.color ? { color: change.color } : {}),
        });
      } catch (error) {
        // A try that timed out but was made, or the same label made elsewhere: use Gmail's.
        const existing = isDuplicateLabel(error)
          ? labelNamed(await this.gmail.listLabels(), change.name)
          : null;
        if (existing === null) throw error;
        label = existing;
      }
      this.#labelMade(change, label);
    } else if (change.kind === "patchLabel") {
      await this.gmail.patchLabel(change.labelId, {
        ...(change.name === undefined ? {} : { name: change.name }),
        ...(change.color ? { color: change.color } : {}),
      });
      this.#setAccountQueue(sent());
    } else if (change.kind === "createFilter") {
      let made: GmailFilter;
      try {
        made = await this.gmail.createFilter({
          criteria: change.criteria,
          action: change.action,
        });
      } catch (error) {
        if (!isDuplicateFilter(error)) throw error;
        // Saving an edit that changed nothing leaves the old filter standing; otherwise a try that
        // timed out was made after all, or the same filter exists already.
        const standing =
          change.replacing ??
          (await this.gmail.listFilters()).find((filter) =>
            sameFilter(filter, change),
          )?.id;
        if (standing === undefined) throw error;
        this.#madeIds.set(change.filterId, standing);
        this.#setAccountQueue(
          filterMade(this.accountQueue, change.seq, standing),
        );
        return;
      }
      const madeId = made.id ?? change.filterId;
      this.#madeIds.set(change.filterId, madeId);
      this.fetchedFilters = [...(this.fetchedFilters ?? []), made];
      this.#setAccountQueue(filterMade(this.accountQueue, change.seq, madeId));
    } else if (change.kind === "deleteFilter") {
      try {
        await this.gmail.deleteFilter(change.filterId);
      } catch (error) {
        if (!(error instanceof GmailError && error.status === 404)) throw error;
      }
      this.fetchedFilters =
        this.fetchedFilters?.filter(
          (filter) => filter.id !== change.filterId,
        ) ?? null;
      this.#setAccountQueue(sent());
    } else {
      const ids = await this.threadIdsMatching(change.query);
      this.#setAccountQueue(sent());
      this.#changeAll(
        ids.map((threadId) => ({
          threadId,
          add: change.add,
          remove: change.remove,
        })),
      );
    }
  }

  // Gmail made a label made on the device: name it by Gmail's id everywhere the local one shows.
  #labelMade(
    change: Extract<AccountChange, { kind: "createLabel" }>,
    label: GmailLabel,
  ): void {
    const localId = change.labelId;
    this.#madeIds.set(localId, label.id);
    const listed = this.labels.some((entry) => entry.id === label.id);
    this.labels = this.labels.flatMap((entry) =>
      entry.id !== localId ? [entry] : listed ? [] : [label],
    );
    this.#setAccountQueue(labelMade(this.accountQueue, change.seq, label.id));
    this.#relabelThreads(localId, label.id);
    const prefs = this.labelPrefDocs.get(localId);
    if (prefs !== undefined) {
      void writeLabelPrefs(
        this.#db,
        this.uid,
        label.id,
        prefs as LabelPrefsPatch,
      )
        .then(() => deleteLabelPrefs(this.#db, this.uid, localId))
        .catch((error) => console.error("label settings save failed", error));
    }
    if (this.view.kind === "label" && this.view.labelId === localId) {
      this.setView({ kind: "label", labelId: label.id });
    }
  }

  // Rename a label on shown threads and in queued thread changes, or drop it with `to` null.
  #relabelThreads(from: string, to: string | null): void {
    const swap = (ids: readonly string[]) =>
      to === null
        ? ids.filter((id) => id !== from)
        : ids.map((id) => (id === from ? to : id));
    this.#putSummaries(
      [...this.summaries.values()]
        .filter((thread) => thread.labelIds.includes(from))
        .map((thread) => ({ ...thread, labelIds: swap(thread.labelIds) })),
    );
    const queue = relabelChanges(this.queue, from, to);
    const changed = queue.filter(
      (change, index) => change !== this.queue[index],
    );
    this.queue = queue;
    this.#storeQueue(changed);
  }

  // Gmail refused a queued change for good: drop it and what depended on it, and say so.
  #refused(change: AccountChange, error: GmailError): void {
    if (change.kind === "createLabel") this.#madeIds.set(change.labelId, null);
    if (change.kind === "createFilter") {
      this.#madeIds.set(change.filterId, null);
    }
    this.#setAccountQueue(changeRefused(this.accountQueue, change.seq));
    if (change.kind === "createLabel") {
      this.labels = this.labels.filter((label) => label.id !== change.labelId);
      this.#relabelThreads(change.labelId, null);
    }
    const what =
      change.kind === "createLabel"
        ? `make the label “${change.name}”`
        : change.kind === "patchLabel"
          ? "change a label"
          : change.kind === "createFilter"
            ? "save a filter"
            : change.kind === "deleteFilter"
              ? "delete a filter"
              : "label the mail a filter matches";
    this.#showNotice(`Couldn't ${what}. Gmail said: ${error.message}`);
  }

  #showNotice(text: string): void {
    clearTimeout(this.#noticeTimer);
    this.notice = { text, expiresAt: Date.now() + NOTICE_MS };
    this.#noticeTimer = setTimeout(() => {
      this.notice = null;
    }, NOTICE_MS);
  }

  /** Thread ids matching a Gmail search, up to `MAX_BULK_THREADS`. */
  async threadIdsMatching(query: string): Promise<string[]> {
    const ids: string[] = [];
    let pageToken: string | null = null;
    do {
      const page = await this.gmail.listThreads({
        q: query,
        pageToken,
        maxResults: 100,
      });
      ids.push(...(page.threads ?? []).map((stub) => stub.id));
      pageToken = page.nextPageToken ?? null;
    } while (pageToken && ids.length < MAX_BULK_THREADS);
    return ids.slice(0, MAX_BULK_THREADS);
  }

  /**
   * Done all on a label group: its threads and its todos (`todoIds`; a repeating one makes its
   * next copy). When Gmail has more of the label's inbox threads than were fetched, every unpinned
   * one is listed by id (up to `MAX_BULK_THREADS`, no thread read) and archived with the shown
   * ones; undo puts them all back.
   */
  doneGroup = async (
    labelId: string,
    shown: readonly string[],
    todoIds: readonly string[] = [],
  ): Promise<void> => {
    const { ids } = await this.#groupSweep(labelId, shown);
    this.#act(this.#fromFor(ids), () => {
      const before = this.lastUndo;
      this.done(ids, [LABEL.inbox, labelId]);
      for (const id of todoIds) this.#stopEditing(id);
      this.#joinUndo(before, "done", this.todos.done(todoIds));
    });
  };

  /**
   * Pin all on a label group: every thread and todo Done all would take (see `doneGroup`), as one
   * undo. Pinned, they leave the bundle for the inbox.
   */
  pinGroup = async (
    labelId: string,
    shown: readonly string[],
    todoIds: readonly string[] = [],
  ): Promise<void> => {
    const { ids } = await this.#groupSweep(labelId, shown);
    this.#act(this.#fromFor(ids), () => {
      const before = this.lastUndo;
      this.setPinned(ids, true, [LABEL.inbox, labelId]);
      const undos = this.selectedTodos(todoIds)
        .filter((todo) => !todo.pinned)
        .flatMap((todo) => this.todos.togglePin(todo.id) ?? []);
      this.#joinUndo(
        before,
        "pin",
        undos.length === 0
          ? null
          : { before: undos.flatMap((undo) => undo.before), spawned: [] },
        true,
      );
    });
  };

  /**
   * Delete all on a label group: every thread Done all would take (see `doneGroup`) to the trash,
   * and its todos deleted, as one undo.
   */
  trashGroup = async (
    labelId: string,
    shown: readonly string[],
    todoIds: readonly string[] = [],
  ): Promise<void> => {
    const { ids } = await this.#groupSweep(labelId, shown);
    this.#act(this.#fromFor(ids), () => {
      const before = this.lastUndo;
      this.trash(ids, [LABEL.inbox, labelId]);
      for (const id of todoIds) this.#stopEditing(id);
      const kind = this.lastUndo !== before ? this.lastUndo!.kind : "delete";
      this.#joinUndo(before, kind, this.todos.remove(todoIds));
    });
  };

  /**
   * Snooze every thread and todo of a label group until `epoch`, as one pick and one undo: those
   * Done all would take. When Gmail has more of the label's inbox threads than were fetched, they
   * are listed by id (no thread read) and snoozed with the shown ones; the menu closes meanwhile.
   */
  snoozeGroup = async (
    labelId: string,
    shown: readonly string[],
    epoch: number,
    source: SnoozeSource,
    pickedKey?: string,
    todoIds: readonly string[] = [],
  ): Promise<void> => {
    if (this.bundleHasMore(labelId)) this.closeSnoozeMenu();
    const { ids, listed } = await this.#groupSweep(labelId, shown);
    this.#act(this.#fromFor(ids), () => {
      const before = this.lastUndo;
      this.#snoozeAll(ids, epoch, source, pickedKey, listed, [
        LABEL.inbox,
        labelId,
      ]);
      if (todoIds.length === 0 || epoch <= Date.now()) return;
      for (const id of todoIds) this.#stopEditing(id);
      const undo = this.todos.snooze(todoIds, epoch);
      if (undo === null) return;
      // The stats count one pick, made by the threads' snooze when there were any.
      const statsUndo =
        this.lastUndo !== before
          ? undefined
          : this.#stats.commit(Date.now(), epoch, source, pickedKey ?? null);
      this.#joinUndo(before, "snooze", undo, undefined, statsUndo);
      this.closeSnoozeMenu();
    });
  };

  // What Done all, or a swipe, on a label group acts on (`bundleSweepIds`), and the history ids of
  // the threads Gmail listed for it; listing waits only when Gmail has more than were fetched.
  async #groupSweep(
    labelId: string,
    shown: readonly string[],
  ): Promise<{ ids: string[]; listed: ReadonlyMap<string, string | null> }> {
    const swept = shown.map((id) => {
      const thread = this.#threadOf(id);
      return { id, pinned: thread !== undefined && isPinned(thread) };
    });
    let every: GmailThreadStub[] = [];
    let pinned: string[] = [];
    if (this.bundleHasMore(labelId)) {
      const inLabel = [LABEL.inbox, labelId];
      try {
        [every, pinned] = await Promise.all([
          this.#stubsListed(inLabel),
          this.#idsListed([...inLabel, LABEL.starred]),
        ]);
      } catch {
        // Tried again at the next refresh.
      }
    }
    return {
      ids: bundleSweepIds(
        swept,
        every.map((stub) => stub.id),
        pinned,
      ),
      listed: new Map(every.map((stub) => [stub.id, stub.historyId ?? null])),
    };
  }

  // Thread ids carrying every one of `labelIds`, up to `MAX_BULK_THREADS`, without reading one.
  async #idsListed(labelIds: string[]): Promise<string[]> {
    return (await this.#stubsListed(labelIds)).map((stub) => stub.id);
  }

  // Threads carrying every one of `labelIds`, as Gmail lists them, up to `MAX_BULK_THREADS`.
  async #stubsListed(labelIds: string[]): Promise<GmailThreadStub[]> {
    const stubs: GmailThreadStub[] = [];
    let pageToken: string | null = null;
    do {
      const page = await this.gmail.listThreads({
        labelIds,
        pageToken,
        maxResults: MAX_BULK_THREADS,
      });
      stubs.push(...(page.threads ?? []));
      pageToken = page.nextPageToken ?? null;
    } while (pageToken && stubs.length < MAX_BULK_THREADS);
    return stubs.slice(0, MAX_BULK_THREADS);
  }

  /** Queue label changes to every thread matching a search (a new filter applied to old mail); the search runs when the queue reaches it. */
  applyToMatching = (query: string, add: string[], remove: string[]): void => {
    if (add.length === 0 && remove.length === 0) return;
    this.#queueAccount([
      {
        kind: "labelMatching",
        query,
        add: this.#gmailIds(add),
        remove: this.#gmailIds(remove),
      },
    ]);
  };

  /** Open or close the label menu for a thread. */
  toggleLabelMenu = (id: string | null): void => {
    this.labelMenuFor = this.labelMenuFor === id ? null : id;
    if (id !== null) this.snoozeMenuFor = null;
  };

  /** Show Filters with the editor open on a new filter made from `editor` (a search or a sender). */
  editFilter = (editor: FilterEditor): void => {
    this.openFilterEditor(editor, null);
    this.setView({ kind: "settings", section: "filters" });
  };

  /** Open the filter editor, fresh, on `editor`: a new filter (id null) or Gmail filter `id`. */
  openFilterEditor = (editor: FilterEditor, id: string | null): void => {
    this.filterEditing = {
      id,
      editor,
      opening: (this.filterEditing?.opening ?? 0) + 1,
    };
  };

  /** Close the filter editor. */
  closeFilterEditor = (): void => {
    this.filterEditing = null;
  };

  /** Pin or unpin (Gmail's star). */
  togglePin = (id: string): void => {
    this.#act(this.#fromFor([id]), () => {
      const items = this.#undoItems(id);
      if (items.length === 0) return;
      const pinned = this.#threadOf(id)?.labelIds.includes(LABEL.starred);
      if (pinned) this.#change(id, [], [LABEL.starred]);
      else this.#change(id, [LABEL.starred], []);
      this.#setUndo({ kind: "pin", items, pinned: !pinned });
    });
  };

  /** Mark a thread important or not (Gmail's IMPORTANT label). */
  setImportant = (id: string, important: boolean): void => {
    const thread = this.#threadOf(id);
    if (!thread) return;
    const { add, remove } = importantChange(thread.labelIds, important);
    if (add.length === 0 && remove.length === 0) return;
    this.#act(this.#fromFor([id]), () => this.#change(id, add, remove));
  };

  /** Snooze until `epoch`: save it in Firestore, as this device's wall time. False when `epoch` already passed. */
  snooze = (
    id: string,
    epoch: number,
    source: SnoozeSource,
    pickedKey?: string,
  ): boolean => this.snoozeAll([id], epoch, source, pickedKey);

  /** Snooze several threads until `epoch`, as one pick and one undo. False when `epoch` already passed. */
  snoozeAll = (
    ids: readonly string[],
    epoch: number,
    source: SnoozeSource,
    pickedKey?: string,
  ): boolean => {
    let snoozed = false;
    this.#act(this.#fromFor(ids), () => {
      snoozed = this.#snoozeAll(ids, epoch, source, pickedKey);
    });
    return snoozed;
  };

  // Threads not read yet that Gmail `listed` (with their history ids) count for undo as having
  // `assumed` labels; others not read are skipped.
  #snoozeAll(
    ids: readonly string[],
    epoch: number,
    source: SnoozeSource,
    pickedKey: string | undefined,
    listed: ReadonlyMap<string, string | null> = new Map(),
    assumed: readonly string[] = [],
  ): boolean {
    const asked = ids.flatMap((id): UndoItem[] => {
      const known = this.#undoItems(id);
      return known.length > 0 || !listed.has(id)
        ? known
        : [
            {
              id,
              row: id,
              labelIds: [...assumed],
              snooze: this.snoozeDocs.get(id),
            },
          ];
    });
    // Ids Gmail listed for a bundle can name a joined row's other threads as well.
    const seen = new Set<string>();
    const items = asked.filter(
      (item) => !seen.has(item.id) && seen.add(item.id),
    );
    if (items.length === 0) return false;
    if (epoch <= Date.now()) {
      this.refreshNow();
      return false;
    }
    const snoozed = items.map((item) => item.id);
    this.#closeIfOpen(snoozed);
    this.#setSnoozes(
      snoozed.map((id) => [
        id,
        {
          snoozeUntil: epoch,
          snoozeWatermark:
            this.summaries.get(id)?.historyId ?? listed.get(id) ?? null,
        },
      ]),
      true,
    );
    if (snoozed.some((id) => this.woken.has(id))) {
      this.woken = new Set(
        [...this.woken].filter((id) => !snoozed.includes(id)),
      );
    }
    if (snoozed.some((id) => this.skippedInbox.has(id))) {
      this.skippedInbox = new Set(
        [...this.skippedInbox].filter((id) => !snoozed.includes(id)),
      );
    }
    // A snoozed thread stays in the inbox, hidden here until its time; one snoozed from
    // elsewhere joins it, so it comes back there.
    const outside = items.filter(
      (item) => !item.labelIds.includes(LABEL.inbox),
    );
    if (outside.length > 0) {
      this.#changeAll(
        outside.map((item) => ({
          threadId: item.id,
          add: [LABEL.inbox],
          remove: [],
        })),
      );
    }
    const statsUndo = this.#stats.commit(
      Date.now(),
      epoch,
      source,
      pickedKey ?? null,
    );
    this.#setUndo({ kind: "snooze", items, statsUndo });
    this.snoozeMenuFor = null;
    return true;
  }

  /** End a snooze now and put the thread back in the inbox. */
  wakeNow = (id: string): void => {
    this.wakeAll([id]);
  };

  /** End several snoozes now and put the threads back in the inbox. */
  wakeAll = (ids: readonly string[]): void => {
    this.#act(this.#fromFor(ids), () => this.#wakeAll(ids));
  };

  #wakeAll(ids: readonly string[]): void {
    this.#setSnoozes(
      ids.map((id) => [id, null]),
      true,
    );
    const outside = ids.filter(
      (id) => !this.#threadOf(id)?.labelIds.includes(LABEL.inbox),
    );
    if (outside.length > 0) {
      this.#changeAll(
        outside.map((threadId) => ({
          threadId,
          add: [LABEL.inbox],
          remove: [],
        })),
      );
    }
  }

  /**
   * Pin (or unpin) several threads, as one undo. A thread not read yet counts for undo as having
   * `assumed` labels, or is skipped without them.
   */
  setPinned = (
    ids: readonly string[],
    pinned: boolean,
    assumed?: readonly string[],
  ): void => {
    this.#act(this.#fromFor(ids), () => {
      const items = this.#undoItemsAssuming(ids, assumed);
      if (items.length === 0) return;
      this.#changeAll(
        items.map((item) => ({
          threadId: item.id,
          add: pinned ? [LABEL.starred] : [],
          remove: pinned ? [] : [LABEL.starred],
        })),
      );
      this.#setUndo({ kind: "pin", items, pinned });
    });
  };

  /** Mark threads read, or unread (queued while offline). */
  setUnread = (ids: readonly string[], unread: boolean): void => {
    this.#act(this.#fromFor(ids), () => {
      // Opening a thread reads it, so one marked unread goes back to the list.
      if (unread) this.#closeIfOpen(ids);
      this.#changeWithUndo(
        unread ? "unread" : "read",
        ids.map((threadId) => ({
          threadId,
          add: unread ? [LABEL.unread] : [],
          remove: unread ? [] : [LABEL.unread],
        })),
      );
    });
  };

  /** Apply one of the selection bar's actions to every selected thread and todo it fits. */
  actOnSelection = (action: SelectionBarAction): void => {
    this.#act(
      "selection",
      () => this.#actOnSelection(action),
      () =>
        action === "delete" ||
        selectionAfter(action === "inbox" ? "done" : action) === "clear",
    );
  };

  /** Done and Move to inbox across the selected threads (see `inboxPlan`). */
  selectionInboxPlan(): { done: string[]; inbox: string[] } {
    return inboxPlan(
      this.selectedThreads(this.selectedThreadIds).map((thread) => ({
        id: thread.id,
        labelIds: thread.labelIds,
        snoozed: thread.status === "snoozed",
      })),
    );
  }

  #actOnSelection(action: SelectionBarAction): void {
    const threads = this.selectedThreads(this.selectedThreadIds);
    const plan = selectionPlan(threads);
    const todoPlan = todoSelectionPlan(
      threads,
      this.selectedTodos(this.selectedTodoIds),
    );
    switch (action) {
      case "done": {
        const before = this.lastUndo;
        this.done(this.selectionInboxPlan().done);
        for (const id of todoPlan.done) this.#stopEditing(id);
        this.#joinUndo(before, "done", this.todos.done(todoPlan.done));
        break;
      }
      case "inbox":
        this.moveToInbox(this.selectionInboxPlan().inbox);
        break;
      case "wake":
        this.wakeAll(plan.wake);
        break;
      case "pin": {
        const before = this.lastUndo;
        this.setPinned(todoPlan.pin.threads, todoPlan.pin.on);
        const undos = todoPlan.pin.todos.flatMap(
          (id) => this.todos.togglePin(id) ?? [],
        );
        this.#joinUndo(
          before,
          "pin",
          undos.length === 0
            ? null
            : {
                before: undos.flatMap((undo) => undo.before),
                spawned: [],
              },
          todoPlan.pin.on,
        );
        break;
      }
      case "unread":
        this.setUnread(plan.unread.ids, plan.unread.on);
        break;
      case "spam":
        if (plan.spam.on) this.markSpam(plan.spam.ids);
        else this.notSpam(plan.spam.ids);
        break;
      case "delete":
        this.deleteTodos(this.selectedTodoIds);
        break;
    }
  }

  /** Snooze every selected thread and open todo until `epoch`; false when `epoch` already passed. */
  snoozeSelection = (
    epoch: number,
    source: SnoozeSource,
    pickedKey?: string,
  ): boolean => {
    let snoozed = false;
    this.#act(
      "selection",
      () => {
        snoozed = this.#snoozeSelection(epoch, source, pickedKey);
      },
      () => snoozed && selectionAfter("snooze") === "clear",
    );
    return snoozed;
  };

  #snoozeSelection(
    epoch: number,
    source: SnoozeSource,
    pickedKey: string | undefined,
  ): boolean {
    const todoIds = todoSelectionPlan(
      [],
      this.selectedTodos(this.selectedTodoIds),
    ).snooze;
    const before = this.lastUndo;
    let snoozed = this.snoozeAll(
      this.selectedThreadIds,
      epoch,
      source,
      pickedKey,
    );
    if (todoIds.length > 0 && epoch > Date.now()) {
      for (const id of todoIds) this.#stopEditing(id);
      const undo = this.todos.snooze(todoIds, epoch);
      if (undo) {
        // The stats count one pick, made by the threads' snooze when there were any.
        const statsUndo =
          this.lastUndo !== before
            ? undefined
            : this.#stats.commit(Date.now(), epoch, source, pickedKey ?? null);
        this.#joinUndo(before, "snooze", undo, undefined, statsUndo);
        this.closeSnoozeMenu();
        snoozed = true;
      }
    }
    return snoozed;
  }

  // Fold an action's todo changes into the undo its threads just set, or start one for them alone.
  #joinUndo(
    before: UndoEntry | null,
    kind: UndoEntry["kind"],
    todos: TodoUndo | null,
    pinned?: boolean,
    statsUndo?: SnoozeUndo,
  ): void {
    if (todos === null) return;
    const threads = this.lastUndo !== before ? this.lastUndo : null;
    this.#setUndo({
      kind,
      items: threads?.items ?? [],
      todos: {
        before: [...(threads?.todos?.before ?? []), ...todos.before],
        spawned: [...(threads?.todos?.spawned ?? []), ...todos.spawned],
      },
      statsUndo: threads?.statsUndo ?? statsUndo,
      pinned: threads?.pinned ?? pinned,
    });
  }

  /** Todos as the selection bar's rules see them. */
  selectedTodos(ids: readonly string[]): SelectedTodo[] {
    return ids.flatMap((id) => {
      const todo = this.todos.get(id);
      if (!todo) return [];
      return [{ id, done: todo.state === "DONE", pinned: todo.pinned }];
    });
  }

  /** End the selection when `action` is one that ends it (see `selectionAfter`). */
  settleSelection = (action: SetAction): void => {
    if (selectionAfter(action) === "clear") {
      this.dispatch({ type: "clearSelection" });
    }
  };

  /** Threads as the selection bar's rules see them. */
  selectedThreads(ids: readonly string[]): SelectedThread[] {
    const inSnoozed = this.view.kind === "snoozed";
    return ids.flatMap((id) => {
      const thread = this.#threadOf(id);
      if (!thread) return [];
      const status = inSnoozed ? "snoozed" : this.statusOf(id);
      return [
        {
          id,
          labelIds: thread.labelIds,
          status: status === "woken" ? "none" : status,
        },
      ];
    });
  }

  /** Take back the last action the undo pill offers. */
  undo = (): void => {
    const entry = this.lastUndo;
    if (!entry) return;
    this.dispatch({ type: "undo" });
    if (entry.todos) this.todos.restore(entry.todos);
    const changes: NewLabelChange[] = [];
    const snoozes: [string, SnoozeDoc | null][] = [];
    // A label made since the action is on its threads under Gmail's id by now.
    const restore = this.#gmailIds(entry.labels ?? undoLabels(entry.kind, []));
    for (const item of entry.items) {
      const current = this.summaries.get(item.id);
      const { add, remove } = undoChange(
        item.labelIds,
        current?.labelIds ?? [],
        restore,
      );
      // Even an empty change names its thread, so its row's lead's change doesn't reach it.
      changes.push({ threadId: item.id, add, remove });
      if (restoresSnooze(entry.kind)) {
        snoozes.push([item.id, item.snooze ?? null]);
      }
    }
    this.#setSnoozes(snoozes);
    this.#changeAll(changes);
    if (entry.statsUndo) this.#stats.undo(entry.statsUndo);
    this.#clearUndo();
  };

  /** "Done all" on a section: its unpinned threads and todos, as one undo. */
  doneSection = (key: string): void => {
    const section = this.sections.find((candidate) => candidate.key === key);
    if (section === undefined) return;
    this.#act("row", () => {
      const todoIds = doneAllTodos(section).map((todo) => todo.id);
      const previous = this.lastUndo;
      this.done(doneAllIds(section));
      this.#joinUndo(previous, "done", this.todos.done(todoIds));
    });
  };

  /** The todo of an email (a row's Gmail threads, lead first), or null when it has none. */
  emailTodoOf = (threadIds: readonly string[]): EmailTodo | null =>
    this.todos.enabled ? rowEmailTodo(this.todos.byThread, threadIds) : null;

  /** Open an email's todo to edit; an email without one gets a new one, saved once it has text. */
  editThreadTodo = (threadId: string): void => {
    if (this.todos.enabled) {
      this.todos.startEmail(this.#joins.membersOf(threadId));
    }
  };

  /**
   * A new todo, open to edit where it will live: at the top of the inbox's list (or the Todos view's)
   * and scrolled into view. It is saved once it has text, and gone if left empty.
   */
  newTodo = (): void => {
    if (!this.todos.enabled) return;
    if (this.view.kind !== "inbox" && this.view.kind !== "todos") {
      this.setView({ kind: "inbox" });
    }
    this.#clearUndo();
    this.#revealTodo(this.todos.start());
  };

  /**
   * Whether Enter in the todo `id`, once it has text, starts the next one above it
   * ({@link newTodoAbove}): not on one done or snoozed (nor on an email's), and only where the next
   * one shows, the inbox and the Todos view.
   */
  startsTodoAbove = (id: string): boolean => {
    const todo = this.todos.get(id);
    return (
      todo !== undefined &&
      matchesFilter(todo, "ACTIVE", this.now) &&
      (this.view.kind === "inbox" || this.view.kind === "todos")
    );
  };

  /**
   * Enter in a todo with text: a new todo just above it, the newer, open to edit, as
   * {@link newTodo}. False when it can't have one there (done, snoozed, on an email, or in a list
   * that wouldn't show it), so Enter only ends the editing.
   */
  newTodoAbove = (id: string): boolean => {
    if (!this.startsTodoAbove(id)) return false;
    // Its edit ends first, so the new one goes above where the edit leaves it.
    this.#stopEditing(id);
    const todo = this.todos.get(id);
    if (!todo) return false;
    const newId = crypto.randomUUID();
    const position = positionAbove(activeKey(todo), newId);
    this.#revealTodo(
      this.todos.start({ id: newId, position, pinned: todo.pinned }),
    );
    return true;
  };

  #revealTodo(id: string): void {
    this.dispatch({ type: "reveal", key: todoRowKey(id) });
  }

  /** Mark a todo done, or make a done one active again. */
  toggleTodoDone = (id: string): void => {
    const todo = this.todos.get(id);
    if (!todo) return;
    this.#stopEditing(id);
    this.#act("row", () => {
      if (todo.state === "DONE") {
        this.todos.reactivate([id]);
      } else {
        const undo = this.todos.done([id]);
        if (undo) this.#setUndo({ kind: "done", items: [], todos: undo });
      }
    });
  };

  /** End a todo's snooze now; a repeating one stops repeating. */
  wakeTodo = (id: string): void => {
    this.#act("row", () => {
      const todo = this.todos.get(id);
      if (todo && (todo.state === "DONE" || isSnoozed(todo, Date.now()))) {
        this.todos.reactivate([id]);
      }
    });
  };

  /** Snooze a todo until `epoch`, as one pick of the snooze menu. False when `epoch` already passed. */
  snoozeTodo = (
    id: string,
    epoch: number,
    source: SnoozeSource,
    pickedKey?: string,
  ): boolean => {
    if (epoch <= Date.now()) {
      this.refreshNow();
      return false;
    }
    this.#stopEditing(id);
    let snoozed = false;
    this.#act("row", () => {
      const undo = this.todos.snooze([id], epoch);
      if (!undo) return;
      const statsUndo = this.#stats.commit(
        Date.now(),
        epoch,
        source,
        pickedKey ?? null,
      );
      this.#setUndo({ kind: "snooze", items: [], todos: undo, statsUndo });
      this.closeSnoozeMenu();
      snoozed = true;
    });
    return snoozed;
  };

  /** Give a todo a repeat rule, from the snooze menu. */
  setTodoRepeat = (id: string, draft: RepeatDraft): void => {
    this.#act("row", () => this.todos.setRepeat(id, draft));
    this.closeSnoozeMenu();
  };

  /** Pin or unpin a todo. */
  toggleTodoPin = (id: string): void => {
    const todo = this.todos.get(id);
    const pinned = todo?.pinned;
    this.#stopEditing(id);
    this.#act("row", () => {
      const undo = this.todos.togglePin(id);
      if (undo) {
        this.#setUndo({
          kind: "pin",
          items: [],
          todos: undo,
          pinned: !pinned,
        });
      }
    });
  };

  /** Delete todos. */
  deleteTodos = (ids: readonly string[]): void => {
    for (const id of ids) this.#stopEditing(id);
    this.#act("row", () => {
      const undo = this.todos.remove(ids);
      if (undo) this.#setUndo({ kind: "delete", items: [], todos: undo });
    });
  };

  // An action that moves or changes the todo being edited closes its editor.
  #stopEditing(id: string): void {
    if (this.todos.editingId === id) this.todos.setEditing(null);
  }

  /** Open the snooze menu for a thread. */
  openSnoozeMenu = (id: string, step: SnoozeMenuStep = "menu"): void => {
    this.labelMenuFor = null;
    this.snoozeMenuFor = id;
    this.snoozeMenuStep = step;
  };

  /** Close the snooze menu. */
  closeSnoozeMenu = (): void => {
    this.snoozeMenuFor = null;
    this.snoozeMenuStep = "menu";
  };

  /**
   * Open a thread in place, from outside the list (a link, history, a reply); a bundle stays open
   * only if the thread is in it. `inPlace` swaps it for the open thread (a sideways swipe), with
   * nothing growing or shrinking (see `openedInPlace`).
   */
  open = (id: string, inPlace = false): void => {
    this.#openingInPlace = inPlace;
    try {
      this.dispatch({ type: "openThread", id: this.rowOf(id) });
    } finally {
      this.#openingInPlace = false;
    }
  };

  /** Call `listener` with each row that opens; returns the function that stops it. */
  onOpen(listener: (id: string) => void): () => void {
    this.#openListeners.add(listener);
    return () => this.#openListeners.delete(listener);
  }

  /** Open row `id`'s messages once they show; null when it closes or another row opens first. */
  detailShown(id: string): Promise<ThreadDetail | null> {
    if (this.detail?.id === id) return Promise.resolve(this.detail);
    if (this.openId !== id || this.#disposed) return Promise.resolve(null);
    return new Promise((resolve) => this.#detailWaits.push({ id, resolve }));
  }

  // Answer the `detailShown` waits the open row's messages or its close settled.
  #settleDetailWaits(): void {
    const detail = this.detail;
    const open = this.#disposed ? null : this.openId;
    this.#detailWaits = this.#detailWaits.filter((wait) => {
      if (detail !== null && detail.id === wait.id && open === wait.id) {
        wait.resolve(detail);
      } else if (open !== wait.id) {
        wait.resolve(null);
      } else {
        return true;
      }
      return false;
    });
  }

  /** Open a thread from a notification: show the inbox, fetch it fresh, and open the thread there. */
  openFromNotification = async (id: string): Promise<void> => {
    if (this.view.kind !== "inbox") this.setView({ kind: "inbox" });
    this.open(id);
    await this.refresh();
  };

  /** Change a label's settings for every device on this account. */
  setLabelPrefs(labelId: string, patch: LabelPrefsPatch): Promise<void> {
    return writeLabelPrefs(this.#db, this.uid, labelId, patch);
  }

  /** Fetch the open thread again after it failed to load. */
  retryDetail = (): void => {
    const id = this.openId;
    if (id === null) return;
    this.detailError = null;
    void this.#loadDetail(id, false);
  };

  /** Close the open thread. */
  close = (): void => {
    this.dispatch({ type: "closeThread" });
  };

  /**
   * The saved copy of row `id`, read from the device without asking Gmail (and held, so opening it
   * shows at once), or null when it isn't saved. Reading it marks nothing read.
   */
  savedDetail = async (id: string): Promise<ThreadDetail | null> => {
    const saved = await this.#savedBodies
      .load(this.#joins.membersOf(id))
      .catch(() => null);
    return saved === null || this.#disposed
      ? null
      : this.#withShown(id, this.#rowLabelsOver(id, saved));
  };

  /** Read the saved bodies of a row likely to open next (pointed at, pressed or focused), so it opens at once. */
  prefetchDetail = (id: string): void => {
    this.#savedBodies.prefetch(this.#joins.membersOf(id));
  };

  // A newly open row shows from memory in the same step when its saved bodies are held there;
  // else it loads.
  #openDetail(id: string): void {
    clearTimeout(this.#detailRetry);
    openStarted(id);
    const held = this.#savedBodies.peek(this.#joins.membersOf(id));
    if (held === null) void this.#loadDetail(id, true);
    else {
      openFrom("memory");
      openMark("saved bodies held in memory");
      this.#showSaved(id, held);
      afterPaint(() => this.#openedSaved(id, held));
    }
  }

  // A row whose Gmail threads are all saved shows from the device and asks Gmail nothing: the
  // saved copy's history sync keeps it current (see `#saveRead`, `#appendSaved`, `#relabelSaved`).
  async #loadDetail(id: string, fromCache: boolean): Promise<void> {
    if (fromCache) {
      openMark("reading saved copy");
      const saved = await this.#savedBodies
        .load(this.#joins.membersOf(id))
        .catch(() => null);
      openMark(saved === null ? "not saved" : "saved copy read");
      if (this.#disposed) return;
      if (saved !== null) {
        openFrom("saved copy");
        if (this.openId === id && this.detail === null) {
          this.#showSaved(id, saved);
          afterPaint(() => this.#openedSaved(id, saved));
        }
        return;
      }
    }
    try {
      // A joined row shows the messages of each of its Gmail threads, but those gone from Gmail;
      // only the lead's failure fails the load.
      const asked = this.#joins.membersOf(id);
      openFrom("Gmail");
      openMark("asking Gmail");
      const results = await Promise.allSettled(
        asked.map((member) => this.gmail.getThread(member, "full")),
      );
      openMark("Gmail answered");
      if (this.#disposed) return;
      const [lead] = results;
      if (lead?.status === "rejected") throw lead.reason;
      const members: string[] = [];
      const threads: GmailThread[] = [];
      results.forEach((result, index) => {
        const member = asked[index]!;
        if (result.status === "fulfilled") {
          members.push(member);
          threads.push(result.value);
        } else if (
          result.reason instanceof GmailError &&
          result.reason.status === 404
        ) {
          this.#removeGone([member]);
        } else {
          console.warn("joined thread load failed", member, result.reason);
        }
      });
      if (this.openId === id) this.#openMembers = members;
      const plains = threads.map(threadDetail);
      const seen = new Set(
        this.detail?.id === id
          ? this.detail.messages.map((message) => message.id)
          : [],
      );
      const detail = this.#withShown(id, joinDetails(plains));
      if (this.openId === id) {
        this.detail = detail;
        this.detailError = null;
        openMarkDrawn("messages drawn");
        this.#settleDetailWaits();
      }
      this.#putSummaries(
        threads.map((thread) => this.#withQueued(summarizeThread(thread))),
      );
      if (
        this.openId === id &&
        detail.messages.some((message) => message.unread)
      ) {
        this.#change(id, [], [LABEL.unread]);
      }
      if (this.openId === id) void this.#decryptOpen(id);
      for (const thread of threads) void this.#noteAutocrypt(thread, seen);
      // The body is saved before the thread counts as saved, so opening it again reads nothing.
      await Promise.all(
        plains.map((plain) =>
          this.#keepDetail(threadPart(detail, plain.id, plain)),
        ),
      );
      await this.savedMail.noteRead(threads, true);
    } catch (error) {
      if (this.#disposed) return;
      if (!isNetworkError(error)) console.error("thread load failed", error);
      if (this.openId === id && this.detail === null) {
        this.detailError = isNetworkError(error)
          ? "This thread isn't saved on this device yet. It opens once you're back online."
          : errorText(error, this.gmail.gate.pausedUntil);
        if (isRateLimited(error)) this.#retryDetailAfterPause(id);
      }
    }
  }

  // Load the open thread again once Gmail's pause ends, as its error says; opening another
  // thread or closing this one cancels it.
  #retryDetailAfterPause(id: string): void {
    clearTimeout(this.#detailRetry);
    const wait = Math.max(
      this.gmail.gate.pausedUntil - Date.now(),
      FIRST_BACKOFF_MS,
    );
    this.#detailRetry = setTimeout(() => {
      if (!this.#disposed && this.openId === id && this.detail === null) {
        void this.#loadDetail(id, false);
      }
    }, wait);
  }

  #showSaved(id: string, saved: ThreadDetail): void {
    if (this.openId !== id) return;
    for (const message of saved.messages) {
      if (message.pgp?.status === "decrypted") {
        this.#decrypted.set(message.id, message);
      }
    }
    this.detail = this.#withShown(id, this.#rowLabelsOver(id, saved));
    this.detailError = null;
    openMarkDrawn("messages drawn");
    this.#settleDetailWaits();
  }

  // What opening a saved row does besides showing it: it counts as opened, and is marked read.
  // Done once the thread has drawn, which it doesn't wait on.
  #openedSaved(id: string, saved: ThreadDetail): void {
    if (this.#disposed || this.openId !== id) return;
    openTimed("opened and marked read", () => this.#noteOpenedSaved(id, saved));
  }

  #noteOpenedSaved(id: string, saved: ThreadDetail): void {
    const members = this.#joins.membersOf(id);
    void this.#cache.touch("threads", members).catch(() => undefined);
    for (const member of members) void this.savedMail.noteOpened(member);
    const row = this.#threadOf(id);
    const unread = row
      ? row.labelIds.includes(LABEL.unread)
      : saved.messages.some((message) => message.unread);
    if (unread) this.#change(id, [], [LABEL.unread]);
    void this.#decryptOpen(id);
  }

  // The open row again from the saved copy, once its sync changed a saved body; new mail in it is
  // marked read, as the open thread's is.
  async #reshowSaved(newMail: boolean): Promise<void> {
    const id = this.openId;
    if (id === null) return;
    const saved = await this.#savedBodies
      .load(this.#joins.membersOf(id))
      .catch(() => null);
    if (
      this.#disposed ||
      saved === null ||
      this.openId !== id ||
      this.detail?.id !== id
    ) {
      return;
    }
    this.detail = this.#withShown(id, this.#rowLabelsOver(id, saved));
    if (newMail && this.detail.messages.some((message) => message.unread)) {
      this.#change(id, [], [LABEL.unread]);
    }
    void this.#decryptOpen(id);
  }

  // A saved body with its row's read state laid over it: the row has the changes queued here,
  // which history brings to the saved body only once Gmail has them.
  #rowLabelsOver(id: string, saved: ThreadDetail): ThreadDetail {
    const row = this.#threadOf(id);
    if (
      row === undefined ||
      row.labelIds.includes(LABEL.unread) ||
      !saved.messages.some((message) => message.unread)
    ) {
      return saved;
    }
    return {
      ...saved,
      messages: saved.messages.map((message) =>
        message.unread ? { ...message, unread: false } : message,
      ),
    };
  }

  // `fresh` for open row `id` with what this session decrypted laid over it; messages the screen
  // already shows stay the same objects, so their views don't redraw.
  #withShown(id: string, fresh: ThreadDetail): ThreadDetail {
    const shown = new Map(
      this.detail?.id === id
        ? this.detail.messages.map((message) => [message.id, message])
        : [],
    );
    return {
      ...fresh,
      messages: fresh.messages.map((message) => {
        const known = this.#decrypted.get(message.id);
        const failed = this.#unopenable.get(message.id);
        const next = known
          ? { ...known, unread: message.unread }
          : failed && message.pgp
            ? { ...message, pgp: { status: failed, signature: null } }
            : message;
        const previous = shown.get(message.id);
        return previous !== undefined &&
          previous.unread === next.unread &&
          previous.pgp?.status === next.pgp?.status
          ? previous
          : next;
      }),
    };
  }

  // The offline copy, each Gmail thread under its own id, with decrypted messages sealed under the
  // device key. Messages decrypted before but not now (a thread the saved copy read again) keep
  // their sealed copy. A joined row's other threads keep their own subjects.
  async #keepDetail(detail: ThreadDetail): Promise<void> {
    const ids = new Set([
      detail.id,
      ...[...detail.messages, ...detail.drafts].map(
        (message) => message.threadId,
      ),
    ]);
    await Promise.all([...ids].map((id) => this.#keepThread(detail, id)));
  }

  async #keepThread(detail: ThreadDetail, id: string): Promise<void> {
    try {
      const stored = await this.#cache
        .get("threads", id)
        .catch(() => undefined);
      const part = threadPart(
        detail,
        id,
        id === detail.id || stored === undefined ? detail : stored,
      );
      const sealed = await sealDetail(part, this.encryption.deviceKey());
      const before = new Map(
        (stored?.messages ?? []).flatMap((message) =>
          message.pgp?.sealed === undefined ? [] : [[message.id, message]],
        ),
      );
      const messages = sealed.messages.map((message) => {
        const kept = before.get(message.id);
        return kept !== undefined &&
          message.pgp !== undefined &&
          message.pgp.sealed === undefined
          ? { ...kept, unread: message.unread }
          : message;
      });
      await this.#cache.put("threads", id, { ...sealed, messages });
      this.#savedBodies.forget([id]);
    } catch (error) {
      console.error("thread cache write failed", error);
    }
  }

  // Senders' keys from the Autocrypt headers of plain mail that isn't spam. Encrypted messages
  // give theirs only after their signature was checked against the key we had before.
  async #noteAutocrypt(
    thread: GmailThread,
    seen: ReadonlySet<string>,
  ): Promise<void> {
    for (const message of thread.messages ?? []) {
      if (seen.has(message.id) || message.labelIds?.includes(LABEL.spam)) {
        continue;
      }
      const headers = message.payload?.headers ?? [];
      const values = headers
        .filter((header) => header.name.toLowerCase() === "autocrypt")
        .map((header) => header.value);
      if (values.length === 0) continue;
      const from = headers.find(
        (header) => header.name.toLowerCase() === "from",
      );
      const view = threadDetail({ id: thread.id, messages: [message] })
        .messages[0];
      if (view === undefined || view.pgp !== undefined || !from) continue;
      await this.encryption
        .noteAutocrypt(values, view.from.email, view.date)
        .catch(() => {});
    }
  }

  #updateMessage(threadId: string, message: MessageView): void {
    const detail = this.detail;
    if (detail?.id !== threadId) return;
    this.detail = {
      ...detail,
      messages: detail.messages.map((entry) =>
        entry.id === message.id ? message : entry,
      ),
    };
  }

  async #forgetDecrypted(): Promise<void> {
    this.#decrypted.clear();
    this.#savedBodies.clear();
    this.#unopenable.clear();
    this.#searchDetails.clear();
    this.#encryptedRun++;
    this.#encryptedProgress.clear();
    this.encryptedSearch = null;
    let detail = this.detail;
    while (
      detail !== null &&
      !this.encryption.unlocked &&
      detail.messages.some(
        (message) =>
          message.pgp?.status === "decrypted" ||
          message.pgp?.status === "decrypting",
      )
    ) {
      const locked = await sealDetail(detail, null);
      if (this.detail === detail) {
        if (!this.encryption.unlocked) this.detail = locked;
        return;
      }
      detail = this.detail;
    }
  }

  // Decrypt the open thread's encrypted messages that aren't yet.
  async #decryptOpen(threadId: string): Promise<void> {
    const detail = this.detail;
    if (detail?.id !== threadId) return;
    const waiting = detail.messages.filter(
      (message) =>
        message.pgp !== undefined &&
        message.pgp.status !== "decrypted" &&
        message.pgp.status !== "wrong-key" &&
        message.pgp.status !== "malformed" &&
        !this.#decrypting.has(message.id),
    );
    if (waiting.length === 0 || !this.encryption.unlocked) return;
    for (const message of waiting) {
      this.#decrypting.add(message.id);
      this.#updateMessage(threadId, {
        ...message,
        pgp: { status: "decrypting", signature: null },
      });
    }
    let changed = false;
    for (const message of waiting) {
      try {
        const opened = await this.#decryptMessage(message);
        // Locked meanwhile: keep nothing decrypted.
        if (!this.encryption.unlocked) break;
        this.#decrypted.set(message.id, opened);
        this.#updateMessage(threadId, opened);
        changed = true;
      } catch (error) {
        const status = pgpFailure(error);
        if (status === "malformed") console.error("decrypt failed", error);
        if (status === "malformed" || status === "wrong-key") {
          this.#unopenable.set(message.id, status);
        }
        this.#updateMessage(threadId, {
          ...message,
          pgp: { status, signature: null },
        });
      } finally {
        this.#decrypting.delete(message.id);
      }
    }
    for (const message of waiting) this.#decrypting.delete(message.id);
    const current = this.detail;
    if (!changed || current?.id !== threadId) return;
    const subject = decryptedSubject(current);
    if (subject !== undefined) this.encryption.noteSubject(threadId, subject);
    void this.#keepDetail(current);
  }

  /** Fetch one encrypted message as sent and decrypt it; throws `PgpError` or network errors. */
  async #decryptMessage(message: MessageView): Promise<MessageView> {
    const pgp = await loadPgp();
    const { raw } = await this.gmail.getMessage(message.id, "raw");
    if (!raw) throw new Error("no raw message");
    const bytes = decodeBase64Url(raw);
    const decrypted = await this.encryption.open(bytes, message.from.email);
    if (decrypted.signature.status !== "valid") {
      const outer = pgp.parseMime(binaryString(bytes));
      const values = outer.headers
        .filter((field) => field.name.toLowerCase() === "autocrypt")
        .map((field) => field.value);
      await this.encryption
        .noteAutocrypt(values, message.from.email, message.date)
        .catch(() => {});
    }
    return decryptedView(message, decrypted, pgp);
  }

  // Look inside encrypted mail for the search's words, a page at a time, while the search shows.
  // Each load of the search starts a run that checks only the messages no run checked yet, so
  // mail that arrived since is looked at, and the matches found so far are listed again at once.
  async #searchEncrypted(query: string, key: string): Promise<void> {
    const search = encryptedSearch(searchPlan(query));
    if (search === null || !this.encryption.unlocked) {
      this.encryptedSearch = null;
      return;
    }
    let progress = this.#encryptedProgress.get(key);
    if (progress === undefined) {
      progress = { checked: new Set(), matches: new Set() };
      this.#encryptedProgress.set(key, progress);
    }
    const run = ++this.#encryptedRun;
    const still = () =>
      this.#encryptedRun === run && viewKey(this.view) === key;
    this.encryptedSearch = { key, running: true, matches: progress.matches };
    this.#listEncryptedMatches(key, progress.matches);
    let pageToken: string | null = null;
    let decrypted = 0;
    try {
      do {
        const page = await this.gmail.listMessages(
          search.query,
          pageToken,
          ENCRYPTED_SEARCH_PAGE,
        );
        pageToken = page.nextPageToken ?? null;
        for (const { id, threadId } of uncheckedMessages(
          page.messages ?? [],
          progress,
        )) {
          if (!still() || decrypted >= ENCRYPTED_SEARCH_LIMIT) break;
          // An earlier message of this page may have matched its thread.
          if (progress.matches.has(threadId)) continue;
          decrypted++;
          const found = await this.#encryptedMatch(id, search.terms);
          progress.checked.add(id);
          if (!found) continue;
          progress.matches = new Set([...progress.matches, threadId]);
          if (!still()) break;
          this.encryptedSearch = {
            key,
            running: true,
            matches: progress.matches,
          };
          if (!this.summaries.has(threadId)) {
            await this.#fetchSummaries([{ id: threadId }], "visible");
          }
          this.#listEncryptedMatches(key, [threadId]);
        }
      } while (
        pageToken !== null &&
        still() &&
        decrypted < ENCRYPTED_SEARCH_LIMIT
      );
    } catch (error) {
      if (!isNetworkError(error))
        console.error("encrypted search failed", error);
    }
    if (this.#encryptedRun === run && this.encryptedSearch?.key === key) {
      this.encryptedSearch = { key, running: false, matches: progress.matches };
    }
  }

  // Add encrypted matches to a search's list, after the threads Gmail's search found.
  #listEncryptedMatches(key: string, threadIds: Iterable<string>): void {
    const list = this.lists[key];
    if (list === undefined) return;
    const added = [...threadIds].filter((id) => !list.ids.includes(id));
    if (added.length > 0) this.#setList(key, { ids: [...list.ids, ...added] });
  }

  // Whether one encrypted message holds the words; nothing decrypted here is kept.
  async #encryptedMatch(
    messageId: string,
    terms: { include: string[]; exclude: string[] },
  ): Promise<boolean> {
    const known = this.#decrypted.get(messageId);
    if (known) return matchesTerms(known, terms);
    try {
      const message = await this.gmail.getMessage(messageId, "full");
      const view = threadDetail({ id: message.threadId, messages: [message] })
        .messages[0];
      if (view?.pgp === undefined) return false;
      return matchesTerms(await this.#decryptMessage(view), terms);
    } catch (error) {
      if (isNetworkError(error)) throw error;
      return false;
    }
  }

  /**
   * The bytes of a message's part, fetched by `attachmentId` once per session and kept by the
   * part's place in the message (`partId`), since Gmail gives the same part a new attachment id
   * on every fetch.
   */
  attachmentBytes(
    messageId: string,
    partId: string,
    attachmentId: string,
  ): Promise<Uint8Array> {
    const key = `${messageId}/${partId}`;
    const cache = this.#attachmentCache;
    let pending = cache.get(key);
    if (pending) cache.delete(key);
    else {
      pending = this.gmail
        .getAttachment(messageId, attachmentId)
        .then(decodeBase64Url);
      const failed = pending;
      failed.catch(() => {
        if (cache.get(key) === failed) cache.delete(key);
      });
    }
    // Re-inserted, so the Map's order runs from least to most recently asked for.
    cache.set(key, pending);
    for (const oldest of cache.keys()) {
      if (cache.size <= ATTACHMENT_CACHE_SIZE) break;
      cache.delete(oldest);
    }
    return pending;
  }

  /**
   * A `data:` URL for an inline part, from its data or by fetching it; null when it can't be had.
   * Throws when the fetch failed for want of a network, so the caller can try again once online.
   */
  async inlineDataUrl(
    messageId: string,
    part: InlinePart,
  ): Promise<string | null> {
    const known = this.#knownInlineUrl(messageId, part);
    if (known !== null) return known;
    let bytes: Uint8Array | null = null;
    if (part.attachmentId) {
      bytes = await this.attachmentBytes(
        messageId,
        part.partId,
        part.attachmentId,
      ).catch((error: unknown) => {
        if (isNetworkError(error)) {
          throw error;
        }
        return null;
      });
    }
    if (!bytes) return null;
    const url = `data:${part.mimeType};base64,${bytesToBase64(bytes)}`;
    const key = `${messageId}/${part.contentId}`;
    this.#inlineUrls.set(key, url);
    for (const oldest of this.#inlineUrls.keys()) {
      if (this.#inlineUrls.size <= INLINE_URLS_HELD) break;
      this.#inlineUrls.delete(oldest);
    }
    return url;
  }

  /** The data URLs of a message's inline images that can be had without waiting, by content id. */
  knownInlineUrls(
    messageId: string,
    parts: readonly InlinePart[],
  ): Map<string, string> {
    const urls = new Map<string, string>();
    for (const part of parts) {
      const url = this.#knownInlineUrl(messageId, part);
      if (url !== null) urls.set(part.contentId, url);
    }
    return urls;
  }

  // An inline image's data URL from its own data or this session's fetch, or null.
  #knownInlineUrl(messageId: string, part: InlinePart): string | null {
    if (part.data) {
      return `data:${part.mimeType};base64,${bytesToBase64(decodeBase64Url(part.data))}`;
    }
    const key = `${messageId}/${part.contentId}`;
    const url = this.#inlineUrls.get(key);
    if (url === undefined) return null;
    this.#inlineUrls.delete(key);
    this.#inlineUrls.set(key, url);
    return url;
  }

  /**
   * Apply one event to the list and do the work it asks for; returns the list after it.
   *
   * Every change to focus, the open thread, the open bundle, selection or undo goes through here
   * as an event (see `ListEvent`): keys, clicks and taps, each action (by {@link done} and the
   * rest, which say which rows they take out), undo, and each new {@link layout}. The step is
   * `listStep`; after it, the open thread's messages load, an opened bundle fetches its next
   * page, and the step's effect runs: a page to load, an action key's action, a todo to edit.
   *
   * ```ts
   * mail.dispatch({ type: "next" });
   * ```
   */
  dispatch = (event: ListEvent<Row, Todo>): MailList => {
    const before = this.list;
    let after = listStep(before, event);
    // An open thread the layout joined into another row opens as that row.
    const lead =
      event.type === "layout"
        ? openRowLead(after.open, this.#joins, layoutKeys(after.layout))
        : null;
    if (lead !== null)
      after = listStep(after, { type: "openThread", id: lead });
    this.list = after;
    this.menus.follow(before.menu, after.menu);
    if (after.open !== before.open) {
      this.openedInPlace = this.#openingInPlace && after.open !== null;
      this.detail = null;
      this.detailError = null;
      this.#openMembers =
        after.open === null ? [] : this.#joins.membersOf(after.open);
      if (after.open !== null) this.#openDetail(after.open);
      this.#settleDetailWaits();
      if (after.open !== null) {
        for (const listener of this.#openListeners) listener(after.open);
      }
    }
    const opened = after.bundle?.labelId;
    if (opened !== undefined && opened !== before.bundle?.labelId) {
      this.#openedBundle(opened);
    }
    if (before.selection.length > 0 && after.selection.length === 0) {
      if (this.snoozeMenuFor === SELECTION_MENU) this.closeSnoozeMenu();
      if (this.labelMenuFor === SELECTION_MENU) this.labelMenuFor = null;
    }
    const effect = after.effect;
    if (effect?.kind === "loadMore") void this.loadMoreBundle(effect.labelId);
    else if (effect?.kind === "edit") this.todos.setEditing(effect.todoId);
    else if (effect?.kind === "act")
      this.#command(effect.action, effect.target);
    return after;
  };

  // Only a bundle's first few are fetched up front; opening it fetches its next page once.
  #openedBundle(labelId: string): void {
    const key = bundleKey(labelId);
    if (this.#paged.has(key)) return;
    // Opened while its first page loads: the next page follows it.
    void (this.#bundleRequests.get(labelId) ?? Promise.resolve()).then(() => {
      if (!this.#paged.has(key)) void this.#loadBundle(labelId, true);
    });
  }

  // An action key on what it targets: the open thread, the selection, or the focused row.
  #command(action: string, target: ListTarget): void {
    const [todo] = target.todos;
    const [thread] = target.threads;
    if (target.from === "selection") {
      this.#selectionCommand(action);
    } else if (target.bundle !== null) {
      if (action === "done") {
        void this.doneGroup(target.bundle, target.threads, target.todos);
      } else if (action === "snooze")
        this.openSnoozeMenu(bundleRowKey(target.bundle));
    } else if (todo !== undefined) {
      this.#todoCommand(action, todo);
    } else if (thread !== undefined) {
      this.#threadCommand(action, thread);
    }
  }

  #threadCommand(action: string, id: string): void {
    switch (action) {
      case "done":
        this.toggleInbox(id);
        break;
      case "snooze":
        this.openSnoozeMenu(id);
        break;
      case "pin":
        this.togglePin(id);
        break;
      case "label":
        this.toggleLabelMenu(id);
        break;
      case "read":
      case "unread":
        this.setUnread([id], action === "unread");
        break;
      case "spam":
        if (this.#threadOf(id)?.labelIds.includes(LABEL.spam)) {
          this.notSpam([id]);
        } else {
          this.markSpam([id]);
        }
        break;
      case "important":
      case "unimportant":
        this.setImportant(id, action === "important");
        break;
      case "delete":
        if (this.#threadOf(id)?.labelIds.includes(LABEL.trash)) {
          this.untrash([id]);
        } else {
          this.trash([id]);
        }
        break;
    }
  }

  #todoCommand(action: string, id: string): void {
    switch (action) {
      case "done":
        this.toggleTodoDone(id);
        break;
      case "snooze":
        this.openSnoozeMenu(todoRowKey(id));
        break;
      case "pin":
        this.toggleTodoPin(id);
        break;
      case "delete":
        this.deleteTodos([id]);
        break;
    }
  }

  // The `e` key on the selection: Done on its threads in the inbox or snoozed and its todos, and
  // Move to inbox on its other threads, as one undo.
  #toggleSelectionInbox(): void {
    const { inbox } = this.selectionInboxPlan();
    this.#act(
      "selection",
      () => {
        const before = this.lastUndo;
        this.#actOnSelection("done");
        const done = this.lastUndo !== before ? this.lastUndo : null;
        this.moveToInbox(inbox);
        const moved = this.lastUndo !== (done ?? before) ? this.lastUndo : null;
        if (done !== null && moved !== null) {
          // Done's undo puts INBOX back as it was, which takes it off the moved threads too.
          this.#setUndo({ ...done, items: [...done.items, ...moved.items] });
        }
      },
      () => selectionAfter("done") === "clear",
    );
  }

  #selectionCommand(action: string): void {
    const offered = selectionActions(
      this.selectedThreadIds.length,
      this.selectedTodoIds.length,
    );
    const asOffered = action === "unread" ? "read" : action;
    if (!offered.some((name) => name === asOffered)) return;
    switch (action) {
      case "done":
        this.#toggleSelectionInbox();
        break;
      case "pin":
      case "spam":
      case "delete":
        this.actOnSelection(action);
        break;
      case "snooze":
        this.openSnoozeMenu(SELECTION_MENU);
        break;
      case "label":
        this.toggleLabelMenu(SELECTION_MENU);
        break;
      case "read":
      case "unread": {
        const ids = this.selectedThreadIds;
        if (ids.length === 0) return;
        this.#act(
          "selection",
          () => this.setUnread(ids, action === "unread"),
          () => selectionAfter("unread") === "clear",
        );
        break;
      }
    }
  }

  /** A notification's Done took a thread out of the inbox: its row goes now, ahead of the refresh that confirms it. */
  noteDoneElsewhere = (threadId: string): void => {
    const key = threadRowKey(this.rowOf(threadId));
    if (this.view.kind !== "inbox" || !layoutKeys(this.list.layout).has(key)) {
      return;
    }
    this.dispatch({
      type: "act",
      removes: [key],
      from: "notification",
      undoable: false,
      clearSelection: false,
    });
  };
}

/** Context accessors for the signed-in MailState. */
export const [getMailState, setMailState] = createContext<MailState>();
