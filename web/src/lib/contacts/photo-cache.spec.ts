import { expect, test } from "bun:test";
import { photoSize, sizedPhotoUrl } from "./photo-cache";

test("the size goes in sz, replacing one already there", () => {
  expect(sizedPhotoUrl("https://lh3.googleusercontent.com/a/abc", 96)).toBe(
    "https://lh3.googleusercontent.com/a/abc?sz=96",
  );
  expect(
    sizedPhotoUrl("https://lh3.googleusercontent.com/a/abc?sz=50&x=1", 64),
  ).toBe("https://lh3.googleusercontent.com/a/abc?sz=64&x=1");
});

test("an avatar asks for the next cached size up in device pixels", () => {
  expect(photoSize(32, 2)).toBe(64);
  expect(photoSize(36, 2)).toBe(96);
  expect(photoSize(20, 1)).toBe(32);
  expect(photoSize(28, 3)).toBe(96);
  expect(photoSize(200, 2)).toBe(128);
});
