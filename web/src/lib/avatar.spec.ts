import { expect, test } from "bun:test";
import { avatarColors } from "./avatar";

test("an address always gets the same theme colors", () => {
  expect(avatarColors("Maya@x.example.com")).toEqual(
    avatarColors("maya@x.example.com"),
  );
  expect(avatarColors("maya@x.example.com").fill).toMatch(
    /^var\(--color-avatar-\d\)$/,
  );
});
