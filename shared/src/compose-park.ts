/**
 * Parking a message being written on a phone: dragging the full-screen composer's top bar down,
 * or its Minimize button, shrinks it into a strip at the bottom of the screen naming its subject,
 * and a tap on the strip opens it again as it was. Both apps follow these rules; fixtures in
 * `shared/testdata/compose-park-fixtures.json`, which Android's `:core` `ComposePark.kt` also runs.
 */

/** How the parked message started: a new message, a reply (or reply all) or a forward. */
export type ParkKind = "new" | "reply" | "forward";

/** How far the top bar must be dragged down (px on the web, dp on Android) for letting go to park. */
export const PARK_DISTANCE = 96;

/** How fast a shorter drag must be moving down when let go (px or dp per second) to park. */
export const PARK_VELOCITY = 800;

/** The strip's height above the screen's bottom inset (px or dp). */
export const PARK_STRIP_HEIGHT = 56;

/** The strip's text: the subject, else what the message is. */
export function parkTitle(subject: string, kind: ParkKind): string {
  const trimmed = subject.trim();
  if (trimmed !== "") return trimmed;
  else if (kind === "reply") return "Reply";
  else if (kind === "forward") return "Forward";
  else return "New message";
}

/**
 * Whether letting go of the top bar parks the composer, given how far it was dragged down
 * (`dragged`) and how fast it was moving down then (`velocity`, negative when moving up).
 */
export function parksOnRelease(dragged: number, velocity: number): boolean {
  if (dragged <= 0 || velocity <= -PARK_VELOCITY) return false;
  else return dragged >= PARK_DISTANCE || velocity >= PARK_VELOCITY;
}
