import { untrack } from "svelte";
import type { Attachment } from "svelte/attachments";
import { prefersReducedMotion } from "svelte/motion";
import { SvelteMap } from "svelte/reactivity";
import type { MailState } from "../mail-state.svelte";
import { openMark } from "../open-timing";
import type { ThreadDetail } from "../thread-detail";
import { Closing } from "./closing";

/** How long a thread or bundle takes to open or close, or 0 when motion is reduced. */
export function openDuration(): number {
  return prefersReducedMotion.current ? 0 : 200;
}

const EASING = "cubic-bezier(0.33, 1, 0.68, 1)";
// Cuts off what hasn't grown in yet with a rounded bottom like the card's, leaving room for its shadow above.
const CLIP = "inset(-48px 0 0 0 round 0 0 20px 20px)";

type Frame = { height: string } & Keyframe;

function frameOf(node: HTMLElement): Frame {
  const style = getComputedStyle(node);
  return {
    height: `${node.getBoundingClientRect().height}px`,
    marginTop: style.marginTop,
    marginBottom: style.marginBottom,
    marginLeft: style.marginLeft,
    marginRight: style.marginRight,
  };
}

// Each growing element's way to show itself already scrolled (see `openScrolled`).
const scrolledOpens = new WeakMap<HTMLElement, (scroll: () => void) => void>();

/**
 * Scroll the page with `scroll` (at once, not smoothly) to a place inside the thread around `inside`,
 * so it opens already there: a thread still growing out of its row stops growing and is shown at its
 * full height from the band it had reached, that band widening to fill the screen in the time left.
 */
export function openScrolled(inside: HTMLElement, scroll: () => void): void {
  let node: HTMLElement | null = inside;
  while (node !== null && !scrolledOpens.has(node)) node = node.parentElement;
  const reveal = node === null ? undefined : scrolledOpens.get(node);
  if (reveal === undefined) scroll();
  else reveal(scroll);
}

function rowFrame(rowHeight: number): Frame {
  return {
    height: `${rowHeight}px`,
    marginTop: "0px",
    marginBottom: "0px",
    marginLeft: "0px",
    marginRight: "0px",
  };
}

/**
 * Grow or shrink an element from the height it had, `from` pixels, to the one it has now, its
 * content fading in: a message of an open thread folding or unfolding. Nothing moves when motion
 * is reduced.
 */
export function resizeFrom(node: HTMLElement, from: number): void {
  const duration = openDuration();
  const to = node.getBoundingClientRect().height;
  if (duration === 0 || from === to) return;
  node.style.overflow = "hidden";
  const resizing = node.animate(
    [{ height: `${from}px` }, { height: `${to}px` }],
    { duration, easing: EASING },
  );
  node.firstElementChild?.animate([{ opacity: 0 }, { opacity: 1 }], {
    duration,
    easing: EASING,
  });
  const done = () => {
    node.style.overflow = "";
  };
  resizing.finished.then(done, done);
}

/**
 * Grow an opened thread or bundle out of the row it replaces, or shrink it back into one.
 *
 * The element starts at `rowHeight` with no margin and grows to its own height and margins; once
 * `closing` turns true it shrinks back the same way and holds there, for the caller to swap the row
 * back in (see {@link closingOpen}). Opening it again mid-close grows it back from where it is. Unless
 * `timed` is false (a bundle), the end of its growing is a phase of the thread open being timed.
 */
