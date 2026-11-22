import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  angleOf,
  CORNER_INNER_RING,
  CORNER_OUTER_RING,
  type CornerSide,
  cornerDragStarts,
  cornerHoldWedges,
  cornerSide,
  type HoldWedge,
  hitWedge,
  holdMenuKey,
  inArc,
  linkHoldWedges,
  pillTop,
  ROW_RING,
  rowHoldWedges,
  VIEW_RING,
  viewHoldWedges,
} from "./hold-menu";

type RowMenu = {
  slots: (string | null)[];
  x: number;
  y: number;
  width: number;
  height: number;
};

type Menu =
  | { row: RowMenu }
  | { corner: { inner: string[]; outer: string[]; side: CornerSide } }
  | { view: { ids: string[] } };

type Arc = { id: string; from: number; to: number };

type Fixtures = {
  link: {
    name: string;
    ids: string[];
    x: number;
    y: number;
    width: number;
    expect: Arc[];
  }[];
  corner: {
    name: string;
    inner: string[];
    outer: string[];
    side: CornerSide;
    expect: (Arc & { ring: "inner" | "outer" })[];
  }[];
  row: (RowMenu & { name: string; expect: Arc[] })[];
  drag: {
    name: string;
    press: [number, number];
    at: [number, number];
    reachedWedge: boolean;
    expect: boolean;
  }[];
  hit: {
    name: string;
    menu: Menu;
    center: [number, number];
    points: { at: [number, number]; expect: number }[];
  }[];
  view: { name: string; ids: string[]; expect: Arc[] }[];
  side: { name: string; x: number; width: number; expect: CornerSide }[];
  pillTop: {
    name: string;
    menu: Menu;
    center: [number, number];
    topLimit: number;
    expect: number;
  }[];
};

const fixtures: Fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/hold-menu-fixtures.json", import.meta.url),
    "utf8",
  ),
);

function wedgesOf(menu: Menu): HoldWedge[] {
  if ("row" in menu) {
    const { slots, x, y, width, height } = menu.row;
    return rowHoldWedges(slots, x, y, width, height);
  } else if ("view" in menu) {
    return viewHoldWedges(menu.view.ids);
  } else {
    const { inner, outer, side } = menu.corner;
    return cornerHoldWedges(inner, outer, side);
  }
}

const arc = ({ id, from, to }: HoldWedge): Arc => ({ id, from, to });

describe("linkHoldWedges", () => {
  for (const fixture of fixtures.link) {
    test(fixture.name, () => {
      const wedges = linkHoldWedges(
        fixture.ids,
        fixture.x,
        fixture.y,
        fixture.width,
      );
      expect(wedges.map(arc)).toEqual(fixture.expect);
    });
  }
});

describe("cornerHoldWedges", () => {
  for (const fixture of fixtures.corner) {
    test(fixture.name, () => {
      const wedges = cornerHoldWedges(
        fixture.inner,
        fixture.outer,
        fixture.side,
      );
      expect(wedges).toEqual(
        fixture.expect.map(({ ring, ...rest }) => ({
          ...rest,
          ring: ring === "inner" ? CORNER_INNER_RING : CORNER_OUTER_RING,
        })),
      );
    });
  }
});

describe("rowHoldWedges", () => {
  for (const fixture of fixtures.row) {
    test(fixture.name, () => {
      const { slots, x, y, width, height } = fixture;
      const wedges = rowHoldWedges(slots, x, y, width, height);
      expect(wedges.map(arc)).toEqual(fixture.expect);
      expect(wedges.every((wedge) => wedge.ring === ROW_RING)).toBe(true);
    });
  }
});

describe("viewHoldWedges", () => {
  for (const fixture of fixtures.view) {
    test(fixture.name, () => {
      const wedges = viewHoldWedges(fixture.ids);
      expect(wedges.map(arc)).toEqual(fixture.expect);
      expect(wedges.every((wedge) => wedge.ring === VIEW_RING)).toBe(true);
    });
  }
});

describe("cornerDragStarts", () => {
  for (const fixture of fixtures.drag) {
    test(fixture.name, () => {
      const [, pressY] = fixture.press;
      const [, y] = fixture.at;
      expect(cornerDragStarts(pressY, y, fixture.reachedWedge)).toBe(
        fixture.expect,
      );
    });
  }
});

describe("hitWedge", () => {
  for (const fixture of fixtures.hit) {
    test(fixture.name, () => {
      const wedges = wedgesOf(fixture.menu);
      const [centerX, centerY] = fixture.center;
      for (const point of fixture.points) {
        const [x, y] = point.at;
        expect([point.at, hitWedge(wedges, centerX, centerY, x, y)]).toEqual([
          point.at,
          point.expect,
        ]);
      }
    });
  }
});

describe("cornerSide", () => {
  for (const fixture of fixtures.side) {
    test(fixture.name, () => {
      expect(cornerSide(fixture.x, fixture.width)).toBe(fixture.expect);
    });
  }
});

describe("pillTop", () => {
  for (const fixture of fixtures.pillTop) {
    test(fixture.name, () => {
      const [centerX, centerY] = fixture.center;
      const top = pillTop(
        centerX,
        centerY,
        wedgesOf(fixture.menu),
        fixture.topLimit,
      );
      expect(top).toBeCloseTo(fixture.expect, 6);
    });
  }
});

describe("angles", () => {
  test("angleOf counts counterclockwise from rightward", () => {
    expect(angleOf(0, 0, 1, 0)).toBe(0);
    expect(angleOf(0, 0, 0, -1)).toBe(90);
    expect(angleOf(0, 0, 0, 1)).toBe(270);
  });

  test("inArc wraps past zero", () => {
    expect(inArc(350, -45, 45)).toBe(true);
    expect(inArc(90, -45, 45)).toBe(false);
  });
});

describe("holdMenuKey", () => {
  test("arrows and Tab move round the wedges, Enter picks, Escape closes", () => {
    expect(holdMenuKey("ArrowRight", false, 1, 2)).toEqual({
      kind: "focus",
      index: 0,
    });
    expect(holdMenuKey("Tab", true, 0, 2)).toEqual({ kind: "focus", index: 1 });
    expect(holdMenuKey("Enter", false, 1, 2)).toEqual({
      kind: "pick",
      index: 1,
    });
    expect(holdMenuKey("Escape", false, -1, 2)).toEqual({ kind: "close" });
  });

  test("a letter does nothing, and Enter with no wedge focused picks none", () => {
    expect(holdMenuKey("e", false, 0, 2)).toEqual({ kind: "none" });
    expect(holdMenuKey("Enter", false, -1, 2)).toEqual({ kind: "none" });
  });
});
