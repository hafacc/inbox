/** How long setup may sit unfinished before the app points it out. */
export const SETUP_STALL_MS = 30 * 60 * 1000;

/** `users/{uid}.addressSetup` as the notice reads it. */
export type SetupState = {
  /** `pending`, `verifying`, `active` or `releasing` */
  status: string;
  /** `error.code`, or null */
  errorCode: string | null;
  /** when the server last changed it, in milliseconds, or null */
  updatedAt: number | null;
};

/**
 * Why the @hafa.cc address needs the user: setup reported a problem (`error`), or it has sat
 * pending or verifying for over half an hour (`stalled`); null when nothing is wrong. The server
 * retries Cloudflare by itself, so its `cloudflare` code counts only once setup has stalled.
 */
export function addressTrouble(
  setup: SetupState | null,
  now: number,
): "error" | "stalled" | null {
  if (setup === null) return null;
  const unfinished = setup.status === "pending" || setup.status === "verifying";
  if (setup.errorCode !== null && setup.errorCode !== "cloudflare") {
    return "error";
  }
  if (
    unfinished &&
    setup.updatedAt !== null &&
    now - setup.updatedAt > SETUP_STALL_MS
  ) {
    return "stalled";
  }
  return null;
}

/**
 * What the inbox's address notice shows: `working` while setup is under way and fine (a calm
 * line, no dots), `error` once {@link addressTrouble} finds a problem (the reason, "Fix it" and the
 * dots), or null when there is no setup or it finished.
 */
export function addressNoticeState(
  setup: SetupState | null,
  now: number,
): "working" | "error" | null {
  if (setup === null) return null;
  if (addressTrouble(setup, now) !== null) return "error";
  return setup.status === "pending" || setup.status === "verifying"
    ? "working"
    : null;
}
