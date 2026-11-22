// Todos on an email (design.md, "Todos on an email"): an email's todo holds only its text. Whether
// it is done, snoozed, pinned or in the inbox is its email's, read from the email, never stored on
// the todo. Its doc id is its thread id, so an email has at most one. Android :core todo/Linked.kt
// is the same; both run shared/testdata/linked-todo-fixtures.json.

/** A todo on an email: its text and bookkeeping; its state is its email's. */
export type EmailTodo = {
  /** doc id: the Gmail thread id, so an email has at most one todo */
  id: string;
  /** the Gmail thread it is on, the same as `id` */
  threadId: string;
  /** text with latr's inline style markers kept */
  text: string;
  /** when it was made, epoch milliseconds */
  createdAt: number;
  /** when it was last changed, epoch milliseconds */
  modifiedAt: number;
  /** a tombstone: deletes keep the doc so a device's stale copy can't bring it back */
  deleted: boolean;
};

/** An email todo's stored fields, without its id and the server-set `serverModifiedAt`. */
export type EmailTodoFields = Omit<EmailTodo, "id">;

/** Whether a stored todo doc is one on an email: it carries a `threadId`. */
export function isEmailTodoDoc(
  data: Readonly<Record<string, unknown>>,
): boolean {
  return typeof data.threadId === "string" && data.threadId !== "";
}

/** A stored todo on an email, its doc id its thread; missing times read as `now`. */
export function readEmailTodo(
  id: string,
  data: Readonly<Record<string, unknown>>,
  now: number,
): EmailTodo {
  return {
    id,
    threadId: id,
    text: typeof data.text === "string" ? data.text : "",
    createdAt: typeof data.createdAt === "number" ? data.createdAt : now,
    modifiedAt: typeof data.modifiedAt === "number" ? data.modifiedAt : now,
    deleted: data.deleted === true,
  };
}

/** A new todo on the email `threadId`. */
export function newEmailTodo(
  threadId: string,
  text: string,
  now: number,
): EmailTodo {
  return {
    id: threadId,
    threadId,
    text,
    createdAt: now,
    modifiedAt: now,
    deleted: false,
  };
}

/** What a write stores for `todo`; the store adds `serverModifiedAt`. */
export function emailTodoFields(todo: EmailTodo): EmailTodoFields {
  const { id: _id, ...fields } = todo;
  return fields;
}

/** An email's todo with new text; `modifiedAt` waits for the edit to end ({@link emailTodoEditEnded}). */
export function withEmailTodoText(todo: EmailTodo, text: string): EmailTodo {
  return text === todo.text ? todo : { ...todo, text };
}

/**
 * An email's todo as editing it ends at `now`, its text `startText` when editing began (null for
 * one new in this edit): changed text stamps `modifiedAt`, once.
 */
export function emailTodoEditEnded(
  todo: EmailTodo,
  startText: string | null,
  now: number,
): EmailTodo {
  return startText === null || todo.text === startText
    ? todo
    : { ...todo, modifiedAt: now };
}

/** An email's todo deleted at `now`: a tombstone with its text blanked. */
export function withEmailTodoDeleted(todo: EmailTodo, now: number): EmailTodo {
  return { ...todo, deleted: true, text: "", modifiedAt: now };
}

/** Per Gmail thread id, the live todo of that email. */
export function emailTodosByThread(
  todos: readonly EmailTodo[],
): ReadonlyMap<string, EmailTodo> {
  const byThread = new Map<string, EmailTodo>();
  for (const todo of todos) {
    if (!todo.deleted) byThread.set(todo.threadId, todo);
  }
  return byThread;
}

/**
 * The todo a row shows: that of the first of its Gmail threads (`threadIds`, lead first, as a
 * joined row holds them) that has one, or null.
 */
export function rowEmailTodo(
  byThread: ReadonlyMap<string, EmailTodo>,
  threadIds: readonly string[],
): EmailTodo | null {
  for (const threadId of threadIds) {
    const todo = byThread.get(threadId);
    if (todo !== undefined) return todo;
  }
  return null;
}

/**
 * The todo Add todo opens on a row (`threadIds`, lead first): the one it has, as an email has one,
 * else a new, empty one on its lead, saved once it has text.
 */
export function emailTodoToEdit(
  byThread: ReadonlyMap<string, EmailTodo>,
  threadIds: readonly string[],
  now: number,
): { todo: EmailTodo; fresh: boolean } {
  const existing = rowEmailTodo(byThread, threadIds);
  if (existing !== null) {
    return { todo: existing, fresh: false };
  } else {
    return { todo: newEmailTodo(threadIds[0] ?? "", "", now), fresh: true };
  }
}
