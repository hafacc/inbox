import { expect, test } from "bun:test";
import { sendSettingsFrom } from "./send-settings";

test("missing settings read as no signature, Gmail's default From, and varying by up to 10 minutes", () => {
  expect(sendSettingsFrom(undefined)).toEqual({
    signature: "",
    sendAsDefault: null,
    varySendTimes: true,
    varySendMaxMinutes: 10,
  });
});

test("stored settings are read; wrong types fall back", () => {
  expect(
    sendSettingsFrom({
      settings: {
        signature: "— Jordan",
        sendAsDefault: "test@hafa.cc",
        varySendTimes: false,
        varySendMaxMinutes: 25,
      },
    }),
  ).toEqual({
    signature: "— Jordan",
    sendAsDefault: "test@hafa.cc",
    varySendTimes: false,
    varySendMaxMinutes: 25,
  });
  expect(
    sendSettingsFrom({
      settings: {
        sendAsDefault: "",
        varySendTimes: "no" as unknown as boolean,
        varySendMaxMinutes: 99,
      },
    }),
  ).toMatchObject({
    sendAsDefault: null,
    varySendTimes: true,
    varySendMaxMinutes: 30,
  });
});
