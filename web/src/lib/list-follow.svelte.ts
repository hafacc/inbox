import { rowTodoId, threadRowKey } from "@inbox/shared/list-state";
import { tick, untrack } from "svelte";
import { openDuration } from "./components/open-motion.svelte";
import { scrollPageBy } from "./components/page-scroll";
import { phoneWidth } from "./components/phone";
import { ROW_MOTION_MS } from "./components/row-motion";
import { inputMode } from "./input-mode.svelte";
import type { MailState } from "./mail-state.svelte";
import { ROW_KEY } from "./press-away";
import { viewKey } from "./views";

// How long the row at the top keeps its place after a layout: rows above it grow in or collapse
// out over this long, so it is held through their motion.
const HOLD_MS = ROW_MOTION_MS + 50;
// How long a new todo stays in view after it is revealed: its editor opens and the on-screen
// keyboard comes up over this long.
const FOLLOW_MS = 1_500;
// How long after a close has played its row is checked to be on screen: the rows it held in
// place move where they belong as it ends.
const RETURN_SLACK_MS = 100;

// The bottom of the app's sticky header, which rows scroll under.
function headerBottom(): number {
  return (
    document.querySelector("[data-app-header]")?.getBoundingClientRect()
      .bottom ?? 0
  );
}

function rowElement(root: HTMLElement, key: string): HTMLElement | null {
  return root.querySelector<HTMLElement>(`[${ROW_KEY}="${CSS.escape(key)}"]`);
}

// The first row on screen below the header, and how far its top sits from the viewport's top.
// A bundle header held stuck at the top doesn't move with the list, so it is never the one.
function measureAnchor(
  root: HTMLElement,
  listed: ReadonlySet<string>,
): { key: string; offset: number } | null {
  const top = headerBottom();
  for (const element of root.querySelectorAll<HTMLElement>(`[${ROW_KEY}]`)) {
    const key = element.getAttribute(ROW_KEY);
    // A row still drawn while it plays its leave has no place to hold.
    if (key === null || !listed.has(key)) continue;
    if (element.hasAttribute("data-stuck")) continue;
    const rect = element.getBoundingClientRect();
    if (rect.height > 0 && rect.bottom > top) return { key, offset: rect.top };
  }
  return null;
}

/**
 * Keep a list's page in step with the list model (see `MailState.dispatch`).
 *
 * Each new layout is handed to the model with the row at the top of the screen measured first, and
 * once the page has drawn it that row is scrolled back to where it was, through the motion of rows
 * coming and going above it, unless the user scrolls meanwhile. Only the keyboard scrolls
 * otherwise: when the model's `reveal` goes up, the focused row scrolls just into view below the
 * header (and an open bundle's header) and above the on-screen keyboard; a todo stays in view for a
 * moment as its editor opens and the keyboard comes up. A thread closing whose top was scrolled
 * away (or, on a phone, whose row is below the screen) brings its row back into view, or the row
 * focus moved to when its own left the list with it (Done, its bundle closing). A row taking DOM focus (a click, a tap, Tab) focuses it in
 * the model. Call while the list's component initializes, with its root element.
 */
