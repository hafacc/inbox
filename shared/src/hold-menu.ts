// The round menu's geometry, shared by every round menu on both platforms (Android :core
// HoldMenu.kt, hold-menu-fixtures.json): a row's half disc (a todo's, an email's, a bundle's), a link's half disc, the
// top bar's view menu and a corner button's two-ring quarter disc. Angles are degrees counterclockwise from rightward, and points are
// window pixels (dp on Android) with y growing downward.

/** How long a press rests before a row's or link's round menu opens. */
export const HOLD_MS = 450;
/** How far a press may wander before it stops counting as a hold. */
export const HOLD_SLOP_PX = 10;
/** A release this close to where the press started keeps the menu open for a tap. */
export const HOLD_TAP_PX = 14;
/** How far past the outer edge a slide still counts as on a wedge. */
export const HOLD_SLACK_PX = 28;
/** Above this many pixels from the window's top a half disc has room to open upward. */
export const HOLD_TOP_ROOM_PX = 190;
/** A link or a row held this close to a side turns its disc inward; a row's this close to the right opens leftward. */
export const HOLD_SIDE_ROOM_PX = 110;
/** How far below where it pressed a finger drags, short of any wedge, before a corner button lifts to move. */
export const CORNER_DRAG_DOWN_PX = 24;

// How far (degrees) a link's half disc turns inward near a side.
const SIDE_TURN = 35;

/** The ring a set of wedges fills. */
export type HoldRing = {
  /** radius of its hole */
  inner: number;
  /** radius of its outer edge */
  outer: number;
};

/** A row's round menu: a todo's, an email's or a bundle's. */
export const ROW_RING: HoldRing = { inner: 26, outer: 104 };
/** A link's half disc. */
export const LINK_RING: HoldRing = { inner: 30, outer: 108 };
/** A corner button's inner ring, around the 56 px button. */
export const CORNER_INNER_RING: HoldRing = { inner: 44, outer: 130 };
/** A corner button's outer ring. */
export const CORNER_OUTER_RING: HoldRing = { inner: 130, outer: 212 };

/** One wedge of a round menu, from angle `from` counterclockwise to `to`, across `ring`. */
export type HoldWedge<Id extends string = string> = {
  /** the action it stands for */
  id: Id;
  /** where it starts */
  from: number;
  /** where it ends */
  to: number;
  /** the ring it fills */
  ring: HoldRing;
};

/** The bottom corner a corner button sits in. */
export type CornerSide = "left" | "right";

/** The point `radius` from the center at `degrees`. */
export function polar(
  centerX: number,
  centerY: number,
  radius: number,
  degrees: number,
): [number, number] {
  const radians = (degrees * Math.PI) / 180;
  return [
    centerX + radius * Math.cos(radians),
    centerY - radius * Math.sin(radians),
  ];
}

/** The angle of a point seen from the center, from 0 up to 360. */
export function angleOf(
  centerX: number,
  centerY: number,
  x: number,
  y: number,
): number {
  const degrees = (Math.atan2(centerY - y, x - centerX) * 180) / Math.PI;
  return (degrees + 360) % 360;
}

/** Whether `angle` lies on the arc from `from` counterclockwise to `to`. */
export function inArc(angle: number, from: number, to: number): boolean {
  return (angle - from + 720) % 360 <= to - from;
}

/** Whether a pointer at `x`, `y` slid away from `press`, past {@link HOLD_TAP_PX}. */
export function slidAway(
  press: { x: number; y: number },
  x: number,
  y: number,
): boolean {
  return Math.hypot(x - press.x, y - press.y) >= HOLD_TAP_PX;
}

/**
 * The index of the wedge under a point, or -1. A point a little past the outermost wedge at its
 * angle (by {@link HOLD_SLACK_PX}) still counts for it, so a long slide doesn't fall off.
 */
export function hitWedge(
  wedges: readonly HoldWedge[],
  centerX: number,
  centerY: number,
  x: number,
  y: number,
): number {
  const distance = Math.hypot(x - centerX, y - centerY);
  const angle = angleOf(centerX, centerY, x, y);
  let outermost = -1;
  for (const [index, wedge] of wedges.entries()) {
    if (!inArc(angle, wedge.from, wedge.to)) continue;
    if (distance >= wedge.ring.inner && distance < wedge.ring.outer) {
      return index;
    }
    if (
      outermost < 0 ||
      wedge.ring.outer > (wedges[outermost]?.ring.outer ?? 0)
    ) {
      outermost = index;
    }
  }
  const edge = wedges[outermost]?.ring.outer ?? 0;
  if (outermost >= 0 && distance >= edge && distance < edge + HOLD_SLACK_PX) {
    return outermost;
  } else {
    return -1;
  }
}

/** The arc, in degrees, a row's wedges share: a third of a disc. */
export const ROW_ARC = 120;

/**
 * A row's wedges around a long press at `x`, `y` in a window `width` wide (a todo's, an email's or
 * a bundle's; the menu key and screen readers press on its dot, avatar or mark): `slots` share
 * {@link ROW_ARC} equally, the first before the second counterclockwise. It opens straight up (two
 * slots: up and left, up and right); right and down near the top; turned 45° right near the left
 * edge, at the bottom too; near the right edge it opens leftward instead, mirrored. A null slot
 * leaves its place empty (a done todo has no Pin).
 */
