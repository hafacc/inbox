// users/{uid}/todos (design.md, "Todos"): latr's todos and the todos on emails (a doc with a
// `threadId`, under that thread's id). Every write is the whole doc, stamped with the server's time;
// deletes are tombstones, except an abandoned empty todo, which is deleted outright.

import {
  type EmailTodo,
  emailTodoFields,
  isEmailTodoDoc,
  readEmailTodo,
  readTodo,
  type Todo,
  todoFields,
} from "@inbox/shared/todos";
import {
  collection,
  doc,
  type Firestore,
  getDocsFromServer,
  query,
  serverTimestamp,
  where,
  writeBatch,
} from "firebase/firestore";
import { watchFirestore } from "../firestore-watch";

/** A stored todo of either kind: one of its own, or one on an email. */
export type AnyTodo = Todo | EmailTodo;

/** The live todos, split by kind. */
export type StoredTodos = { todos: Todo[]; emailTodos: EmailTodo[] };

/** Whether `todo` is one on an email. */
export function isEmailTodo(todo: AnyTodo): todo is EmailTodo {
  return "threadId" in todo;
}

function todos(database: Firestore, uid: string) {
  return collection(database, "users", uid, "todos");
}

function readAll(
  docs: readonly { id: string; data: () => Record<string, unknown> }[],
): StoredTodos {
  const now = Date.now();
  const stored: StoredTodos = { todos: [], emailTodos: [] };
  for (const entry of docs) {
    const data = entry.data();
    if (isEmailTodoDoc(data)) {
      stored.emailTodos.push(readEmailTodo(entry.id, data, now));
    } else {
      stored.todos.push(readTodo(entry.id, data, now));
    }
  }
  return stored;
}

/** Follow the live todos (tombstones left out), calling back with all of them. */
export function watchTodos(
  database: Firestore,
  uid: string,
  onChange: (stored: StoredTodos) => void,
): () => void {
  return watchFirestore(
    query(todos(database, uid), where("deleted", "==", false)),
    (snapshot) => onChange(readAll(snapshot.docs)),
    "todo",
  ).stop;
}

/**
 * The live todos (tombstones left out), read once from Firestore's servers: a device copy may be
 * missing some, so offline this fails rather than answer short.
 */
export async function readTodos(
  database: Firestore,
  uid: string,
): Promise<StoredTodos> {
  const snapshot = await getDocsFromServer(
    query(todos(database, uid), where("deleted", "==", false)),
  );
  return readAll(snapshot.docs);
}

// Firestore's most writes in one batch.
const BATCH_LIMIT = 500;

/** Save todos whole and delete `erased` outright, a batch at a time; Firestore queues it while offline. */
export async function saveTodos(
  database: Firestore,
  uid: string,
  saved: readonly AnyTodo[],
  erased: readonly string[] = [],
): Promise<void> {
  const writes = [
    ...saved.map((todo) => ({ id: todo.id, todo })),
    ...erased.map((id) => ({ id, todo: null })),
  ];
  for (let start = 0; start < writes.length; start += BATCH_LIMIT) {
    const batch = writeBatch(database);
    for (const { id, todo } of writes.slice(start, start + BATCH_LIMIT)) {
      const ref = doc(todos(database, uid), id);
      if (todo === null) batch.delete(ref);
      else {
        batch.set(ref, {
          ...(isEmailTodo(todo) ? emailTodoFields(todo) : todoFields(todo)),
          serverModifiedAt: serverTimestamp(),
        });
      }
    }
    await batch.commit();
  }
}
