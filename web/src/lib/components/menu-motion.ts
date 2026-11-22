import { cubicIn, cubicOut } from "svelte/easing";
import { prefersReducedMotion } from "svelte/motion";
import type { TransitionConfig } from "svelte/transition";

/** Close a menu or popover: it fades and sinks a little, or vanishes when motion is reduced. */
export function closeMenu(_node: Element): TransitionConfig {
  if (prefersReducedMotion.current) return { duration: 0 };
  return {
    duration: 120,
    easing: cubicIn,
    css: (progress) =>
      `opacity:${progress};transform:translateY(${(1 - progress) * 6}px) scale(${0.98 + progress * 0.02})`,
  };
}

/** Close a bottom sheet: it slides back down, or vanishes when motion is reduced. */
export function closeSheet(_node: Element): TransitionConfig {
  if (prefersReducedMotion.current) return { duration: 0 };
  return {
    duration: 180,
    easing: cubicIn,
    css: (progress) => `transform:translateY(${(1 - progress) * 100}%)`,
  };
}

/** Fade a sheet's scrim in or out, or not at all when motion is reduced. */
export function fadeScrim(_node: Element): TransitionConfig {
  if (prefersReducedMotion.current) return { duration: 0 };
  return { duration: 180, css: (progress) => `opacity:${progress}` };
}

/** Which way a menu's steps move: forward to a deeper step, or back. */
export type StepDirection = "forward" | "back";

/**
 * Slide a menu step in or out sideways: forward steps come in from the end and push the last one
 * out to the start, back steps the other way. Nothing moves when motion is reduced.
 */
export function slideStep(
  _node: Element,
  { direction, leaving }: { direction: () => StepDirection; leaving: boolean },
): TransitionConfig {
  if (prefersReducedMotion.current) return { duration: 0 };
  const sign = (direction() === "forward") === leaving ? -1 : 1;
  return {
    duration: 200,
    easing: cubicOut,
    css: (progress) =>
      `opacity:${progress};transform:translateX(${(1 - progress) * sign * 24}%)`,
  };
}
