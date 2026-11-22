import { createContext } from "svelte";
import { dev } from "$app/env";
import { resolve } from "$app/paths";

/** The offline worker and its pending update. */
export class PwaState {
  #waiting = $state.raw<ServiceWorker | null>(null);
  #dismissed = $state.raw<ServiceWorker | null>(null);
  // Set once an update is under way, so pressing it again doesn't reload twice.
  #applying = false;

  /** Whether a new version waits and its offer wasn't dismissed. */
  readonly updateReady = $derived(
    this.#waiting !== null && this.#waiting !== this.#dismissed,
  );

  constructor() {
    $effect(() => {
      if (dev) return;
      if (!("serviceWorker" in navigator)) return;
      let registration: ServiceWorkerRegistration | null = null;

      const track = (worker: ServiceWorker | null) => {
        // Without a controller this is the first install, which needs no reload.
        if (!worker || !navigator.serviceWorker.controller) return;
        if (worker.state === "installed") {
          this.#waiting = worker;
        } else {
          worker.addEventListener("statechange", () => {
            if (worker.state === "installed") this.#waiting = worker;
          });
        }
      };
      const check = () => {
        if (document.visibilityState === "visible") registration?.update();
      };

      navigator.serviceWorker
        .register(`${resolve("/")}sw.js`, { scope: resolve("/") })
        .then((found) => {
          registration = found;
          track(found.waiting ?? found.installing);
          found.addEventListener("updatefound", () => track(found.installing));
        })
        .catch((error) => console.error(error));
      document.addEventListener("visibilitychange", check);
      return () => document.removeEventListener("visibilitychange", check);
    });
  }

  /** Switch to the waiting version and reload. */
  applyUpdate = (): void => {
    const waiting = this.#waiting;
    if (!waiting || this.#applying) return;
    this.#applying = true;
    navigator.serviceWorker.addEventListener(
      "controllerchange",
      () => location.reload(),
      { once: true },
    );
    waiting.postMessage("skipWaiting");
  };

  /** Hide the update offer until the next version. */
  dismissUpdate = (): void => {
    this.#dismissed = this.#waiting;
  };
}

/** Context accessors for the one PwaState. */
export const [getPwaState, setPwaState] = createContext<PwaState>();
