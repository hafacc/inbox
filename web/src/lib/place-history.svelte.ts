import { untrack } from "svelte";
import {
  currentPlace,
  onPlaceShown,
  overlayOnTop,
  previousPlace,
  writePlace,
} from "./back-stack.svelte";
import { scrollPageTo } from "./components/page-scroll";
import type { MailState } from "./mail-state.svelte";
import {
  type Place,
  placeFromUrl,
  placeStep,
  placeUrl,
  threadCloseStep,
} from "./places";
import { sameView, viewKey } from "./views";

// How long a view's scroll position is waited for while its list loads.
const RESTORE_MS = 3_000;
// What the user does that ends the wait for a scroll position.
const TAKEOVER_EVENTS = ["wheel", "touchstart", "pointerdown", "keydown"];

/**
 * Keep the address and browser history in step with the view and open thread: each change adds
 * or rewrites an entry, back and forward show the entry's place, and a reload opens the place in
 * the address. Each view keeps its own scroll position. Call while a component initializes.
 */
export function followPlaces(mail: MailState): void {
  let started = false;

  $effect(() => {
    untrack(() => {
      const named = placeFromUrl(new URL(location.href));
      if (named !== null) {
        // A tapped notification opens /?thread=<id>: the inbox, fetched fresh, with it open.
        if (named.thread !== null && named.view.kind === "inbox") {
          void mail.openFromNotification(named.thread);
        } else {
          if (!sameView(mail.view, named.view)) mail.setView(named.view);
          if (named.thread !== null) mail.open(named.thread);
        }
      }
      const start: Place = { view: mail.view, thread: mail.openId };
      writePlace(start, placeUrl(start, new URL(location.href)), "replace");
      started = true;
    });
    return onPlaceShown((place) => {
      if (!sameView(mail.view, place.view)) mail.setView(place.view);
      if (place.thread === null) {
        if (mail.openId !== null) mail.close();
      } else if (mail.openId !== place.thread) mail.open(place.thread);
    });
  });

  $effect(() => {
    const next: Place = { view: mail.view, thread: mail.openId };
    untrack(() => {
      let current = currentPlace();
      if (!started || current === null) return;
      const closing = threadCloseStep(previousPlace(), current, next);
      if (closing !== null && !overlayOnTop()) {
        current = { view: current.view, thread: null };
        writePlace(current, placeUrl(current, new URL(location.href)), closing);
      }
      const step = placeStep(previousPlace(), current, next);
      if (step !== "none") {
        writePlace(next, placeUrl(next, new URL(location.href)), step);
      }
    });
  });

  const positions = new Map<string, number>();
  let stopRestoring: (() => void) | null = null;
  let shownKey: string | null = null;

  function restore(target: number): void {
    stopRestoring?.();
    const scrollTo = () => scrollPageTo(target);
    scrollTo();
    if (Math.abs(window.scrollY - target) < 1) return;
    // The list is still loading: scroll again as it grows, until it reaches the spot.
    const observer = new ResizeObserver(() => {
      scrollTo();
      if (Math.abs(window.scrollY - target) < 1) stop();
    });
    const timer = setTimeout(stop, RESTORE_MS);
    function stop() {
      observer.disconnect();
      clearTimeout(timer);
      for (const name of TAKEOVER_EVENTS) {
        window.removeEventListener(name, stop, true);
      }
      stopRestoring = null;
    }
    observer.observe(document.body);
    for (const name of TAKEOVER_EVENTS) {
      window.addEventListener(name, stop, { capture: true, passive: true });
    }
    stopRestoring = stop;
  }

  $effect(() => {
    const key = viewKey(mail.view);
    untrack(() => {
      if (shownKey !== null && shownKey !== key)
        restore(positions.get(key) ?? 0);
      shownKey = key;
    });
  });

  // A row the list reveals (a new todo started from another view) takes over from the restore.
  $effect(() => {
    void mail.list.reveal;
    untrack(() => stopRestoring?.());
  });

  $effect(() => {
    function remember() {
      if (stopRestoring === null && shownKey !== null) {
        positions.set(shownKey, window.scrollY);
      }
    }
    window.addEventListener("scroll", remember, { passive: true });
    return () => {
      window.removeEventListener("scroll", remember);
      stopRestoring?.();
    };
  });
}
