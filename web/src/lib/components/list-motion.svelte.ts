import { untrack } from "svelte";
import type { TransitionConfig } from "svelte/transition";
import { followList } from "../list-follow.svelte";
import type { MailState } from "../mail-state.svelte";
import type { ThreadDetail } from "../thread-detail";
import type { Closing } from "./closing";
import { closingThread } from "./open-motion.svelte";
import { ListedRows, RowMoves } from "./row-motion";

/**
 * The motion a mail list's page has (the thread list, the Todos view).
 *
 * The page follows the list model (see `followList`); rows joining or leaving it grow in or
 * collapse out (see `ListedRows`), not those only moving between cards; a todo whose edit ended
 * slides to its new place, the rows it passes making way (see `RowMoves`); and the open thread
 * stays drawn open with its messages while it shrinks closed. Make while the list's component
 * initializes:
 *
 * ```ts
 * const motion = new ListMotion(mail, () => root, () => keys, () => sections);
 * ```
 */
export class ListMotion {
  /** The open thread, and the one just closed while it shrinks back into its row. */
  readonly thread: Closing<ThreadDetail | null>;
  /** A row coming in (see `ListedRows.enter`). */
  readonly enter: (node: Element, key: string) => TransitionConfig;
  /** A row going (see `ListedRows.leave`). */
  readonly leave: (node: Element, key: string) => TransitionConfig;
  #mail: MailState;

  /**
   * `root` is the list's element, `listed` the keys of the rows it lists, and `drawn` what it
   * draws them from, read so moves play once they are drawn.
   */
  constructor(
    mail: MailState,
    root: () => HTMLElement | undefined,
    listed: () => ReadonlySet<string>,
    drawn: () => unknown,
  ) {
    this.#mail = mail;
    followList(mail, root);
    this.thread = closingThread(mail, () => mail.openId);
    const rows = new ListedRows();
    this.enter = rows.enter;
    this.leave = rows.leave;
    const moves = new RowMoves();
    $effect.pre(() => {
      void mail.todos.endedEdits;
      untrack(() => moves.measure(root()));
    });
    $effect(() => {
      void drawn();
      void mail.todos.endedEdits;
      untrack(() => moves.play(root()));
    });
    $effect.pre(() => {
      rows.note(listed());
    });
  }

  /** Whether thread `id` shows open: open now, or shrinking back into its row. */
  showsOpen(id: string): boolean {
    return id === this.#mail.openId || this.thread.has(id);
  }
}
