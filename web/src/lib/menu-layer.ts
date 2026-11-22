import type { ListEvent } from "@inbox/shared/list-state";

/** How a menu or dropdown tells the list it opened, closed or was pressed away from. */
export type MenuLayer = {
  /** a menu opened; the list runs `close` when it takes the menu away */
  open: (id: string, close: () => void) => void;
  /** a menu closed by itself */
  close: (id: string) => void;
  /** a press outside menu `id`: the list closes its top layer */
  dismiss: (id: string) => void;
};

let provided: MenuLayer | null = null;

/** Make `layer` the one menus report to; returns a function that stops it. */
export function provideMenuLayer(layer: MenuLayer): () => void {
  provided = layer;
  return () => {
    if (provided === layer) provided = null;
  };
}

/** The layer menus report to, or null where no list is showing. */
export function menuLayer(): MenuLayer | null {
  return provided;
}

/** The list events a menu sends. */
export type MenuEvent = Extract<
  ListEvent<unknown, unknown>,
  { type: "openMenu" | "closeMenu" | "dismiss" }
>;

/**
 * The menu layer of a list: menus' reports sent to it as list events, and each menu closed when
 * a step takes it away.
 *
 * Opening, closing and a press away become `openMenu`, `closeMenu` and `dismiss` (sent only while
 * that menu is the list's top one). After each list step, {@link follow} closes the menu the step
 * took away (a press away, Escape, another menu opening), so the list's `menu` and what shows
 * agree.
 *
 * ```ts
 * const menus = new ListMenus(dispatch, () => list.menu);
 * provideMenuLayer(menus);
 * // after each step:
 * menus.follow(before.menu, after.menu);
 * ```
 */
export class ListMenus implements MenuLayer {
  #closers = new Map<string, () => void>();
  #dispatch: (event: MenuEvent) => void;
  #current: () => string | null;

  /** a layer sending its events with `dispatch` to a list whose open menu `current` reads */
  constructor(
    dispatch: (event: MenuEvent) => void,
    current: () => string | null,
  ) {
    this.#dispatch = dispatch;
    this.#current = current;
  }

  /** note an open menu and tell the list */
  open = (id: string, close: () => void): void => {
    this.#closers.set(id, close);
    this.#dispatch({ type: "openMenu", id });
  };

  /** forget a menu that closed by itself and tell the list */
  close = (id: string): void => {
    this.#closers.delete(id);
    this.#dispatch({ type: "closeMenu", id });
  };

  /** send a press away while menu `id` is the top layer */
  dismiss = (id: string): void => {
    if (this.#current() === id) this.#dispatch({ type: "dismiss" });
  };

  /** close the menu a step took away, given the list's menu before and after it */
  follow(before: string | null, after: string | null): void {
    if (before === null || before === after) return;
    const close = this.#closers.get(before);
    this.#closers.delete(before);
    close?.();
  }
}
