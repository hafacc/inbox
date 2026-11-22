import { expect, test } from "bun:test";
import {
  isActive,
  reasonOf,
  refusalText,
  setupErrorText,
  setupFrom,
  setupSteps,
  statusText,
} from "./address-model";

test("reads the setup and words each state", () => {
  expect(setupFrom(null)).toBeNull();
  expect(setupFrom({ status: "nope" })).toBeNull();
  const setup = setupFrom({
    status: "verifying",
    error: { code: "confirm-by-hand", message: "m" },
    updatedAt: 1,
  })!;
  expect(setup.status).toBe("verifying");
  expect(statusText(setup, "test@hafa.cc")).toBe(
    "Confirming your Gmail address…",
  );
  expect(setupErrorText(setup)).toContain("email from Cloudflare");
  expect(isActive(setup)).toBe(false);
  const active = setupFrom({ status: "active", error: null })!;
  expect(isActive(active)).toBe(true);
  expect(statusText(active, "test@hafa.cc")).toBe(
    "Mail to test@hafa.cc arrives here.",
  );
  expect(setupErrorText(active)).toBeNull();
});

test("progress steps follow the status", () => {
  const states = (status: string | null) =>
    setupSteps(status ? setupFrom({ status }) : null).map((step) => step.state);
  expect(states(null)).toEqual(["waiting", "waiting"]);
  expect(states("pending")).toEqual(["active", "waiting"]);
  expect(states("verifying")).toEqual(["done", "active"]);
  expect(states("active")).toEqual(["done", "done"]);
});

test("reads the refusal reason of a callable error", () => {
  expect(reasonOf({ details: { reason: "taken" } })).toBe("taken");
  expect(reasonOf({ details: { reason: "other" } })).toBeNull();
  expect(reasonOf(new Error("x"))).toBeNull();
});

test("a had-address refusal names the old address", () => {
  expect(
    refusalText({
      details: { reason: "had-address", address: "test@hafa.cc" },
    }),
  ).toBe(
    "Your account already had test@hafa.cc — you can only take that one back.",
  );
  expect(refusalText({ details: { reason: "taken" } })).toBe(
    "That name is taken.",
  );
  expect(refusalText(new Error("x"))).toBeNull();
});
