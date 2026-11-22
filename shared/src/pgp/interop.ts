// Checks openpgp.js against Android's Bouncy Castle on the decrypt cases in pgp-fixtures.json, with
// keys made on each run; android/core's Gradle build runs it around :core:test.
//   bun src/pgp/interop.ts write <file>   this run's keys and the cases' messages, made here
//   bun src/pgp/interop.ts check <file>   decrypt the messages Android wrote, to the cases' expect
// A file is {"keys": {id: {publicKey, privateKey}}, "messages": {case name: base64}}. It holds
// private keys, so it goes in a build directory only.

import { isDeepStrictEqual } from "node:util";
import { fromBase64, toBase64 } from "./bytes";
import {
  type DecryptOutcome,
  decryptOutcome,
  fixtureMessage,
  type MessageRecipe,
  type TestPerson,
  testKeys,
} from "./test-fixtures";

type DecryptCase = MessageRecipe & {
  name: string;
  decryptionKey: string;
  verificationKeys: string[];
  expect: DecryptOutcome;
};

type InteropFile = {
  keys: Record<string, { publicKey: string; privateKey: string }>;
  messages: Record<string, string>;
};

const fixtures: {
  keys: Record<string, TestPerson>;
  decrypt: DecryptCase[];
} = await Bun.file(
  new URL("../../testdata/pgp-fixtures.json", import.meta.url),
).json();

async function write(path: string): Promise<void> {
  const keys = await testKeys(fixtures.keys);
  const messages: Record<string, string> = {};
  for (const entry of fixtures.decrypt) {
    messages[entry.name] = toBase64(await fixtureMessage(entry, keys));
  }
  const file: InteropFile = {
    keys: Object.fromEntries(
      Object.entries(keys).map(([id, { publicKey, privateKey }]) => [
        id,
        { publicKey, privateKey },
      ]),
    ),
    messages,
  };
  await Bun.write(path, JSON.stringify(file));
}

async function check(path: string): Promise<void> {
  const file: InteropFile = await Bun.file(path).json();
  const failures: string[] = [];
  for (const entry of fixtures.decrypt) {
    const message = file.messages[entry.name];
    if (message === undefined) {
      failures.push(`${entry.name}: no message in ${path}`);
      continue;
    }
    const outcome = await decryptOutcome(
      fromBase64(message),
      file.keys[entry.decryptionKey]!.privateKey,
      entry.verificationKeys.map((id) => file.keys[id]!.publicKey),
    );
    if (!isDeepStrictEqual(outcome, entry.expect)) {
      failures.push(
        `${entry.name}:\n  expected ${JSON.stringify(entry.expect)}\n  got      ${JSON.stringify(outcome)}`,
      );
    }
  }
  if (failures.length > 0) {
    console.error(
      `openpgp.js did not read ${failures.length} of Android's messages as expected:\n${failures.join("\n")}`,
    );
    process.exit(1);
  }
  console.log(
    `openpgp.js read all ${fixtures.decrypt.length} of Android's messages as expected`,
  );
}

const [command, path] = process.argv.slice(2);
if (command === "write" && path !== undefined) {
  await write(path);
} else if (command === "check" && path !== undefined) {
  await check(path);
} else {
  console.error("usage: bun src/pgp/interop.ts write|check <file>");
  process.exit(2);
}
