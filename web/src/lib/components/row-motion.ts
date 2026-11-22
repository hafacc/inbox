import { cubicOut } from "svelte/easing";
import { prefersReducedMotion } from "svelte/motion";
import { slide, type TransitionConfig } from "svelte/transition";

/** How long a row takes to grow in or collapse out of a list, as on Android. */
export const ROW_MOTION_MS = 200;

const STILL: TransitionConfig = { duration: 0 };

/** Grow a row open (or collapse it): height, with padding, margin and border, and opacity together; still when motion is reduced. */
export function grow(node: Element): TransitionConfig {
  if (prefersReducedMotion.current) return STILL;
  const sliding = slide(node, { duration: ROW_MOTION_MS, easing: cubicOut });
  return {
    ...sliding,
    css: (progress, remaining) =>
      `${sliding.css?.(progress, remaining) ?? ""};opacity:${progress}`,
  };
}

/**
 * Which rows a list shows, so that only a row joining or leaving the list animates: not one moving
 * between its cards, as when a thread opens and the rows after it start a card of their own.
 */
export class ListedRows {
  #now: ReadonlySet<string> | null = null;
  #added: ReadonlySet<string> = new Set();

  /** Note the rows listed now, before the list draws them; the first rows listed are its start, not new. */
  note(keys: ReadonlySet<string>): void {
    const now = this.#now;
    if (
      now !== null &&
      keys.size === now.size &&
      [...keys].every((key) => now.has(key))
    ) {
      return;
    }
    // Rows come in during the update this note precedes; after it, a row is only moving.
    this.#added =
      now === null
        ? new Set()
        : new Set([...keys].filter((key) => !now.has(key)));
    this.#now = keys;
    queueMicrotask(() => {
      this.#added = new Set();
    });
  }

  /** A row coming in: it grows open and fades in, unless it was already listed elsewhere. */
  enter = (node: Element, key: string): TransitionConfig =>
    this.#added.has(key) ? grow(node) : STILL;

  /** A row going: it collapses and fades out, unless it is still listed elsewhere. */
  leave = (node: Element, key: string): TransitionConfig =>
    this.#now?.has(key) ? STILL : grow(node);
}

// The rows of a list a move slides, by the key each carries.
const MOVING_ROW = "[data-move-key]";

/**
 * Rows sliding from where they were to where they are (FLIP), as when a todo whose edit ended
 * takes its new place: {@link measure} before the update, {@link play} after it, in the same frame.
 * Still when motion is reduced.
 */
export class RowMoves {
  #before: Map<string, number> | null = null;

  /** Note where each row under `root` is, before an update that may move them; forgotten next frame. */
  measure(root: Element | undefined): void {
    if (!root || prefersReducedMotion.current) return;
    const before = new Map<string, number>();
    for (const row of root.querySelectorAll<HTMLElement>(MOVING_ROW)) {
      before.set(row.dataset.moveKey ?? "", pageTop(row));
    }
    this.#before = before;
    requestAnimationFrame(() => {
      if (this.#before === before) this.#before = null;
    });
  }

  /** Slide each row under `root` that moved since {@link measure} from where it was. */
  play(root: Element | undefined): void {
    const before = this.#before;
    if (!root || before === null) return;
    let moved = false;
    for (const row of root.querySelectorAll<HTMLElement>(MOVING_ROW)) {
      const was = before.get(row.dataset.moveKey ?? "");
      if (was === undefined) continue;
      const offset = was - pageTop(row);
      if (Math.abs(offset) < 1) continue;
      moved = true;
      row.animate(
        [{ transform: `translateY(${offset}px)` }, { transform: "none" }],
        { duration: ROW_MOTION_MS, easing: "cubic-bezier(0.33, 1, 0.68, 1)" },
      );
    }
    if (moved) this.#before = null;
  }
}

// Where a row is in the page, so a scroll between measuring and playing doesn't count as a move.
function pageTop(row: HTMLElement): number {
  return row.getBoundingClientRect().top + window.scrollY;
}