export function unfold(
  rowHeight: number,
  closing: () => boolean,
  timed = true,
): Attachment<HTMLElement> {
  return (node) => {
    let animation: Animation | null = null;
    let shrinking = false;
    let endsAt = 0;
    let target: Frame = rowFrame(rowHeight);
    let shown: boolean | null = null;
    // Set once it is shown at full height instead of growing (see `openScrolled`).
    let revealed = false;

    const play = (from: Frame, to: Frame, duration: number) => {
      animation?.cancel();
      node.style.clipPath = CLIP;
      const running = node.animate([from, to], {
        duration,
        easing: EASING,
        fill: shrinking ? "forwards" : "none",
      });
      animation = running;
      target = to;
      endsAt = performance.now() + duration;
      running.finished.then(
        () => {
          if (!shrinking) {
            node.style.clipPath = "";
            if (timed) openMark("open motion done");
          }
        },
        () => {},
      );
    };

    // Content that changes size while it grows (its messages loading) is grown to, not jumped to.
    const observer = new ResizeObserver(() => {
      const left = endsAt - performance.now();
      if (
        revealed ||
        shrinking ||
        animation?.playState !== "running" ||
        left < 16
      ) {
        return;
      }
      const height = `${node.firstElementChild?.getBoundingClientRect().height ?? 0}px`;
      if (height !== target.height) {
        play(frameOf(node), { ...target, height }, left);
      }
    });
    if (node.firstElementChild) observer.observe(node.firstElementChild);

    scrolledOpens.set(node, (scroll) => {
      const timeLeft = endsAt - performance.now();
      if (shrinking || animation?.playState !== "running" || timeLeft < 16) {
        scroll();
        return;
      }
      const band = node.getBoundingClientRect();
      revealed = true;
      animation.cancel();
      animation = null;
      scroll();
      const full = node.getBoundingClientRect();
      const top = band.top - full.top;
      const right = full.right - band.right;
      const bottom = full.bottom - band.bottom;
      const left = band.left - full.left;
      node.style.clipPath = CLIP;
      const revealing = node.animate(
        [
          {
            clipPath: `inset(${top}px ${right}px ${bottom}px ${left}px round 20px)`,
          },
          { clipPath: CLIP },
        ],
        { duration: timeLeft, easing: EASING },
      );
      animation = revealing;
      endsAt = performance.now() + timeLeft;
      const done = () => {
        if (animation === revealing) {
          node.style.clipPath = "";
          if (timed) openMark("open motion done");
        }
      };
      revealing.finished.then(done, () => {});
    });

    $effect(() => {
      const closingNow = closing();
      if (closingNow === shown) return;
      shown = closingNow;
      untrack(() => {
        const duration = openDuration();
        const started = animation !== null;
        const current = frameOf(node);
        const wasRunning = animation?.playState === "running";
        animation?.cancel();
        node.style.clipPath = "";
        shrinking = closingNow;
        revealed = false;
        if (duration === 0) return;
        const natural = frameOf(node);
        if (closingNow) {
          play(wasRunning ? current : natural, rowFrame(rowHeight), duration);
        } else {
          play(started ? current : rowFrame(rowHeight), natural, duration);
        }
      });
    });
    return () => {
      scrolledOpens.delete(node);
      observer.disconnect();
      animation?.cancel();
    };
  };
}

/**
 * What a list has open, kept while it shrinks closed (see {@link Closing}): `open` gives each open
 * id and what it shows, `inPlace` whether the open one just took another's place. Call while the
 * list's component initializes; its timers stop when the component goes.
 */
export function closingOpen<T>(
  open: () => ReadonlyMap<string, T>,
  inPlace: () => boolean = () => false,
): Closing<T> {
  const closing = new Closing<T>(new SvelteMap(), openDuration);
  $effect.pre(() => {
    const now = open();
    const swapped = inPlace();
    untrack(() => closing.update(now, swapped));
  });
  $effect(() => () => closing.stop());
  return closing;
}

/**
 * The open thread `open()` gives (a list's, a bundle's), kept with the messages it showed while it
 * shrinks closed; a thread a swipe swapped out goes at once. Call while the list's component
 * initializes.
 */
export function closingThread(
  mail: MailState,
  open: () => string | null,
): Closing<ThreadDetail | null> {
  return closingOpen(
    () => {
      const id = open();
      const detail = mail.detail;
      return new Map(
        id === null ? [] : [[id, detail?.id === id ? detail : null]],
      );
    },
    () => mail.openedInPlace,
  );
}
