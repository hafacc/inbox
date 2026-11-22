/** How a `DeviceSetting` keeps its choice: the storage key and the conversions to and from it. */
export type DeviceSettingFormat<Value> = {
  /** the localStorage key */
  key: string;
  /** the choice stored as `raw`; null when nothing is stored */
  parse: (raw: string | null) => Value;
  /** what to store for `value`; null removes the key, for the default */
  store: (value: Value) => string | null;
};

/**
 * A choice saved in this browser for every account, kept across sign-out (device-data.ts) and
 * followed across the browser's tabs. Storage can be blocked: the choice then holds for this page.
 */
export class DeviceSetting<Value> {
  /** The current choice. */
  value = $state() as Value;
  #format: DeviceSettingFormat<Value>;
  #listeners = new Set<(value: Value) => void>();

  constructor(format: DeviceSettingFormat<Value>) {
    this.#format = format;
    this.value = format.parse(null);
    if (typeof window === "undefined") return;
    this.value = this.#read();
    window.addEventListener("storage", (event) => {
      if (event.key === null || event.key === format.key) {
        this.#apply(this.#read());
      }
    });
  }

  /** Change the choice and remember it. */
  set(value: Value): void {
    this.#apply(value);
    try {
      const stored = this.#format.store(value);
      if (stored === null) localStorage.removeItem(this.#format.key);
      else localStorage.setItem(this.#format.key, stored);
    } catch (error) {
      console.error("device setting save failed", this.#format.key, error);
    }
  }

  /** Call `listener` after each change, here or in another tab; returns the function that stops it. */
  onChange(listener: (value: Value) => void): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  #apply(value: Value): void {
    if (value === this.value) return;
    this.value = value;
    for (const listener of this.#listeners) listener(value);
  }

  #read(): Value {
    try {
      return this.#format.parse(localStorage.getItem(this.#format.key));
    } catch {
      return this.#format.parse(null);
    }
  }
}
