// How a thread beside the open one is drawn: as light text until a swipe brings it near, so the
// open thread doesn't pay for its neighbors' message frames while nothing slides.

import type { MessageView } from "./thread-detail";

/** How many characters of a message's text a light preview shows; more is off screen anyway. */
export const PREVIEW_TEXT_CHARS = 1200;

/**
 * Whether a sideways swipe that moved the open thread `page` pixels (rightward positive) brings in
 * the neighbor on `side` (-1 the one before, 1 the one after), so it is drawn in full.
 */
export function swipeReveals(page: number, side: -1 | 1): boolean {
  return page * side < 0;
}

/** The text a light preview shows for an expanded message: its plain text, else its snippet. */
export function previewText(message: MessageView): string {
  const text = message.body.text?.trim() || message.snippet;
  return text.length > PREVIEW_TEXT_CHARS
    ? `${text.slice(0, PREVIEW_TEXT_CHARS).trimEnd()}…`
    : text;
}