export function rowHoldWedges<Id extends string>(
  slots: readonly (Id | null)[],
  x: number,
  y: number,
  width: number,
): HoldWedge<Id>[] {
  const leftward = x > width - HOLD_SIDE_ROOM_PX;
  const sideways = leftward || x < HOLD_SIDE_ROOM_PX;
  const facing = y <= HOLD_TOP_ROOM_PX ? -45 : sideways ? 45 : 90;
  const middle = leftward ? 180 - facing : facing;
  const share = ROW_ARC / slots.length;
  return slots.flatMap((id, index) => {
    if (id === null) return [];
    const from = leftward
      ? middle - ROW_ARC / 2 + index * share
      : middle + ROW_ARC / 2 - (index + 1) * share;
    return [{ id, from, to: from + share, ring: ROW_RING }];
  });
}

/**
 * A link's wedges around a finger at `x`, `y` in a window `width` wide: a half disc above it, or
 * below near the top, turned inward near either side, `ids` left to right as it opens upward.
 */
export function linkHoldWedges<Id extends string>(
  ids: readonly Id[],
  x: number,
  y: number,
  width: number,
): HoldWedge<Id>[] {
  const up = y >= HOLD_TOP_ROOM_PX;
  let middle = up ? 90 : 270;
  if (x < HOLD_SIDE_ROOM_PX) middle += up ? -SIDE_TURN : SIDE_TURN;
  if (x > width - HOLD_SIDE_ROOM_PX) middle += up ? SIDE_TURN : -SIDE_TURN;
  const share = 180 / ids.length;
  const start = middle - 90;
  return ids.map((id, index) => {
    const from = start + (ids.length - 1 - index) * share;
    return { id, from, to: from + share, ring: LINK_RING };
  });
}

/**
 * A corner button's wedges: a quarter disc opening up and inward from the button, `inner` on the
 * ring next to it and `outer` on the ring beyond, each listed from the top down to the side. On
 * the left the disc mirrors, opening up and to the right.
 */
export function cornerHoldWedges<Id extends string>(
  inner: readonly Id[],
  outer: readonly Id[],
  side: CornerSide,
): HoldWedge<Id>[] {
  const ring = (ids: readonly Id[], shape: HoldRing): HoldWedge<Id>[] => {
    const share = 90 / ids.length;
    return ids.map((id, index) => {
      const start = 90 + index * share;
      const end = start + share;
      if (side === "right") return { id, from: start, to: end, ring: shape };
      else return { id, from: 180 - end, to: 180 - start, ring: shape };
    });
  };
  return [...ring(inner, CORNER_INNER_RING), ...ring(outer, CORNER_OUTER_RING)];
}

/** The top bar's view menu, around the view's name. */
export const VIEW_RING: HoldRing = { inner: 40, outer: 124 };

// The arc (degrees) the view menu fills: from down and to the left round to rightward, so it
// opens down from a name at the top left and stays on screen.
const VIEW_ARC_FROM = 240;
const VIEW_ARC_TO = 360;

/**
 * The top bar's view wedges around the view's name: a third of a disc opening downward, `ids`
 * sharing it from rightward round to down and to the left, in the side menu's order.
 */
export function viewHoldWedges<Id extends string>(
  ids: readonly Id[],
): HoldWedge<Id>[] {
  const share = (VIEW_ARC_TO - VIEW_ARC_FROM) / ids.length;
  return ids.map((id, index) => ({
    id,
    from: VIEW_ARC_TO - (index + 1) * share,
    to: VIEW_ARC_TO - index * share,
    ring: VIEW_RING,
  }));
}

/**
 * Whether a press on a corner button that began at height `pressY` and is now at `y` drags the
 * button rather than choosing from its menu: it went more than {@link CORNER_DRAG_DOWN_PX} down,
 * before reaching any wedge.
 */
export function cornerDragStarts(
  pressY: number,
  y: number,
  reachedWedge: boolean,
): boolean {
  return !reachedWedge && y - pressY > CORNER_DRAG_DOWN_PX;
}

/** The bottom corner nearer to `x` in a window `width` wide; the middle goes right. */
export function cornerSide(x: number, width: number): CornerSide {
  if (x < width / 2) return "left";
  else return "right";
}

/**
 * The top of the pill naming the menu: 48 above the disc's top, unless that is above `topLimit`,
 * else 14 below its bottom.
 */
export function pillTop(
  centerX: number,
  centerY: number,
  wedges: readonly HoldWedge[],
  topLimit: number,
): number {
  const edges = wedges.flatMap((wedge) =>
    [wedge.from, (wedge.from + wedge.to) / 2, wedge.to].map(
      (angle) => polar(centerX, centerY, wedge.ring.outer, angle)[1],
    ),
  );
  const topmost = Math.min(centerY, ...edges);
  const lowest = Math.max(centerY, ...edges);
  if (topmost - 48 > topLimit) return topmost - 48;
  else return lowest + 14;
}

/** What a key does while a round menu is open: move focus to a wedge, pick one, close, or nothing. */
export type HoldMenuKey =
  | { kind: "focus"; index: number }
  | { kind: "pick"; index: number }
  | { kind: "close" }
  | { kind: "none" };

/**
 * What `key` does in an open round menu of `count` wedges, `focused` the index of the wedge with
 * focus or -1. Every key is the menu's while it is open; one that does nothing still goes no
 * further, so the list's shortcuts don't act under it.
 */
export function holdMenuKey(
  key: string,
  shiftKey: boolean,
  focused: number,
  count: number,
): HoldMenuKey {
  const step = (by: number): HoldMenuKey => ({
    kind: "focus",
    index: (focused + by + count) % count,
  });
  if (key === "Escape") return { kind: "close" };
  else if (key === "Tab") return step(shiftKey ? -1 : 1);
  else if (key === "ArrowRight" || key === "ArrowDown") return step(1);
  else if (key === "ArrowLeft" || key === "ArrowUp") return step(-1);
  else if ((key === "Enter" || key === " ") && focused >= 0) {
    return { kind: "pick", index: focused };
  } else return { kind: "none" };
}
