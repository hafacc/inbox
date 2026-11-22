import {
  countTags,
  type EmailTodo,
  emailTodoEditEnded,
  emailTodosByThread,
  emailTodoToEdit,
  heldInPlace,
  newTodo,
  type RepeatDraft,
  renameTags,
  type Todo,
  todoEditEnded,
  undoneTodo,
  withDeleted,
  withDone,
  withEmailTodoDeleted,
  withEmailTodoText,
  withPinToggled,
  withReactivated,
  withRepeat,
  withSnoozed,
  withTyping,
} from "@inbox/shared/todos";
import type { Firestore } from "firebase/firestore";
import { untrack } from "svelte";
import {
  type AnyTodo,
  isEmailTodo,
  readTodos,
  saveTodos,
  watchTodos,
} from "./todo-docs";
import type { TagOffer } from "./todo-editor";

/** What undoing a todo action writes back: the todos as they were, and the copies it made. */
export type TodoUndo = { before: AnyTodo[]; spawned: Todo[] };

/** Where the arrow keys put the caret in the todo they move to. */
export type CaretHint = { id: string; x: number; edge: "first" | "last" };

// Typing is saved once it pauses this long, and at once when the editor closes.
const SAVE_DELAY_MS = 400;

/**
 * latr's todos in this account (`users/{uid}/todos`), and the todos on its emails, while the Show
 * todos setting is on: the live lists, the edits latr makes, and which todo is being written. An
 * email's todo holds only its text: its other edits are its email's.
 *
 * Every change shows at once and is saved whole; Firestore's own listener then confirms it. An
 * action that can be undone returns a {@link TodoUndo}, which {@link restore} takes back.
 */
export class TodoState {
  /** Whether todos show: the account's Show todos setting. */
  enabled = $state(false);
  /** Live todos of their own, tombstones left out, with typing not yet saved. */
  todos = $state.raw<Todo[]>([]);
  /** Live todos on emails, with typing not yet saved; a new one being written first. */
  emailTodos = $state.raw<EmailTodo[]>([]);
  /** Per Gmail thread id, its email's todo as {@link emailTodos} has it. */
  readonly byThread = $derived(emailTodosByThread(this.emailTodos));
  /** Whether Firestore has answered since todos were turned on. */
  loaded = $state(false);
  #editingId = $state<string | null>(null);
  // The todo being edited and its text as editing began, null for one new in this edit.
  #editStart: { id: string; text: string | null } | null = null;
  /** How many edits have ended with a change that may move their row, for lists to animate the move. */
  endedEdits = $state(0);
  /** Deletes, with undo, a saved todo whose edit ended with its text cleared out. */
  onClearedOut: ((id: string) => void) | null = null;
  /** At phone width, the tags offered above the keyboard for the todo being written. */
  tagOffer = $state.raw<TagOffer | null>(null);
  readonly #byId = $derived(new Map(this.todos.map((todo) => [todo.id, todo])));
  /** Every tag in use, for completing one as it's typed. */
  readonly tagCounts = $derived(
    countTags([...this.todos, ...this.emailTodos].map((todo) => todo.text)),
  );
  /** Where the arrow keys put the caret in the todo they move to; read once by that row. */
  caretHint: CaretHint | null = null;
  /** The todo being edited at its place from before the edit, until the editor closes. */
  #held = $state.raw<Todo | null>(null);
  #heldPrevious: Todo | null = null;
  /** The todos as lists place them: the one being edited where it sat when editing began. */
  readonly placed = $derived.by(() => {
    const held = this.#held;
    return held
      ? this.todos.map((todo) => (todo.id === held.id ? held : todo))
      : this.todos;
  });
  #db: Firestore;
  #uid: string;
  #stored: Todo[] = [];
  #storedEmail: EmailTodo[] = [];
  // A new todo shown and being written, saved once it has text.
  #fresh: AnyTodo | null = null;
  // Typed text not yet saved, by todo id; laid over each newer stored copy.
  #unsaved = new Map<string, string>();
  #timers = new Map<string, ReturnType<typeof setTimeout>>();
  #stop: (() => void) | null = null;

