import { expect, test } from "bun:test";
import {
  failureStep,
  isFailure,
  mailtoMessage,
  stepText,
  unsubscribeOf,
} from "./unsubscribe-model";

test("offers the preferred way, or nothing", () => {
  expect(unsubscribeOf(null, null)).toBeNull();
  expect(
    unsubscribeOf(
      "<https://news.example/u?x=1>, <mailto:u@news.example>",
      "List-Unsubscribe=One-Click",
    )?.preferred,
  ).toBe("oneClick");
  expect(
    unsubscribeOf("<mailto:u@news.example?subject=stop>", null)?.preferred,
  ).toBe("mailto");
});

test("the request mail fills in a subject and body when missing", () => {
  expect(mailtoMessage({ address: "u@x", subject: "", body: "" })).toEqual({
    to: [{ name: "", email: "u@x" }],
    subject: "Unsubscribe",
    markdown: "Please unsubscribe me from this list.",
  });
  expect(
    mailtoMessage({ address: "u@x", subject: "stop", body: "now" }).subject,
  ).toBe("stop");
});

test("words each step", () => {
  expect(stepText("idle")).toBe("Unsubscribe");
  expect(stepText("working")).toBe("Unsubscribing…");
  expect(stepText("unsubscribed")).toBe("Unsubscribed");
  expect(stepText("requested")).toBe("Request sent");
  expect(stepText("failed")).toBe("Couldn't unsubscribe");
  expect(stepText("signIn")).toBe("Sign in again to unsubscribe");
  expect(stepText("offline")).toBe("Can't unsubscribe offline");
});

test("a failure says what the user can do about it", () => {
  expect(
    failureStep(
      { code: "functions/failed-precondition", message: "reauth" },
      true,
    ),
  ).toBe("signIn");
  expect(failureStep({ code: "functions/internal" }, false)).toBe("offline");
  expect(failureStep({ code: "functions/unavailable" }, true)).toBe("offline");
  expect(failureStep({ code: "functions/internal" }, true)).toBe("failed");
  expect(failureStep(null, true)).toBe("failed");
  expect(
    (["failed", "signIn", "offline"] as const).every((step) => isFailure(step)),
  ).toBe(true);
  expect(isFailure("working")).toBe(false);
});
