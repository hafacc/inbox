import type { Mailbox, QuotedMessage } from "@inbox/shared/compose";
import type {
  ComposeFile,
  ComposeImage,
  ComposeMode,
  ComposeSnapshot,
} from "./compose-model";
import { autosaveDelay } from "./outbox";

/** Whether the draft is safe in Gmail. */
export type SaveStatus = "idle" | "saving" | "saved" | "queued" | "failed";

// Edits settle this long before the draft goes to Gmail.
const AUTOSAVE_MS = 2_000;

/** One message being written: what the composer shows, saved to Gmail as a draft while typing. */
export class Composer {
  /** Id on this device. */
  readonly localId: string;
  /** What it was opened for. */
  readonly mode: ComposeMode;
  /** The thread a reply or forward belongs to. */
  readonly threadId: string | null;
  /** The Gmail draft, once saved. */
  draftId = $state<string | null>(null);
  /** The Gmail message id of the draft as last saved. */
  messageId = $state<string | null>(null);
  /** The Gmail thread the draft is in once saved, a new message's own one too. */
  draftThreadId = $state<string | null>(null);
  /** Sender address. */
  from = $state("");
  /** `To`. */
  to = $state.raw<Mailbox[]>([]);
  /** `Cc`. */
  cc = $state.raw<Mailbox[]>([]);
  /** `Bcc`. */
  bcc = $state.raw<Mailbox[]>([]);
  /** Subject. */
  subject = $state("");
  /** Body, markdown. */
  markdown = $state("");
  /** Body as the editor's HTML, null until it is edited in this composer. */
  html = $state<string | null>(null);
  /** Attachments. */
  attachments = $state.raw<ComposeFile[]>([]);
  /** Images in the body. */
  inlineImages = $state.raw<ComposeImage[]>([]);
  /** The message a reply quotes, under the signature; null for none. */
  quote = $state.raw<QuotedMessage | null>(null);
  /** Whether the Cc and Bcc rows show. */
  showCopies = $state(false);
  /** Draft save state. */
  saveStatus = $state<SaveStatus>("idle");
  /** Files still being read in (pasted, dropped or picked ones, forwarded attachments, a reopened draft's files). */
  loadingFiles = $state(0);
  /** Whether Gmail's draft holds what this holds: opened from it, or saved since the last edit. */
  inGmail = false;
  /** Whether the user chose to send without encryption though everyone has a key. */
  sendPlain = $state(false);
  /** Why the last action didn't go through, shown in the composer. */
  error = $state<string | null>(null);
  readonly #inReplyTo: string | null;
  readonly #references: string[];
  #timer: ReturnType<typeof setTimeout> | null = null;
  #save: (composer: Composer) => void;
  #sendAt: () => number | null;

  constructor(
    snapshot: ComposeSnapshot,
    save: (composer: Composer) => void,
    sendAt: () => number | null = () => null,
  ) {
    this.localId = snapshot.localId;
    this.mode = snapshot.mode;
    this.threadId = snapshot.threadId;
    this.draftId = snapshot.draftId;
    this.messageId = snapshot.messageId;
    this.draftThreadId = snapshot.draftThreadId ?? null;
    this.from = snapshot.from;
    this.to = snapshot.to;
    this.cc = snapshot.cc;
    this.bcc = snapshot.bcc;
    this.subject = snapshot.subject;
    this.markdown = snapshot.markdown;
    this.html = snapshot.html ?? null;
    this.attachments = snapshot.attachments;
    this.inlineImages = snapshot.inlineImages;
    this.quote = snapshot.quote ?? null;
    this.showCopies = snapshot.cc.length > 0 || snapshot.bcc.length > 0;
    this.#inReplyTo = snapshot.inReplyTo;
    this.#references = snapshot.references;
    this.#save = save;
    this.#sendAt = sendAt;
  }

  /** Everything it holds, for saving and building the message. */
  snapshot(): ComposeSnapshot {
    return {
      localId: this.localId,
      mode: this.mode,
      threadId: this.threadId,
      draftId: this.draftId,
      messageId: this.messageId,
      draftThreadId: this.draftThreadId,
      from: this.from,
      to: this.to,
      cc: this.cc,
      bcc: this.bcc,
      subject: this.subject,
      markdown: this.markdown,
      html: this.html,
      inReplyTo: this.#inReplyTo,
      references: this.#references,
      attachments: this.attachments,
      inlineImages: this.inlineImages,
      quote: this.quote,
    };
  }

  /** Note an edit: the draft saves once typing pauses, or in time for a scheduled send. */
  changed(): void {
    this.inGmail = false;
    this.error = null;
    this.cancelAutosave();
    this.#timer = setTimeout(
      () => {
        this.#timer = null;
        this.#save(this);
      },
      autosaveDelay(Date.now(), this.#sendAt(), AUTOSAVE_MS),
    );
  }

  /** Whether an edit is waiting for its autosave. */
  get pendingAutosave(): boolean {
    return this.#timer !== null;
  }

  /** Drop a pending autosave, as when sending or closing saves at once. */
  cancelAutosave(): void {
    if (this.#timer !== null) clearTimeout(this.#timer);
    this.#timer = null;
  }
}
