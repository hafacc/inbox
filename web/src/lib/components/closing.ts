/**
 * What a list shows open (threads, bundles) that stays shown for a moment after it closes, so it
 * can shrink back first, with what it showed when it closed: each id's value is kept as it was at
 * the last {@link update} that had it open. Call {@link stop} when the list goes.
 */
export class Closing<T> {
  #kept: Map<string, T>;
  #duration: () => number;
  #open: ReadonlyMap<string, T> = new Map();
  #timers = new Map<string, ReturnType<typeof setTimeout>>();

  /** `kept` holds the closing ids (a reactive map, to draw from); `duration` is how long a close plays. */
  constructor(kept: Map<string, T>, duration: () => number) {
    this.#kept = kept;
    this.#duration = duration;
  }

  /**
   * Note what is open now and what each shows; those that just closed stay closing, with what they
   * last showed, until their close has played, unless `inPlace` (the open one took their place,
   * with nothing shrinking).
   */
  update(open: ReadonlyMap<string, T>, inPlace = false): void {
    const duration = inPlace ? 0 : this.#duration();
    for (const [id, shown] of this.#open) {
      if (open.has(id) || duration === 0) continue;
      this.#kept.set(id, shown);
      clearTimeout(this.#timers.get(id));
      this.#timers.set(
        id,
        setTimeout(() => {
          this.#timers.delete(id);
          this.#kept.delete(id);
        }, duration),
      );
    }
    for (const id of open.keys()) {
      clearTimeout(this.#timers.get(id));
      this.#timers.delete(id);
      this.#kept.delete(id);
    }
    this.#open = new Map(open);
  }

  /** Whether `id` is still closing. */
  has(id: string): boolean {
    return this.#kept.has(id);
  }

  /** What `id` showed when it closed, while it is still closing. */
  shown(id: string): T | undefined {
    return this.#kept.get(id);
  }

  /** Stop the timers, as the list goes. */
  stop(): void {
    for (const timer of this.#timers.values()) clearTimeout(timer);
    this.#timers.clear();
  }
}
