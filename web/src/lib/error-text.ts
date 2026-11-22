import {
  GmailError,
  isNetworkError,
  isRateLimited,
  isTimeout,
} from "./gmail/request";

/**
 * What to tell the user about a failed call: offline, Gmail's limits (with the seconds left of the
 * pause ending at `pausedUntil`, epoch milliseconds), no answer in time, encrypted mail locked, or
 * Gmail's own words.
 */
export function errorText(error: unknown, pausedUntil = 0): string {
  if (isNetworkError(error)) return "You're offline.";
  if (isRateLimited(error)) {
    const seconds = Math.ceil((pausedUntil - Date.now()) / 1000);
    return seconds > 0
      ? `Gmail asked us to slow down. Trying again in ${seconds} s.`
      : "Gmail asked us to slow down. Trying again shortly.";
  }
  if (isTimeout(error)) return "Gmail didn't answer in time.";
  if (error instanceof Error && error.message === "locked") {
    return "Unlock encrypted mail on this device first.";
  }
  if (error instanceof GmailError) return `Gmail said: ${error.message}`;
  return "Something went wrong.";
}