export function followList(
  mail: MailState,
  root: () => HTMLElement | undefined,
): void {
  let shownView: string | null = null;
  let shownReveal = untrack(() => mail.list.reveal);
  let holding: number | null = null;
  let shownOpen = untrack(() => mail.openId);
  // Counts closes of the open thread, so a hold measured before one (where the thread had been
  // read to) doesn't scroll the page back there once the close has placed it.
  let closes = 0;
  // Stops the check that a closed thread's row is back on screen (see `returnAfterClose`).
  let returning: (() => void) | null = null;
  // The new todo kept in view while its editor and the keyboard open, and until when.
  let following: { key: string; until: number; sizes: ResizeObserver } | null =
    null;

  function stopFollowing(): void {
    following?.sizes.disconnect();
    following = null;
  }

  // Keep the row `key` in view as it grows and as the keyboard takes the bottom of the screen.
  function follow(element: HTMLElement, key: string): void {
    stopFollowing();
    const row = rowElement(element, key);
    if (row === null) return;
    const again = () => {
      if (following === null || performance.now() > following.until) {
        stopFollowing();
      } else {
        revealRow(element, following.key);
      }
    };
    const sizes = new ResizeObserver(again);
    sizes.observe(row);
    following = { key, until: performance.now() + FOLLOW_MS, sizes };
  }

  function stopHolding(): void {
    if (holding !== null) cancelAnimationFrame(holding);
    holding = null;
  }

  function hold(key: string, offset: number): void {
    stopHolding();
    const element = root();
    if (!element) return;
    const until = performance.now() + HOLD_MS;
    let leftAt: number | null = null;
    const step = () => {
      holding = null;
      // The user scrolled since the last step: the list is theirs again.
      if (leftAt !== null && Math.abs(window.scrollY - leftAt) >= 1) return;
      const row = rowElement(element, key);
      if (row === null) return;
      const drift = row.getBoundingClientRect().top - offset;
      if (Math.abs(drift) >= 1) scrollPageBy(drift);
      leftAt = window.scrollY;
      if (performance.now() < until) holding = requestAnimationFrame(step);
    };
    step();
  }

  function stopReturning(): void {
    returning?.();
    returning = null;
  }

  // Once a close has played, its row (else the focused row) is shown again should anything since
  // have left it off screen, unless the user scrolled or used the keys meanwhile.
  function returnAfterClose(element: HTMLElement, key: string): void {
    stopReturning();
    let takenOver = false;
    const takeover = () => {
      takenOver = true;
    };
    const events = ["wheel", "touchmove", "keydown"] as const;
    for (const name of events) {
      window.addEventListener(name, takeover, { passive: true });
    }
    const check = () => {
      const focus = mail.list.focus;
      const row =
        rowElement(element, key) ??
        (focus === null ? null : rowElement(element, focus));
      if (takenOver || row === null || mail.openId !== null) return;
      const rect = row.getBoundingClientRect();
      if (rect.bottom <= visibleTop(row) || rect.top >= visibleBottom()) {
        revealRow(element, row.getAttribute(ROW_KEY) ?? key);
      }
    };
    const timer = setTimeout(() => {
      returning = null;
      cleanup();
      check();
    }, openDuration() + RETURN_SLACK_MS);
    const cleanup = () => {
      clearTimeout(timer);
      for (const name of events) window.removeEventListener(name, takeover);
    };
    returning = cleanup;
  }

  $effect.pre(() => {
    const sections = mail.layout;
    const view = viewKey(mail.view);
    untrack(() => {
      const closesBefore = closes;
      const element = root();
      const sameView = view === shownView;
      shownView = view;
      // At the very top the list stays at the top, so new mail shows. A swipe swapping the open
      // thread for its neighbor places the page itself, in the update that swaps them; the thread
      // it opened stays open over a list that keeps its place as any other.
      const swapping = mail.openedInPlace && mail.openId !== shownOpen;
      const anchor =
        sameView && element && window.scrollY > 0 && !swapping
          ? measureAnchor(
              element,
              new Set(mail.list.rows.map((row) => row.key)),
            )
          : null;
      const revealBefore = mail.list.reveal;
      if (anchor !== null) mail.dispatch({ type: "anchor", ...anchor });
      mail.dispatch({ type: "layout", sections });
      const kept = mail.list.anchor;
      // A layout that also moved focus to a row to show (a page landing under the keyboard, undo)
      // scrolls to that row instead.
      const revealed = mail.list.reveal !== revealBefore;
      if (anchor !== null && kept !== null && !revealed) {
        void tick().then(() => {
          if (closes === closesBefore) hold(kept.key, kept.offset);
        });
      }
    });
  });

  $effect(() => {
    const reveal = mail.list.reveal;
    untrack(() => {
      if (reveal === shownReveal) return;
      shownReveal = reveal;
      const element = root();
      const key = mail.list.focus;
      if (!element || key === null) return;
      stopHolding();
      stopFollowing();
      stopReturning();
      revealRow(element, key);
      if (rowTodoId(key) !== null) follow(element, key);
    });
  });

  // A thread closing shrinks back into its row from the bottom up; when its top was scrolled out
  // of view (reading down a long thread), the list goes back to where the row sits. One an action
  // took out of the list (Done) goes back to the row focus moved to in its place.
  $effect(() => {
    const open = mail.openId;
    untrack(() => {
      const closed = shownOpen;
      shownOpen = open;
      const element = root();
      const key = closed === null ? null : threadRowKey(closed);
      const listed = mail.list.rows.some((row) => row.key === key);
      const returnKey = listed ? key : mail.list.focus;
      if (key === null || open !== null || !element || returnKey === null) {
        return;
      }
      closes += 1;
      stopHolding();
      returnAfterClose(element, returnKey);
      const row = rowElement(element, returnKey);
      if (row === null) return;
      const rect = row.getBoundingClientRect();
      const above = rect.top - visibleTop(row);
      // A thread still drawn open shrinks back into its row from the bottom up. On a phone it
      // covered the screen, and a swipe may have moved it on to a row below; a row in its place
      // may be below too.
      const below =
        phoneWidth.current || !listed
          ? Math.max(0, rect.bottom - visibleBottom())
          : 0;
      const by = above < 0 ? above : below;
      if (by !== 0) scrollPageBy(by);
    });
  });

  $effect(() => {
    const element = root();
    if (!element) return;
    const onFocusIn = (event: FocusEvent) => {
      const row =
        event.target instanceof Element
          ? event.target.closest(`[${ROW_KEY}]`)
          : null;
      const key = row?.getAttribute(ROW_KEY);
      if (key == null || !element.contains(row)) return;
      mail.dispatch({
        type: "focus",
        key,
        by: inputMode.keyboard ? "keyboard" : "pointer",
      });
    };
    const onViewport = () => {
      if (following === null) return;
      if (performance.now() > following.until) stopFollowing();
      else revealRow(element, following.key);
    };
    // The user scrolling takes the list back from a todo kept in view.
    const onTakeover = () => stopFollowing();
    element.addEventListener("focusin", onFocusIn);
    window.visualViewport?.addEventListener("resize", onViewport);
    window.addEventListener("resize", onViewport);
    window.addEventListener("wheel", onTakeover, { passive: true });
    window.addEventListener("touchmove", onTakeover, { passive: true });
    return () => {
      element.removeEventListener("focusin", onFocusIn);
      window.visualViewport?.removeEventListener("resize", onViewport);
      window.removeEventListener("resize", onViewport);
      window.removeEventListener("wheel", onTakeover);
      window.removeEventListener("touchmove", onTakeover);
      stopHolding();
      stopFollowing();
      stopReturning();
    };
  });
}

