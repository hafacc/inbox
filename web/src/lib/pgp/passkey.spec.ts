import { describe, expect, it } from "bun:test";
import {
  creationOptions,
  deviceLabel,
  fromBase64Url,
  prfSecret,
  RP_ID,
  requestOptions,
  rpIdFor,
  toBase64Url,
} from "./passkey";

const user = {
  uid: "u1",
  gmail: "jordan@example.com",
  address: "test@hafa.cc",
};

describe("passkeys", () => {
  it("uses inbox.hafa.cc, and localhost while developing", () => {
    expect(rpIdFor("inbox.hafa.cc")).toBe(RP_ID);
    expect(rpIdFor("localhost")).toBe("localhost");
    expect(rpIdFor("127.0.0.1")).toBe("localhost");
  });

  it("creates a resident, verified passkey with a PRF salt", () => {
    const salt = new Uint8Array(32).fill(7);
    const options = creationOptions(user, salt, RP_ID, new Uint8Array(32));
    expect(options.rp).toEqual({ id: RP_ID, name: "inbox" });
    expect(new TextDecoder().decode(options.user.id as Uint8Array)).toBe("u1");
    expect(options.user.name).toBe("jordan@example.com");
    expect(options.user.displayName).toBe("test@hafa.cc");
    expect(options.pubKeyCredParams.map((param) => param.alg)).toEqual([
      -7, -257,
    ]);
    expect(options.authenticatorSelection).toEqual({
      residentKey: "required",
      userVerification: "required",
    });
    expect(options.extensions).toEqual({
      prf: { eval: { first: salt } },
    } as never);
  });

  it("asks every sealed passkey for its own salt", () => {
    const sealed = [
      {
        credentialId: "AQID",
        salt: "BAUG",
        sealedPrivateKey: "x",
        name: "a",
        addedAt: 0,
      },
      {
        credentialId: "Bw",
        salt: "CAk",
        sealedPrivateKey: "y",
        name: "b",
        addedAt: 0,
      },
    ];
    const options = requestOptions(sealed, RP_ID, new Uint8Array(32));
    expect(options.rpId).toBe(RP_ID);
    expect(options.userVerification).toBe("required");
    expect(
      options.allowCredentials?.map((entry) => [...(entry.id as Uint8Array)]),
    ).toEqual([[1, 2, 3], [7]]);
    const prf = (
      options.extensions as {
        prf: { evalByCredential: Record<string, { first: Uint8Array }> };
      }
    ).prf;
    expect(Object.keys(prf.evalByCredential)).toEqual(["AQID", "Bw"]);
    expect([...(prf.evalByCredential.AQID?.first ?? [])]).toEqual([4, 5, 6]);
  });

  it("takes a 32-byte PRF result only", () => {
    expect(prfSecret(undefined)).toBeNull();
    expect(prfSecret({ enabled: true })).toBeNull();
    expect(prfSecret({ results: { first: new Uint8Array(16) } })).toBeNull();
    const secret = prfSecret({
      results: { first: new Uint8Array(32).fill(9).buffer },
    });
    expect(secret?.length).toBe(32);
    expect(secret?.[0]).toBe(9);
  });

  it("round-trips base64url", () => {
    const bytes = new Uint8Array([251, 255, 0, 62, 63]);
    expect(toBase64Url(bytes)).toBe("-_8APj8");
    expect([...fromBase64Url("-_8APj8")]).toEqual([...bytes]);
    expect([...fromBase64Url("+/8APj8=")]).toEqual([...bytes]);
  });

  it("names the device", () => {
    expect(
      deviceLabel(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
      ),
    ).toBe("Chrome on Mac");
    expect(
      deviceLabel(
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
      ),
    ).toBe("Chrome on Android");
    expect(
      deviceLabel(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
      ),
    ).toBe("Safari on iPhone");
    expect(deviceLabel("something")).toBe("Browser");
  });
});
