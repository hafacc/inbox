import type { ListEvent } from "@inbox/shared/list-state";
import type { Attachment } from "svelte/attachments";

/** The attribute a list row's element carries its row key in (see `threadRowKey`). */
export const ROW_KEY = "data-row-key";

/** The list events a press away sends. */
export type PressAwayEvent = Extract<
  ListEvent<unknown, unknown>,
  { type: "tap" | "dismiss" }
>;

/** Options of the `pressAway` attachment. */
export type PressAwayOptions = {
  /** whether a press here is away from the list's top layer */
  away: () => boolean;
  /** send the list a press away */
  dispatch: (event: PressAwayEvent) => void;
};

/**
 * Make presses on part of a list, while `away` holds, presses away from its top layer.
 *
 * Attached to the dimmed rest of a list with a bundle open: a click there goes to the list as a
 * `tap` on the row it lands on (else a `dismiss`), which closes only the top layer, and acts on
 * nothing. Its press moves no focus, so focusing a row doesn't close a layer first. An open menu
 * swallows the click itself (see `popover`), and the list closes only the menu.
 *
 * ```svelte
 * <div {@attach pressAway({ away: () => bundleOpen, dispatch })}>…</div>
 * ```
 */
export function pressAway(options: PressAwayOptions): Attachment<HTMLElement> {
  return (element) => {
    const onPress = (event: MouseEvent) => {
      if (options.away()) event.preventDefault();
    };
    const onClick = (event: MouseEvent) => {
      if (!options.away()) return;
      event.preventDefault();
      event.stopPropagation();
      const target = event.target;
      const row =
        target !== null && "closest" in target
          ? (target as Element).closest(`[${ROW_KEY}]`)
          : null;
      const key = row?.getAttribute(ROW_KEY);
      if (key != null) options.dispatch({ type: "tap", key });
      else options.dispatch({ type: "dismiss" });
    };
    element.addEventListener("mousedown", onPress, true);
    element.addEventListener("click", onClick, true);
    return () => {
      element.removeEventListener("mousedown", onPress, true);
      element.removeEventListener("click", onClick, true);
    };
  };
}
