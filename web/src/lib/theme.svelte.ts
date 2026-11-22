import {
  type ColorMode,
  DEFAULT_THEME,
  PALETTE_VERSION,
  type ThemeId,
  themeColors,
  themeIdFrom,
} from "@inbox/shared/themes";
import { createContext, untrack } from "svelte";
import { MediaQuery } from "svelte/reactivity";

/** The light/dark choice: follow the system, or force light or dark. */
export type ThemeMode = "system" | "light" | "dark";

// Read by static/theme-boot.js before the app loads, so the first paint has the saved colors.
const MODE_KEY = "inbox:theme";
const THEME_KEY = "inbox:color-theme";
const COLORS_KEY = "inbox:theme-colors";
const FADE_MS = 220;

/** The light/dark mode (this device) and color theme (the account's, cached here), applied to the root element. */
export class ThemeState {
  /** The light/dark choice. */
  mode = $state<ThemeMode>("system");
  /** The color theme. */
  colorTheme = $state<ThemeId>(DEFAULT_THEME);
  /** Light or dark as shown now. */
  readonly resolved: ColorMode;
  #systemDark = new MediaQuery("(prefers-color-scheme: dark)", false);
  #applied: string | null = null;
  #fadeTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.resolved = $derived(
      (this.mode === "system" ? this.#systemDark.current : this.mode === "dark")
        ? "dark"
        : "light",
    );

    $effect(() => {
      untrack(() => this.#load());
      const onStorage = (event: StorageEvent) => {
        if (
          event.key === null ||
          event.key === MODE_KEY ||
          event.key === THEME_KEY
        ) {
          this.#load();
        }
      };
      window.addEventListener("storage", onStorage);
      return () => window.removeEventListener("storage", onStorage);
    });

    $effect(() => {
      const mode = this.resolved;
      const id = this.colorTheme;
      const key = `${id}:${mode}`;
      if (key === this.#applied) return;
      if (this.#applied !== null) this.#fade();
      this.#applied = key;
      const root = document.documentElement;
      root.classList.toggle("dark", mode === "dark");
      const colors = themeColors(id, mode);
      for (const [name, value] of Object.entries(colors)) {
        root.style.setProperty(`--color-${name}`, value);
      }
      for (const meta of document.querySelectorAll(
        'meta[name="theme-color"]',
      )) {
        meta.setAttribute("content", colors.bg!);
      }
    });
  }

  // Colors ease over for a moment, then transitions go back to each component's own.
  #fade() {
    const root = document.documentElement;
    root.classList.add("theme-fade");
    if (this.#fadeTimer !== null) clearTimeout(this.#fadeTimer);
    this.#fadeTimer = setTimeout(() => {
      root.classList.remove("theme-fade");
      this.#fadeTimer = null;
    }, FADE_MS);
  }

  // Takes the saved mode and color theme; storage that can't be read leaves the defaults.
  #load() {
    let mode: string | null = null;
    let theme: string | null = null;
    try {
      mode = localStorage.getItem(MODE_KEY);
      theme = localStorage.getItem(THEME_KEY);
    } catch (error) {
      console.warn("couldn't read the theme", error);
    }
    this.mode = mode === "dark" || mode === "light" ? mode : "system";
    this.colorTheme = themeIdFrom(theme);
    this.#cacheColors(this.colorTheme, false);
  }

  /** Choose light, dark or the system's, and remember it on this device. */
  setMode = (mode: ThemeMode): void => {
    this.mode = mode;
    try {
      if (mode === "system") localStorage.removeItem(MODE_KEY);
      else localStorage.setItem(MODE_KEY, mode);
    } catch (error) {
      console.warn("couldn't save the theme mode", error);
    }
  };

  /** Show a color theme and keep it for the next first paint; the account setting is saved by the caller. */
  setColorTheme = (id: ThemeId): void => {
    this.colorTheme = id;
    this.#cacheColors(id, true);
  };

  // Saves the theme's colors for theme-boot.js; unless forced, only when the saved copy is for
  // another theme or an older palette.
  #cacheColors(id: ThemeId, force: boolean) {
    try {
      const saved = JSON.parse(localStorage.getItem(COLORS_KEY) ?? "null") as {
        id?: string;
        version?: string;
      } | null;
      if (!force && saved?.id === id && saved.version === PALETTE_VERSION) {
        return;
      }
      localStorage.setItem(THEME_KEY, id);
      localStorage.setItem(
        COLORS_KEY,
        JSON.stringify({
          id,
          version: PALETTE_VERSION,
          light: themeColors(id, "light"),
          dark: themeColors(id, "dark"),
        }),
      );
    } catch (error) {
      console.warn("couldn't cache the theme", error);
    }
  }
}

/** Context accessors for the one ThemeState. */
export const [getThemeState, setThemeState] = createContext<ThemeState>();
