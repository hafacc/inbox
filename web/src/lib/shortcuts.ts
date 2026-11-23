import type { ThreadAction } from "@inbox/shared/view-filter";
import { isMacPlatform, touchOnly, undoKeyLabel } from "./keyboard";

/**
 * The list command each action key sends (see `ListEvent`'s `command`), on the open thread, else
 * the selection, else the focused row.
 */
export const COMMAND_KEYS: Readonly<Record<string, string>> = {
  e: "done",
  b: "snooze",
  p: "pin",
  l: "label",
  I: "read",
  U: "unread",
  "!": "spam",
  "+": "important",
  "=": "important",
  "-": "unimportant",
  "#": "delete",
};

// The command a thread action's key sends: an action and its opposite share one.
const ACTION_COMMANDS: Partial<Record<ThreadAction, string>> = {
  done: "done",
  inbox: "done",
  snooze: "snooze",
  pin: "pin",
  unpin: "pin",
  label: "label",
  read: "read",
  unread: "unread",
  spam: "spam",
  notSpam: "spam",
  important: "important",
  unimportant: "unimportant",
  delete: "delete",
  untrash: "delete",
};

/** The key of a thread action as a tooltip shows it (a capital with Shift), or null when it has none. */
export function threadActionKey(action: ThreadAction): string | null {
  const command = ACTION_COMMANDS[action];
  const key = Object.keys(COMMAND_KEYS).find(
    (candidate) => COMMAND_KEYS[candidate] === command,
  );
  if (key === undefined) return null;
  else if (/^[A-Z]$/.test(key)) return shiftKey(key);
  else return key;
}

/** One shortcut as the list shows it. */
export type Shortcut = {
  action: string;
  keys: string[];
  // true: press the keys one after the other; false: a range from the first to the last
  chord?: boolean;
};

/** A titled group of shortcuts. */
export type ShortcutGroup = { title: string; shortcuts: Shortcut[] };

/** The shortcuts in three columns, with this platform's modifier keys; todos' own while they show. */
export function shortcutColumns(todos = false): ShortcutGroup[][] {
  const mac = isMacPlatform();
  const command = mac ? "⌘" : "Ctrl+";
  const todoGroup: ShortcutGroup = {
    title: "Todos",
    shortcuts: [
      { action: "New todo", keys: ["n"] },
      { action: "Edit", keys: ["o"] },
      { action: "Delete", keys: ["#"] },
      { action: "Done, even while typing", keys: [`${command}D`] },
      { action: "Snooze or wake, even while typing", keys: [`${command}S`] },
      { action: "Pin, even while typing", keys: [`${command}K`] },
      { action: "Delete the one under the pointer", keys: [`${command}⌫`] },
      { action: "Bold", keys: [`${command}B`] },
      { action: "Italic", keys: [`${command}I`] },
      { action: "Repeat, in the snooze menu", keys: ["r"] },
    ],
  };
  return [
    [
      {
        title: "Move",
        shortcuts: [
          { action: "Next thread", keys: ["j"] },
          { action: "Previous thread", keys: ["k"] },
          { action: "Open", keys: ["o"] },
          { action: "Close or go back", keys: ["esc"] },
          { action: "Select or unselect", keys: ["x"] },
        ],
      },
      {
        title: "Write",
        shortcuts: [
          { action: "Compose", keys: ["c"] },
          { action: "Reply", keys: ["r"] },
          { action: "Reply all", keys: ["a"] },
          { action: "Forward", keys: ["f"] },
          { action: "Send", keys: [mac ? "⌘↵" : "Ctrl+Enter"] },
          { action: "Add or edit a link, in a message", keys: [`${command}K`] },
        ],
      },
    ],
    [
      {
        title: "Act",
        shortcuts: [
          { action: "Done, or Move to inbox", keys: ["e"] },
          { action: "Snooze", keys: ["b"] },
          { action: "Pick a suggestion", keys: ["1", "5"], chord: false },
          { action: "Pick date & time", keys: ["d"] },
          { action: "Pin", keys: ["p"] },
          { action: "Mark important", keys: ["+"] },
          { action: "Mark not important", keys: ["-"] },
          { action: "Label", keys: ["l"] },
          { action: "Mark read", keys: [shiftKey("I")] },
          { action: "Mark unread", keys: [shiftKey("U")] },
          { action: "Spam or not spam", keys: ["!"] },
          { action: "Delete", keys: ["#"] },
          { action: "Undo", keys: [undoKeyLabel()] },
        ],
      },
      ...(todos ? [todoGroup] : []),
    ],
    [
      {
        title: "Go",
        shortcuts: [
          { action: "Inbox", keys: ["g", "i"], chord: true },
          { action: "Snoozed", keys: ["g", "s"], chord: true },
          { action: "Done", keys: ["g", "d"], chord: true },
          { action: "Sent", keys: ["g", "t"], chord: true },
          ...(todos
            ? [{ action: "Todos", keys: ["g", "k"], chord: true }]
            : []),
          { action: "Search", keys: ["/"] },
          { action: "Complete a search operator", keys: ["Tab"] },
          { action: "Show or hide the sidebar", keys: ["["] },
          { action: "Focus on pinned mail and todos", keys: ["i"] },
          { action: "This list", keys: ["?"] },
        ],
      },
    ],
  ];
}

/** A letter typed with Shift, as this platform writes it. */
export function shiftKey(letter: string): string {
  if (isMacPlatform()) return `⇧${letter}`;
  else return `Shift+${letter}`;
}

/** A tooltip: the words, then the key while single-key shortcuts are on, except on a touch-only device. */
export function keyTitle(words: string, key: string, on: boolean): string {
  if (on && !touchOnly()) return `${words}  ${key}`;
  else return words;
}

/** An accessible name: the words, then the key in parentheses while single-key shortcuts are on, except on a touch-only device. */
export function keyLabel(words: string, key: string, on: boolean): string {
  if (on && !touchOnly()) return `${words} (${key})`;
  else return words;
}

/** A tooltip for a chord that works whatever the shortcuts setting (⌘Z, ⌘↵): the words, then the keys; only the words on a touch-only device. */
export function chordTitle(words: string, keys: string): string {
  if (touchOnly()) return words;
  else return `${words}  ${keys}`;
}
