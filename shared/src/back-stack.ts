/**
 * Back as both apps follow it: it closes the layer opened last, then returns to the view shown
 * before, and at the first view with nothing open it leaves the app (Android) or does what the
 * browser does (web).
 *
 * The web keeps this as browser history entries; Android adds a Back handler per layer as the
 * layer opens and keeps the views shown in a list. Both follow `backStep`; fixtures in
 * `shared/testdata/back-stack-fixtures.json`, which Android's `:core` `BackStack.kt` also runs.
 */

/**
 * A layer over a view that Back closes before it leaves the view: `menu` is the side menu, a
 * menu, a sheet or one of its steps, or a dialog; `todo` a todo being edited.
 */
export type BackLayer =
  | "menu"
  | "thread"
  | "todo"
  | "selection"
  | "bundle"
  | "search";

/** Every kind of layer Back closes. */
export const BACK_LAYERS: readonly BackLayer[] = [
  "menu",
  "thread",
  "todo",
  "selection",
  "bundle",
  "search",
];

/** What Back goes back through. */
export type BackStack = {
  /** view keys in the order shown, the current one last */
  views: readonly string[];
  /** the open layers in the order they opened, the top one last */
  layers: readonly BackLayer[];
};

/** Something that changes what Back goes back through. */
export type BackEvent =
  /** a layer opened; one already open keeps its place (another bundle opening in its stead) */
  | { type: "open"; layer: BackLayer }
  /** a layer closed by other means than Back (its X, Escape, an action that ends it) */
  | { type: "close"; layer: BackLayer }
  /** the app shows another view, by key; the app closes the layers the move ends itself */
  | { type: "view"; view: string }
  /** the user pressed Back */
  | { type: "back" };

/** What Back does: close a layer, show the view before, or leave the app. */
export type BackEffect =
  | { kind: "close"; layer: BackLayer }
  | { kind: "view"; view: string }
  | { kind: "leave" };

/** How the views move from `current` to `next`, given the view shown before `current`. */
export type ViewStep = "none" | "back" | "replace" | "push";

// Views of this kind replace one another rather than stacking: searches.
const REPLACING_KIND = "search";

function kindOf(view: string): string {
  return view.split(":")[0] ?? view;
}

/** What Back goes back through when the app starts on a view. */
export function startBack(view: string): BackStack {
  return { views: [view], layers: [] };
}

/**
 * How the views follow a move to `next`: back to the view before (`previous`) when it is that
 * one, a rewrite of the current one from one search to another, else a new one on top (settings
 * pages stack like any other view).
 */
export function viewStep(
  previous: string | null,
  current: string,
  next: string,
): ViewStep {
  if (current === next) return "none";
  else if (previous === next) return "back";
  else if (
    kindOf(current) === REPLACING_KIND &&
    kindOf(next) === REPLACING_KIND
  ) {
    return "replace";
  } else return "push";
}

/** The views after a move to `next` (see `viewStep`). */
export function viewsAfter(
  views: readonly string[],
  next: string,
): readonly string[] {
  const current = views.at(-1);
  if (current === undefined) return [next];
  const step = viewStep(views.at(-2) ?? null, current, next);
  switch (step) {
    case "none":
      return views;
    case "back":
      return views.slice(0, -1);
    case "replace":
      return [...views.slice(0, -1), next];
    default:
      return [...views, next];
  }
}

/** The stack after `event`, and for Back what it does (null for any other event). */
export function backStep(
  stack: BackStack,
  event: BackEvent,
): { stack: BackStack; effect: BackEffect | null } {
  switch (event.type) {
    case "open":
      if (stack.layers.includes(event.layer)) return { stack, effect: null };
      else {
        return {
          stack: { ...stack, layers: [...stack.layers, event.layer] },
          effect: null,
        };
      }
    case "close":
      return {
        stack: {
          ...stack,
          layers: stack.layers.filter((layer) => layer !== event.layer),
        },
        effect: null,
      };
    case "view":
      return {
        stack: { ...stack, views: viewsAfter(stack.views, event.view) },
        effect: null,
      };
    default: {
      const top = stack.layers.at(-1);
      const before = stack.views.at(-2);
      if (top !== undefined) {
        return {
          stack: { ...stack, layers: stack.layers.slice(0, -1) },
          effect: { kind: "close", layer: top },
        };
      } else if (before !== undefined) {
        return {
          stack: { ...stack, views: stack.views.slice(0, -1) },
          effect: { kind: "view", view: before },
        };
      } else return { stack, effect: { kind: "leave" } };
    }
  }
}
