import { describe, expect, test } from "bun:test";
import {
  CLICK_GUARD_MS,
  ClickGuard,
  lockDirection,
  SwipeVelocity,
  slideOffTo,
  startsAtEdge,
} from "./swipe";

describe("slideOffTo", () => {
  test("a row let go past where it acts slides its whole width off its own side", () => {
    expect(slideOffTo("start", 360)).toBe(360);
    expect(slideOffTo("end", 360)).toBe(-360);
  });
});

describe("lockDirection", () => {
  test("stays pending inside the slop", () => {
    expect(lockDirection(5, 3)).toBe("pending");
  });
  test("claims horizontal only when |dx| > 2|dy|", () => {
    expect(lockDirection(30, 10)).toBe("horizontal");
    expect(lockDirection(20, 10)).toBe("vertical");
  });
  test("a mostly vertical drag is a scroll", () => {
    expect(lockDirection(4, 30)).toBe("vertical");
  });
});

test("edge starts are ignored so the browser's back swipe wins", () => {
  expect(startsAtEdge(10, 390)).toBe(true);
  expect(startsAtEdge(380, 390)).toBe(true);
  expect(startsAtEdge(200, 390)).toBe(false);
});

describe("SwipeVelocity", () => {
  test("measures the last 100ms in px per second", () => {
    const tracker = new SwipeVelocity();
    tracker.add(0, 0);
    tracker.add(100, 10);
    tracker.add(150, 30);
    tracker.add(200, 50);
    expect(tracker.velocity(200)).toBe(400);
  });
  test("a touch that rested before letting go has no speed", () => {
    const tracker = new SwipeVelocity();
    tracker.add(0, 0);
    tracker.add(20, 40);
    expect(tracker.velocity(100)).toBe(0);
  });
  test("one sample has no speed, and reset forgets", () => {
    const tracker = new SwipeVelocity();
    tracker.add(0, 0);
    expect(tracker.velocity(0)).toBe(0);
    tracker.add(10, 20);
    tracker.reset();
    expect(tracker.velocity(10)).toBe(0);
  });
});

describe("ClickGuard", () => {
  test("swallows a click that arrives late after the gesture ends", () => {
    const guard = new ClickGuard();
    guard.hold();
    expect(guard.active(5000)).toBe(true);
    guard.release(1000);
    expect(guard.consume(1600)).toBe(true);
  });
  test("swallows only one click", () => {
    const guard = new ClickGuard();
    guard.hold();
    guard.release(1000);
    expect(guard.consume(1100)).toBe(true);
    expect(guard.consume(1200)).toBe(false);
  });
  test("lets a click through once the window passes", () => {
    const guard = new ClickGuard();
    guard.hold();
    guard.release(1000);
    expect(guard.consume(1000 + CLICK_GUARD_MS)).toBe(false);
  });
  test("does nothing when no gesture held it", () => {
    const guard = new ClickGuard();
    guard.release(1000);
    expect(guard.consume(1100)).toBe(false);
  });
});
