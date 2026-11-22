/**
 * Whether the user is driving the app from the keyboard: true after a key press, false after a
 * pointer press. Focus marks that would only distract a mouse user (a row's ring, its actions)
 * show only while it is true.
 */
class InputMode {
  keyboard = $state(false);

  constructor() {
    if (typeof window === "undefined") return;
    window.addEventListener(
      "keydown",
      (event) => {
        if (!event.metaKey && !event.ctrlKey && !event.altKey) {
          this.keyboard = true;
        }
      },
      { capture: true },
    );
    window.addEventListener(
      "pointerdown",
      () => {
        this.keyboard = false;
      },
      { capture: true },
    );
  }
}

/** The app's one input-mode tracker. */
export const inputMode = new InputMode();
