/** Whether the platform is a Mac, where ⌘ is the command key. */
export function isMacPlatform(): boolean {
  return typeof navigator !== "undefined" && /mac/i.test(navigator.platform);
}

/** Whether the device has only a touch screen (no mouse or trackpad), where key hints mean nothing. */
export function touchOnly(): boolean {
  return (
    typeof window !== "undefined" &&
    typeof window.matchMedia === "function" &&
    window.matchMedia("(hover: none) and (pointer: coarse)").matches
  );
}

/** ⌘ on Mac, Ctrl elsewhere, and not the other one, so Mac's Ctrl text bindings stay text bindings. */
export function isCommandChord(event: KeyboardEvent): boolean {
  if (isMacPlatform()) return event.metaKey && !event.ctrlKey;
  else return event.ctrlKey && !event.metaKey;
}

/** Whether any of ⌘, Ctrl or Alt is held. */
export function hasModifier(event: KeyboardEvent): boolean {
  return event.metaKey || event.ctrlKey || event.altKey;
}

/** Whether the key went to a text field, where single-key shortcuts stay off. */
export function isEditableTarget(target: EventTarget | null): boolean {
  if (!target) return false;
  const element = target as HTMLElement;
  return (
    element.tagName === "INPUT" ||
    element.tagName === "TEXTAREA" ||
    element.tagName === "SELECT" ||
    element.isContentEditable
  );
}

/** Whether a ⌘ chord may act on a todo: not while typing anywhere but in that todo's own text. */
export function todoChordAllowed(
  target: EventTarget | null,
  todoId: string,
): boolean {
  if (!isEditableTarget(target)) return true;
  const element = target as Element;
  if (typeof element.closest !== "function") return false;
  const row = element.closest("[data-todo-id]");
  return row?.getAttribute("data-todo-id") === todoId;
}

// Places whose keys belong to them: menus, dialogs and forms (the composer is one).
const OWN_KEYS =
  'dialog, form, [role="dialog"], [role="alertdialog"], [role="menu"], [role="menubar"], [role="listbox"], [role="combobox"]';
// Controls Enter activates.
const CONTROLS = 'button, a[href], summary, [role="button"], [role="link"]';

/**
 * Whether a key pressed on `target` should be left alone by the single-key shortcuts: typing in a
 * text field, anything inside a menu, dialog or form, and Enter on a button or link (it clicks
 * it). Elements marked `data-shortcuts` (the list's rows) take every shortcut.
 */
export function leavesShortcuts(
  target: EventTarget | null,
  key: string,
): boolean {
  if (isEditableTarget(target)) return true;
  const element = target as Element | null;
  if (typeof element?.closest !== "function") return false;
  if (element.closest("[data-shortcuts]") !== null) return false;
  if (element.closest(OWN_KEYS) !== null) return true;
  return key === "Enter" && element.closest(CONTROLS) !== null;
}

/** The undo chord as the platform writes it. */
export function undoKeyLabel(): string {
  return isMacPlatform() ? "⌘Z" : "Ctrl+Z";
}
