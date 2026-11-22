// Test-only key pairs for the people in shared/testdata/pgp-fixtures.json `keys`, made fresh on
// every run so no private key is ever saved.
import { generateKeyPair, type KeyPair } from "@inbox/shared/pgp";
import fixtures from "../../../../shared/testdata/pgp-fixtures.json";

/** A test key pair. */
export type TestKey = KeyPair & { address: string; name: string };

type Id = "alice" | "bob" | "carol";

async function make(id: Id): Promise<[Id, TestKey]> {
  const person = fixtures.keys[id];
  return [id, { ...person, ...(await generateKeyPair(person)) }];
}

/** Alice (test@hafa.cc), Bob (bob@example.com) and Carol (carol@example.org). */
export const TEST_KEYS = Object.fromEntries(
  await Promise.all((["alice", "bob", "carol"] as const).map(make)),
) as Record<Id, TestKey>;
