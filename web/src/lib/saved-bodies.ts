import { joinDetails, type ThreadDetail, threadPart } from "./thread-detail";

/** Where saved bodies come from: the device's saved copy. */
export type SavedBodyStore = {
  /** whether the saved copy holds thread `id` */
  saved(id: string): boolean;
  /** resolves once `saved` answers from what the device stored */
  ready(): Promise<void>;
  /** these threads' stored bodies, ready to show (sealed messages opened), in order; undefined where missing */
  read(ids: readonly string[]): Promise<(ThreadDetail | undefined)[]>;
};

/** How many threads' bodies stay in memory. */
export const SAVED_BODIES_HELD = 24;

/**
 * Bodies of threads in the device's saved copy, ready to show, the latest shown or read ahead
 * held in memory so a thread opens without waiting on the store.
 *
 * The saved copy's history sync keeps what it holds current (labels, unread marks, new and
 * deleted messages), so a row whose threads are all saved shows from here and asks Gmail
 * nothing; a row with a thread that isn't saved gets null and is read from Gmail. Whoever
 * changes a stored body calls {@link forget} (or {@link update}) so memory follows it.
 *
 * ```ts
 * const shown = bodies.peek(members) ?? (await bodies.load(members));
 * ```
 */
export class SavedBodies {
  #store: SavedBodyStore;
  #held: number;
  #memory = new Map<string, ThreadDetail>();
  #reading = new Map<string, Promise<ThreadDetail | undefined>>();
  // Moves on with every forget, so a read that started before one doesn't fill memory after it.
  #epoch = 0;

  constructor(store: SavedBodyStore, held = SAVED_BODIES_HELD) {
    this.#store = store;
    this.#held = held;
  }

  /** The row of `members` (lead first) as one thread from memory alone; null unless every one is saved and held. */
  peek(members: readonly string[]): ThreadDetail | null {
    if (!this.#allSaved(members)) return null;
    const parts: ThreadDetail[] = [];
    for (const id of members) {
      const part = this.#memory.get(id);
      if (part === undefined) return null;
      parts.push(part);
    }
    for (const id of members) this.#keep(id, this.#memory.get(id)!);
    return joined(members, parts);
  }

  /** The row of `members` (lead first) as one thread from the saved copy; null when one isn't saved or has no body. */
  async load(members: readonly string[]): Promise<ThreadDetail | null> {
    await this.#store.ready();
    if (!this.#allSaved(members)) return null;
    const parts = await this.#bodies(members);
    if (!parts.every((part) => part !== undefined)) return null;
    return joined(members, parts as ThreadDetail[]);
  }

  /** Read a row's bodies into memory ahead of its open. */
  prefetch(members: readonly string[]): void {
    if (members.every((id) => this.#memory.has(id))) return;
    void this.load(members).catch(() => {});
  }

  /** Let go of these threads' bodies, whose stored copies changed. */
  forget(ids: Iterable<string>): void {
    this.#epoch += 1;
    for (const id of ids) {
      this.#memory.delete(id);
      this.#reading.delete(id);
    }
  }

  /** Let go of every body (the device locked or unlocked, which changes what can show). */
  clear(): void {
    this.#epoch += 1;
    this.#memory.clear();
    this.#reading.clear();
  }

  /** Change a held body as its stored copy was changed; one not held stays unread. */
  update(id: string, change: (body: ThreadDetail) => ThreadDetail): void {
    const held = this.#memory.get(id);
    if (held !== undefined) this.#memory.set(id, change(held));
  }

  #allSaved(members: readonly string[]): boolean {
    return members.length > 0 && members.every((id) => this.#store.saved(id));
  }

  async #bodies(ids: readonly string[]): Promise<(ThreadDetail | undefined)[]> {
    const missing = ids.filter(
      (id) => !this.#memory.has(id) && !this.#reading.has(id),
    );
    if (missing.length > 0) {
      const epoch = this.#epoch;
      const read = this.#store.read(missing);
      missing.forEach((id, index) => {
        const one = read.then((found) => found[index]);
        this.#reading.set(id, one);
        void one.then(
          (body) => {
            if (this.#reading.get(id) !== one) return;
            this.#reading.delete(id);
            if (body !== undefined && epoch === this.#epoch) {
              this.#keep(id, body);
            }
          },
          () => {
            if (this.#reading.get(id) === one) this.#reading.delete(id);
          },
        );
      });
    }
    return Promise.all(
      ids.map((id) => this.#memory.get(id) ?? this.#reading.get(id)),
    );
  }

  // The Map's order runs from least to most recently used.
  #keep(id: string, body: ThreadDetail): void {
    this.#memory.delete(id);
    this.#memory.set(id, body);
    for (const oldest of this.#memory.keys()) {
      if (this.#memory.size <= this.#held) break;
      this.#memory.delete(oldest);
    }
  }
}

// A row's saved bodies as one thread; each part keeps only its own thread's messages, so a body
// saved for a joined row doesn't show another thread's twice.
function joined(
  members: readonly string[],
  parts: readonly ThreadDetail[],
): ThreadDetail {
  const own = parts.map((part, index) => {
    const id = members[index]!;
    const mixed = [...part.messages, ...part.drafts].some(
      (message) => message.threadId !== id,
    );
    return mixed || part.id !== id ? threadPart(part, id) : part;
  });
  return own.length === 1 ? own[0]! : joinDetails(own);
}
