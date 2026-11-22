import { describe, expect, test } from "bun:test";
import { HOLD_SLOP_PX, ROW_RING } from "@inbox/shared/hold-menu";
import {
  HoldPress,
  pillLeft,
  swallowReleaseClick,
  wedgePath,
} from "./hold-menu";

describe("wedgePath", () => {
  test("runs along the outer edge, then back along the hole", () => {
    const path = wedgePath(0, 0, {
      id: "pin",
      from: 0,
      to: 90,
      ring: ROW_RING,
    });
    expect(path.startsWith("M 104 0 A 104 104 0 0 0")).toBe(true);
    expect(path.endsWith("A 26 26 0 0 1 26 0 Z")).toBe(true);
  });
});

describe("pillLeft", () => {
  test("centered on the disc, kept inside the window", () => {
    expect(pillLeft(30, 390)).toBe(162);
    expect(pillLeft(195, 390)).toBe(195);
    expect(pillLeft(370, 390)).toBe(228);
  });
});

describe("swallowReleaseClick", () => {
  function click(target: EventTarget): boolean {
    const event = new Event("click", { cancelable: true });
    target.dispatchEvent(event);
    return event.defaultPrevented;
  }

  test("swallows the release's click, and only that one", () => {
    const target = new EventTarget();
    swallowReleaseClick(target, 400);
    expect(click(target)).toBe(true);
    expect(click(target)).toBe(false);
  });

  test("a quick tap after a release that sent no click still lands", () => {
    const target = new EventTarget();
    swallowReleaseClick(target, 400);
    target.dispatchEvent(new Event("pointerdown"));
    expect(click(target)).toBe(false);
  });

  test("lets go once its time is up", async () => {
    const target = new EventTarget();
    swallowReleaseClick(target, 5);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(click(target)).toBe(false);
  });
});

describe("HoldPress", () => {
  function fakeTimers() {
    const pending = new Map<number, () => void>();
    let next = 1;
    return {
      timers: {
        set: (run: () => void, _ms: number) => {
          const timer = next++;
          pending.set(timer, run);
          return timer;
        },
        clear: (timer: number) => {
          pending.delete(timer);
        },
      },
      fire: () => {
        const runs = [...pending.values()];
        pending.clear();
        for (const run of runs) run();
      },
    };
  }

  test("a press that rests holds", () => {
    const { timers, fire } = fakeTimers();
    const press = new HoldPress(timers);
    const held: string[] = [];
    press.start(1, 100, 100, () => held.push("held"));
    press.move(1, 100 + HOLD_SLOP_PX, 100);
    expect(press.pending).toBe(true);
    fire();
    expect(held).toEqual(["held"]);
    expect(press.pending).toBe(false);
  });

  test("a drag before the hold is left to the scroll or swipe", () => {
    const { timers, fire } = fakeTimers();
    const press = new HoldPress(timers);
    const held: string[] = [];
    press.start(1, 100, 100, () => held.push("held"));
    press.move(1, 100, 100 + HOLD_SLOP_PX + 1);
    fire();
    expect(held).toEqual([]);
    expect(press.pending).toBe(false);
  });

  test("another finger's move and a lift", () => {
    const { timers, fire } = fakeTimers();
    const press = new HoldPress(timers);
    const held: string[] = [];
    press.start(1, 100, 100, () => held.push("held"));
    press.move(2, 400, 400);
    expect(press.pending).toBe(true);
    press.cancel();
    fire();
    expect(held).toEqual([]);
  });
});
