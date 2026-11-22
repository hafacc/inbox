import { describe, expect, it } from "bun:test";
import {
  newActionToken,
  parseActionInput,
  parseActionToken,
} from "./action-token.js";

describe("action tokens", () => {
  it("round-trips the uid and nonce", () => {
    const { token, nonce } = newActionToken("google:123");
    expect(parseActionToken(token)).toEqual({ uid: "google:123", nonce });
  });

  it("makes a fresh nonce each time", () => {
    expect(newActionToken("u").nonce).not.toBe(newActionToken("u").nonce);
  });

  it("rejects malformed tokens", () => {
    expect(parseActionToken("")).toBeUndefined();
    expect(parseActionToken("abc")).toBeUndefined();
    expect(parseActionToken("abc.short")).toBeUndefined();
    expect(parseActionToken(`abc.${"a".repeat(24)}.extra`)).toBeUndefined();
  });
});

describe("parseActionInput", () => {
  it("accepts done", () => {
    expect(parseActionInput({ token: "t", action: "done" })).toEqual({
      token: "t",
      action: "done",
    });
  });

  it("rejects snooze, an unknown action or no token", () => {
    expect(
      parseActionInput({ token: "t", action: "snooze", snoozeUntil: 5 }),
    ).toBeUndefined();
    expect(parseActionInput({ token: "t", action: "spam" })).toBeUndefined();
    expect(parseActionInput({ action: "done" })).toBeUndefined();
    expect(parseActionInput(null)).toBeUndefined();
  });
});
