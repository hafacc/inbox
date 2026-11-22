import { untrack } from "svelte";
import type { Attachment } from "svelte/attachments";
import { pushBack } from "../back-stack.svelte";
import { menuLayer } from "../menu-layer";

// Room kept between a popover and the window's edge.
const EDGE_MARGIN = 16;
// The least height a fitted popover is given, however little room its side has.
const MIN_FIT_HEIGHT = 160;

let menuCount = 0;

/** Options of the `popover` attachment. */
export type PopoverOptions = {
  /** close the popover */
  onClose: () => void;
  /** keys while it is open, seen first; `preventDefault` keeps an Escape from closing it */
  onKey?: (event: KeyboardEvent) => void;
  /** where it is placed, now and whenever it resizes: whether it opens above its anchor */
  onPlace?: (above: boolean) => void;
  /** the side it opens on while it fits there (default below) */
  prefer?: "above" | "below";
  /** whether to keep it inside the window: no taller than its side has room for, shifted sideways off an edge */
  fit?: boolean;
};

/**
 * A menu or popover's shared behavior, attached to the floating element inside its anchor (the
 * element holding the button that opens it).
 *
 * It tells the list (see `menuLayer`) when it opens and closes, and the list closes it when it
 * takes it away. A press outside the anchor goes to the list as a click away, which closes only
 * the top layer, this popover; that press neither moves focus nor clicks what was under it.
 * Escape closes it too and puts focus back on the anchor's button (the one with
 * `aria-haspopup` or `aria-expanded`), and so does the browser's Back. With `onPlace` it is told
 * which side of the anchor has room; with `fit` it is also kept inside the window, given a
 * `max-height` for the room on its side (its own layout decides what scrolls) and shifted
 * sideways off an edge. Keys are seen in the capture phase, before the page's own shortcuts.
 *
 * ```svelte
 * <div class="relative">
 *   <button aria-haspopup="menu" aria-expanded={open}>…</button>
 *   {#if open}
 *     <div role="menu" {@attach popover({ onClose: () => (open = false) })}>…</div>
 *   {/if}
 * </div>
 * ```
 */
export function popover(options: PopoverOptions): Attachment<HTMLElement> {
  return (element) => {
    const anchor = element.parentElement ?? element;
    const document = element.ownerDocument;
    const layer = menuLayer();
    menuCount += 1;
    const id = `menu-${menuCount}`;
    // Set once it is closing, while it may still be fading out.
    let closed = false;
    const releaseBack = untrack(() => pushBack(close));

    function close() {
      if (closed) return;
      closed = true;
      releaseBack();
      layer?.close(id);
      options.onClose();
    }

    function onPress(event: MouseEvent) {
      if (closed || anchor.contains(event.target as Node)) return;
      event.preventDefault();
      swallowNextClick(document);
      layer?.dismiss(id);
      close();
    }

    function onKeydown(event: KeyboardEvent) {
      if (closed) return;
      options.onKey?.(event);
      if (event.key !== "Escape" || event.defaultPrevented) return;
      event.preventDefault();
      event.stopPropagation();
      anchor
        .querySelector<HTMLElement>("[aria-haspopup], [aria-expanded]")
        ?.focus();
      close();
    }

    untrack(() =>
      layer?.open(id, () => {
        if (closed) return;
        closed = true;
        releaseBack();
        options.onClose();
      }),
    );

    let observer: ResizeObserver | null = null;
    const { onPlace, fit = false } = options;
    const place = () => {
      if (fit) {
        element.style.maxHeight = "";
        element.style.translate = "";
      }
      const rect = anchor.getBoundingClientRect();
      const below = window.innerHeight - rect.bottom - EDGE_MARGIN;
      const above = rect.top - EDGE_MARGIN;
      const height = element.offsetHeight;
      const opensAbove =
        options.prefer === "above"
          ? height <= above || above >= below
          : height > below && above > below;
      onPlace?.(opensAbove);
      if (!fit) return;
      element.style.maxHeight = `${Math.max(MIN_FIT_HEIGHT, opensAbove ? above : below)}px`;
      const box = element.getBoundingClientRect();
      const overLeft = EDGE_MARGIN - box.left;
      const overRight = box.right - (window.innerWidth - EDGE_MARGIN);
      const shift = overLeft > 0 ? overLeft : overRight > 0 ? -overRight : 0;
      if (shift !== 0) element.style.translate = `${shift}px 0`;
    };
    if (onPlace || fit) {
      place();
      observer = new ResizeObserver(place);
      observer.observe(element);
    }
    if (fit) window.addEventListener("resize", place);

    document.addEventListener("mousedown", onPress);
    document.addEventListener("keydown", onKeydown, true);
    return () => {
      if (!closed) {
        closed = true;
        releaseBack();
        layer?.close(id);
      }
      observer?.disconnect();
      if (fit) window.removeEventListener("resize", place);
      document.removeEventListener("mousedown", onPress);
      document.removeEventListener("keydown", onKeydown, true);
    };
  };
}

// Keep the click that ends a press from acting on what it lands on; a press that ends in no click
// (a drag) lets the next one through.
function swallowNextClick(document: Document): void {
  const view = document.defaultView;
  if (view === null) return;
  const swallow = (event: MouseEvent) => {
    event.preventDefault();
    event.stopImmediatePropagation();
    stop();
  };
  const stop = () => {
    view.removeEventListener("click", swallow, true);
    view.removeEventListener("mousedown", stop, true);
  };
  view.addEventListener("click", swallow, true);
  view.addEventListener("mousedown", stop, true);
}
