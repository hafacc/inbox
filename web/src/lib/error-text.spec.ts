import { expect, test } from "bun:test";
import { errorText } from "./error-text";
import { FetchFailedError, GmailError } from "./gmail/request";

test("says what went wrong in the user's terms", () => {
  expect(errorText(new FetchFailedError(new TypeError("x")))).toBe(
    "You're offline.",
  );
  expect(
    errorText(
      new GmailError(429, "limit", "rateLimitExceeded"),
      Date.now() + 4_500,
    ),
  ).toBe("Gmail asked us to slow down. Trying again in 5 s.");
  expect(errorText(new GmailError(429, "limit", "rateLimitExceeded"))).toBe(
    "Gmail asked us to slow down. Trying again shortly.",
  );
  expect(errorText(new Error("locked"))).toBe(
    "Unlock encrypted mail on this device first.",
  );
  expect(errorText(new GmailError(400, "Bad label"))).toBe(
    "Gmail said: Bad label",
  );
  expect(errorText(new Error("boom"))).toBe("Something went wrong.");
});
