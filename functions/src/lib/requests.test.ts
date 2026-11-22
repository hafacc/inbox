import { describe, expect, it } from "bun:test";
import { parseSignInInput } from "./requests.js";

describe("parseSignInInput", () => {
  it("accepts a code and redirect URI", () => {
    expect(
      parseSignInInput({
        code: "4/0abc",
        redirectUri: "postmessage",
        extra: 1,
      }),
    ).toEqual({
      code: "4/0abc",
      redirectUri: "postmessage",
    });
  });

  it("accepts an Android server auth code, which has no redirect URI", () => {
    expect(parseSignInInput({ code: "4/0abc", redirectUri: "" })).toEqual({
      code: "4/0abc",
      redirectUri: "",
    });
  });

  it("rejects missing, empty or mistyped fields", () => {
    for (const data of [
      undefined,
      null,
      "code",
      {},
      { code: "4/0abc" },
      { code: "", redirectUri: "postmessage" },
      { code: 1, redirectUri: "postmessage" },
      { code: "4/0abc", redirectUri: null },
    ]) {
      expect(parseSignInInput(data)).toBeUndefined();
    }
  });
});