// The top of the screen a row shows below: the header's bottom, and that of the open bundle's
// header when the row sits in one.
function visibleTop(row: HTMLElement): number {
  let top = Math.max(headerBottom(), window.visualViewport?.offsetTop ?? 0);
  const bundleHead = row
    .closest("[data-bundle]")
    ?.querySelector<HTMLElement>(`[${ROW_KEY}]`);
  if (bundleHead && bundleHead !== row) top += bundleHead.offsetHeight;
  return top;
}

// The bottom of the screen a row shows above: the on-screen keyboard's top.
function visibleBottom(): number {
  const viewport = window.visualViewport;
  return viewport === null || viewport === undefined
    ? window.innerHeight
    : Math.min(window.innerHeight, viewport.offsetTop + viewport.height);
}

// Scroll the whole row just into view: below the header (and the open bundle's), above the
// on-screen keyboard; nothing moves when it already shows.
function revealRow(root: HTMLElement, key: string): void {
  const row = rowElement(root, key);
  if (row === null) return;
  const top = visibleTop(row);
  const rect = row.getBoundingClientRect();
  const bottom = visibleBottom();
  let by = 0;
  if (rect.top < top) by = rect.top - top;
  else if (rect.bottom > bottom)
    by = Math.min(rect.bottom - bottom, rect.top - top);
  if (by !== 0) scrollPageBy(by);
}
