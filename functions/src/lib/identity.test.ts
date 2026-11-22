import { describe, expect, it } from "bun:test";
import type { TokenPayload } from "google-auth-library";
import { identityFromPayload, uidForGoogleSub } from "./identity.js";

const basePayload: TokenPayload = {
  iss: "https://accounts.google.com",
  sub: "1234567890",
  aud: "client-id",
  iat: 0,
  exp: 0,
  email: "someone@gmail.example.com",
  email_verified: true,
  name: "Some One",
  picture: "https://lh3.googleusercontent.com/a/photo",
};

describe("uidForGoogleSub", () => {
  it("prefixes the Google account id", () => {
    expect(uidForGoogleSub("1234567890")).toBe("google:1234567890");
  });

  it("rejects an empty id", () => {
    expect(() => uidForGoogleSub("")).toThrow();
  });
});

describe("identityFromPayload", () => {
  it("takes the profile fields", () => {
    expect(identityFromPayload(basePayload)).toEqual({
      sub: "1234567890",
      email: "someone@gmail.example.com",
      displayName: "Some One",
      photoUrl: "https://lh3.googleusercontent.com/a/photo",
    });
  });

  it("allows a missing profile", () => {
    const { name: _name, picture: _picture, ...withoutProfile } = basePayload;
    const identity = identityFromPayload(withoutProfile);
    expect(identity.displayName).toBeUndefined();
    expect(identity.photoUrl).toBeUndefined();
  });

  it("requires a verified email", () => {
    expect(() =>
      identityFromPayload({ ...basePayload, email_verified: false }),
    ).toThrow();
    const { email: _email, ...withoutEmail } = basePayload;
    expect(() => identityFromPayload(withoutEmail)).toThrow();
    expect(() => identityFromPayload(undefined)).toThrow();
  });
});