  constructor(database: Firestore, uid: string) {
    this.#db = database;
    this.#uid = uid;

    $effect.pre(() => {
      const id = this.editingId;
      const live = id ? this.todos.find((todo) => todo.id === id) : undefined;
      untrack(() => {
        const held = this.#held;
        if (!live) this.#held = null;
        else if (!held || !this.#heldPrevious || held.id !== live.id) {
          this.#held = live;
        } else if (live !== this.#heldPrevious) {
          this.#held = heldInPlace(held, this.#heldPrevious, live);
        }
        this.#heldPrevious = live ?? null;
      });
    });
  }

  /** Follow the account's todos, or stop and forget them. */
  setEnabled(on: boolean): void {
    if (on === this.enabled) return;
    this.enabled = on;
    if (on) {
      this.#stop = watchTodos(this.#db, this.#uid, (stored) => {
        this.#stored = stored.todos;
        this.#storedEmail = stored.emailTodos;
        // Another device saved this email's todo while the new, still empty one was open: edit theirs.
        const fresh = this.#fresh;
        if (
          fresh !== null &&
          isEmailTodo(fresh) &&
          stored.emailTodos.some((todo) => todo.id === fresh.id)
        )
          this.#fresh = null;
        this.#show();
        this.loaded = true;
      });
    } else {
      this.dispose();
      this.#stored = [];
      this.#storedEmail = [];
      this.todos = [];
      this.emailTodos = [];
      this.loaded = false;
      this.#editingId = null;
      this.#editStart = null;
      this.#fresh = null;
    }
  }

  /** Save any typing and stop listening. */
  dispose(): void {
    this.flush();
    this.#stop?.();
    this.#stop = null;
  }

  /** The todo whose text is being edited. */
  get editingId(): string | null {
    return this.#editingId;
  }

  /**
   * Open a todo's text to edit, or none. The one open before ends its edit here, the one place
   * every way of leaving it comes to (a tap away, Enter, Escape, opening something else): its
   * text is saved and, when changed, its time stamped once ({@link todoEditEnded}), so the row
   * moves only now; cleared out, it is deleted with undo, and a new one left empty goes. Hiding
   * the page only saves typing ({@link flush}).
   */
  setEditing(id: string | null): void {
    const previous = this.#editingId;
    if (previous === id) return;
    const start = this.#editStart;
    this.#editingId = id;
    this.#editStart =
      id === null
        ? null
        : {
            id,
            text: this.#fresh?.id === id ? null : (this.#any(id)?.text ?? null),
          };
    if (previous !== null) {
      this.#endEdit(previous, start?.id === previous ? start.text : null);
    }
  }

  #endEdit(id: string, startText: string | null): void {
    if (this.#fresh?.id === id) {
      this.#fresh = null;
      this.#show();
      return;
    }
    const current = this.#any(id);
    if (current === undefined) {
      // Deleted elsewhere since: the typing is dropped, not saved over its tombstone.
      this.#cancelSave(id);
    } else if (current.text.trim() === "") {
      if (this.#hasSavedText(id)) {
        this.#discard(id);
        this.onClearedOut?.(id);
      } else {
        this.#erase(id);
      }
    } else {
      const now = Date.now();
      const ended = isEmailTodo(current)
        ? emailTodoEditEnded(current, startText, now)
        : todoEditEnded(current, startText, now);
      if (ended !== current) this.endedEdits += 1;
      if (ended !== current || this.#unsaved.has(id)) this.#write([ended]);
    }
  }

  /** The live todo of its own with this id. */
  get(id: string): Todo | undefined {
    return this.#byId.get(id);
  }

  // The live todo of either kind with this id.
  #any(id: string): AnyTodo | undefined {
    return this.get(id) ?? this.emailTodos.find((todo) => todo.id === id);
  }

  #show(): void {
    const now = Date.now();
    const shown = withTyping(this.#stored, this.#unsaved, now);
    const shownEmail = this.#storedEmail.map((todo) => {
      const text = this.#unsaved.get(todo.id);
      return text === undefined ? todo : withEmailTodoText(todo, text);
    });
    const fresh = this.#fresh;
    if (fresh === null) {
      this.todos = shown;
      this.emailTodos = shownEmail;
    } else if (isEmailTodo(fresh)) {
      this.todos = shown;
      this.emailTodos = [fresh, ...shownEmail];
    } else {
      this.todos = [fresh, ...shown];
      this.emailTodos = shownEmail;
    }
  }

  // Shown at once; the listener confirms it once Firestore has it.
  #write(saved: readonly AnyTodo[], erased: readonly string[] = []): void {
    for (const todo of saved) this.#cancelSave(todo.id);
    for (const id of erased) this.#cancelSave(id);
    const changed = new Map(saved.map((todo) => [todo.id, todo]));
    const gone = new Set(erased);
    const merged = <Item extends AnyTodo>(
      stored: readonly Item[],
      kind: (todo: AnyTodo) => todo is Item,
    ): Item[] => {
      const kept = stored
        .filter((todo) => !gone.has(todo.id))
        .map((todo) => {
          const next = changed.get(todo.id);
          return next !== undefined && kind(next) ? next : todo;
        });
      const added = saved.filter(
        (todo): todo is Item =>
          kind(todo) && !stored.some((entry) => entry.id === todo.id),
      );
      return [...added, ...kept].filter((todo) => !todo.deleted);
    };
    this.#stored = merged(this.#stored, isOwnTodo);
    this.#storedEmail = merged(this.#storedEmail, isEmailTodo);
    this.#show();
    void saveTodos(this.#db, this.#uid, saved, erased).catch((error) =>
      console.error("todo write failed", error),
    );
  }

  #cancelSave(id: string): void {
    const timer = this.#timers.get(id);
    if (timer !== undefined) clearTimeout(timer);
    this.#timers.delete(id);
    this.#unsaved.delete(id);
  }

  // The saved todos of their own with these ids; a new one not saved yet has nothing to act on.
  #found(ids: readonly string[]): Todo[] {
    return this.#foundAny(ids).filter(isOwnTodo);
  }

  // The saved todos of either kind with these ids.
  #foundAny(ids: readonly string[]): AnyTodo[] {
    return ids
      .map((id) => this.#any(id))
      .filter(
        (todo): todo is AnyTodo =>
          todo !== undefined && todo.id !== this.#fresh?.id,
      );
  }

  /** Save typing that hasn't been saved yet: one todo's, or everyone's. Blank text is never saved. */
  flush(id?: string): void {
    const ids = id === undefined ? [...this.#unsaved.keys()] : [id];
    const typed = ids.filter(
      (key) => (this.#unsaved.get(key)?.trim() ?? "") !== "",
    );
    const saved = this.#foundAny(typed);
    // Typing into a todo deleted elsewhere since is dropped, not saved over its tombstone.
    for (const key of typed) this.#cancelSave(key);
    if (saved.length > 0) this.#write(saved);
  }

  // Drop a todo's typing not yet saved, showing its stored text again.
  #discard(id: string): void {
    if (!this.#unsaved.has(id)) return;
    this.#cancelSave(id);
    this.#show();
  }

  // Whether the todo's stored copy (typing not yet saved left out) has any text.
  #hasSavedText(id: string): boolean {
    const stored = [...this.#stored, ...this.#storedEmail].find(
      (todo) => todo.id === id,
    );
    return stored !== undefined && stored.text.trim() !== "";
  }

  /**
   * Show a new empty todo and open it to edit: at `position` (else the top of Active), pinned when
   * asked. It is saved once it has text, and gone if left empty. Returns the id opened.
   */
  start(
    options: { id?: string; position?: string | null; pinned?: boolean } = {},
  ): string {
    this.flush();
    this.setEditing(null);
    const id = options.id ?? crypto.randomUUID();
    this.#fresh = {
      ...newTodo(id, "", Date.now()),
      position: options.position ?? null,
      pinned: options.pinned ?? false,
    };
    this.#show();
    this.setEditing(id);
    return id;
  }

  /**
   * Open the todo of an email (its row's Gmail threads, lead first) to edit: the one it has, as an
   * email has one, else a new, empty one, saved once it has text and gone if left empty.
   */
  startEmail(threadIds: readonly string[]): void {
    this.flush();
    this.setEditing(null);
    const { todo, fresh } = emailTodoToEdit(
      this.byThread,
      threadIds,
      Date.now(),
    );
    if (fresh) {
      this.#fresh = todo;
      this.#show();
    }
    this.setEditing(todo.id);
  }

  /** New text for a todo, saved once typing pauses; a new todo is saved as soon as it has text. */
  edit(id: string, text: string): void {
    const fresh = this.#fresh;
    if (fresh?.id === id) {
      if (text.trim() === "") {
        this.#fresh = { ...fresh, text };
        this.#show();
      } else {
        this.#fresh = null;
        this.#write([{ ...fresh, text }]);
      }
      return;
    }
    const existing = this.#any(id);
    if (!existing || existing.text === text) return;
    this.#unsaved.set(id, text);
    this.#show();
    const timer = this.#timers.get(id);
    if (timer !== undefined) clearTimeout(timer);
    this.#timers.set(
      id,
      setTimeout(() => this.flush(id), SAVE_DELAY_MS),
    );
  }

  /** Mark todos done (those not done yet); a repeating one makes its next copy, snoozed to its next time. */
  done(ids: readonly string[]): TodoUndo | null {
    const before = this.#found(ids).filter((todo) => todo.state !== "DONE");
    if (before.length === 0) return null;
    const now = Date.now();
    const saved: Todo[] = [];
    const spawned: Todo[] = [];
    for (const todo of before) {
      const result = withDone(todo, now, crypto.randomUUID());
      saved.push(result.done);
      if (result.spawned) spawned.push(result.spawned);
    }
    this.#write([...saved, ...spawned]);
    return { before, spawned };
  }

  /** Make a done or snoozed todo active again; unsnoozing a repeating one ends its repeat. */
  reactivate(ids: readonly string[]): TodoUndo | null {
    const before = this.#found(ids);
    if (before.length === 0) return null;
    const now = Date.now();
    this.#write(before.map((todo) => withReactivated(todo, now)));
    return { before, spawned: [] };
  }

  /** Snooze todos until `until` (epoch milliseconds). */
  snooze(ids: readonly string[], until: number): TodoUndo | null {
    const before = this.#found(ids);
    if (before.length === 0) return null;
    const now = Date.now();
    this.#write(before.map((todo) => withSnoozed(todo, until, now)));
    return { before, spawned: [] };
  }

  /** Pin or unpin a todo. */
  togglePin(id: string): TodoUndo | null {
    const before = this.#found([id]);
    if (before.length === 0) return null;
    const now = Date.now();
    this.#write(before.map((todo) => withPinToggled(todo, now)));
    return { before, spawned: [] };
  }

  /** Give a todo the repeat rule `draft`. */
  setRepeat(id: string, draft: RepeatDraft): void {
    const existing = this.get(id);
    if (existing) this.#write([withRepeat(existing, draft, Date.now())]);
  }

  /** Delete todos of either kind, leaving tombstones. */
  remove(ids: readonly string[]): TodoUndo | null {
    const before = this.#foundAny(ids);
    if (before.length === 0) return null;
    const now = Date.now();
    this.#write(
      before.map((todo) =>
        isEmailTodo(todo)
          ? withEmailTodoDeleted(todo, now)
          : withDeleted(todo, now),
      ),
    );
    return { before, spawned: [] };
  }

  // Delete an abandoned empty todo outright; a new one never saved just goes.
  #erase(id: string): void {
    if (this.#fresh?.id === id) {
      this.#fresh = null;
      this.#show();
    } else if (this.#any(id)) {
      this.#write([], [id]);
    }
  }

  /**
   * Rewrite every todo's tags naming the label `oldName` to name `newName` (`renameTags`), typing
   * not yet saved included. With todos turned off, or not loaded yet, they are read once from
   * Firestore's servers to do so, which fails offline.
   */
  async retag(oldName: string, newName: string): Promise<void> {
    const retagged = (todo: AnyTodo): AnyTodo[] => {
      const text = renameTags(todo.text, oldName, newName);
      return text === todo.text ? [] : [{ ...todo, text }];
    };
    if (this.enabled && this.loaded) {
      this.flush();
      const changed = [...this.#stored, ...this.#storedEmail].flatMap(retagged);
      if (changed.length > 0) this.#write(changed);
    } else {
      const stored = await readTodos(this.#db, this.#uid);
      const changed = [...stored.todos, ...stored.emailTodos].flatMap(retagged);
      if (changed.length > 0) await saveTodos(this.#db, this.#uid, changed);
    }
  }

  /** Take an action back: the todos as they were, keeping text typed since, and the copies it made deleted. */
  restore(undo: TodoUndo): void {
    const now = Date.now();
    this.#write([
      ...undo.before.map((todo) => {
        if (!isEmailTodo(todo)) return undoneTodo(todo, this.get(todo.id), now);
        const current = this.#any(todo.id);
        return current === undefined
          ? todo
          : withEmailTodoText(todo, current.text);
      }),
      ...undo.spawned.map((todo) => withDeleted(todo, now)),
    ]);
  }
}

function isOwnTodo(todo: AnyTodo): todo is Todo {
  return !isEmailTodo(todo);
}
