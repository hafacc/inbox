import { afterEach, expect, test } from "bun:test";
import { isAppScroll, scrollPageBy, scrollPageTo } from "./page-scroll";

const realWindow = globalThis.window;

// A window whose page scrolls at once, as an instant scroll does.
function fakeWindow(): { scrollY: number } {
  const page = {
    scrollY: 0,
    scrollBy: ({ top }: ScrollToOptions) => {
      page.scrollY += top ?? 0;
    },
    scrollTo: ({ top }: ScrollToOptions) => {
      page.scrollY = top ?? 0;
    },
  };
  globalThis.window = page as unknown as Window & typeof globalThis;
  return page;
}

afterEach(() => {
  globalThis.window = realWindow;
});

test("the app's own scroll is told apart from the user's, once", () => {
  const page = fakeWindow();
  scrollPageTo(1_200);
  expect(isAppScroll(page.scrollY)).toBe(true);
  // The user scrolls on from there.
  expect(isAppScroll(1_260)).toBe(false);
  page.scrollY = 1_260;
  scrollPageBy(-40);
  expect(isAppScroll(1_220)).toBe(true);
});

test("a user scroll landing elsewhere before the app's event is the user's", () => {
  const page = fakeWindow();
  scrollPageTo(500);
  expect(isAppScroll(page.scrollY + 300)).toBe(false);
  expect(isAppScroll(page.scrollY)).toBe(false);
});
