import { DeviceSetting } from "./device-setting.svelte";

/** Focus mode: the inbox shows only pinned threads and todos. Saved on this device, for every account. */
class FocusMode extends DeviceSetting<boolean> {
  constructor() {
    super({
      key: "inbox:focus",
      parse: (raw) => raw === "1",
      store: (on) => (on ? "1" : null),
    });
  }

  /** Whether focus mode is on. */
  get on(): boolean {
    return this.value;
  }

  /** Switch focus mode over. */
  toggle = (): void => {
    this.set(!this.value);
  };
}

/** The app's one focus switch. */
export const focusMode = new FocusMode();
