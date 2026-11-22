import { describe, expect, it } from "bun:test";
import {
  confirmPositions,
  passkeyName,
  pgpDocFrom,
  withoutPasskey,
  withPasskey,
  wordMatches,
} from "./pgp-doc";

const recoverySealed = {
  salt: "c2FsdA==",
  sealedPrivateKey: "AQID",
  kdf: { name: "argon2id", memoryKiB: 65536, iterations: 3, parallelism: 1 },
};

describe("users/{uid}.pgp", () => {
  it("reads the stored shape, timestamps as epoch ms", () => {
    const doc = pgpDocFrom({
      publicKey: "-----BEGIN PGP PUBLIC KEY BLOCK-----",
      sealed: [
        {
          credentialId: "id",
          salt: "s",
          sealedPrivateKey: "k",
          name: "Pixel 9",
          addedAt: { toMillis: () => 1_800_000_000_000 },
        },
        { credentialId: 5 },
      ],
      recoverySealed,
    });
    expect(doc?.sealed).toEqual([
      {
        credentialId: "id",
        salt: "s",
        sealedPrivateKey: "k",
        name: "Pixel 9",
        addedAt: 1_800_000_000_000,
      },
    ]);
    expect(doc?.recoverySealed).toEqual(recoverySealed as never);
  });

  it("is null when absent or unusable", () => {
    expect(pgpDocFrom(undefined)).toBeNull();
    expect(pgpDocFrom({ publicKey: "x", sealed: [] })).toBeNull();
    expect(
      pgpDocFrom({
        publicKey: "x",
        sealed: [],
        recoverySealed: { ...recoverySealed, kdf: { name: "scrypt" } },
      }),
    ).toBeNull();
  });

  it("adds and removes passkeys", () => {
    const one = {
      credentialId: "a",
      salt: "",
      sealedPrivateKey: "",
      name: "A",
      addedAt: 1,
    };
    const two = { ...one, credentialId: "b", name: "B" };
    expect(withPasskey([one], two)).toEqual([one, two]);
    expect(
      withPasskey([one, two], { ...one, name: "A2" }).map(
        (entry) => entry.name,
      ),
    ).toEqual(["B", "A2"]);
    expect(withoutPasskey([one, two], "a")).toEqual([two]);
  });

  it("keeps passkey names within the rules", () => {
    expect(passkeyName("  Chrome on Mac ")).toBe("Chrome on Mac");
    expect(passkeyName("")).toBe("Passkey");
    expect(passkeyName("x".repeat(150))).toHaveLength(100);
  });

  it("asks back two different words", () => {
    expect(confirmPositions(8, () => 0)).toEqual([0, 1]);
    expect(confirmPositions(8, () => 0.99)).toEqual([6, 7]);
    for (let draw = 0; draw < 50; draw++) {
      const [first, second] = confirmPositions(8);
      expect(first).toBeLessThan(second);
      expect(second).toBeLessThan(8);
    }
    expect(wordMatches(" Orbit ", "orbit")).toBe(true);
    expect(wordMatches("orbits", "orbit")).toBe(false);
  });
});
