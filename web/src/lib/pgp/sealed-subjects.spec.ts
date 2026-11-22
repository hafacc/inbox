import { expect, test } from "bun:test";
import { newWrappingKey, sealJson } from "./device-key";
import {
  loadSubjects,
  type SubjectSlot,
  saveSubjects,
} from "./sealed-subjects";

function memorySlot(initial?: unknown): SubjectSlot & { value: unknown } {
  const slot = {
    value: initial,
    get: async () => slot.value,
    put: async (value: Uint8Array<ArrayBuffer>) => {
      slot.value = value;
    },
  };
  return slot;
}

test("a save keeps the subjects stored before it, newer ones winning", async () => {
  const key = await newWrappingKey();
  const slot = memorySlot(
    await sealJson(key, [
      ["t1", "Lease"],
      ["t2", "Old"],
    ]),
  );
  const merged = await saveSubjects(slot, key, new Map([["t2", "New"]]));
  expect([...merged]).toEqual([
    ["t1", "Lease"],
    ["t2", "New"],
  ]);
  expect([...(await loadSubjects(slot, key))]).toEqual([...merged]);
});

test("subjects sealed under another key, or nothing stored, load as none", async () => {
  const other = await newWrappingKey();
  const key = await newWrappingKey();
  expect(
    (await loadSubjects(memorySlot(await sealJson(other, [["t", "s"]])), key))
      .size,
  ).toBe(0);
  expect((await loadSubjects(memorySlot(), key)).size).toBe(0);
});
