// Decrypted subjects for the thread list, kept on the device sealed under the device key.

import { openJson, sealJson } from "./device-key";

/** Where the sealed subjects live: one value. */
export type SubjectSlot = {
  /** the stored value, or undefined */
  get(): Promise<unknown>;
  /** store a value */
  put(value: Uint8Array<ArrayBuffer>): Promise<void>;
};

/** The stored subjects by thread id; empty when there are none or they can't be opened. */
export async function loadSubjects(
  slot: SubjectSlot,
  key: CryptoKey,
): Promise<Map<string, string>> {
  const sealed = await slot.get().catch(() => undefined);
  if (!(sealed instanceof Uint8Array)) return new Map();
  try {
    return new Map(
      await openJson<[string, string][]>(
        key,
        sealed as Uint8Array<ArrayBuffer>,
      ),
    );
  } catch {
    // Sealed under a key this device no longer has.
    return new Map();
  }
}

/**
 * Store `subjects` merged over the ones already stored (which may not have been read yet), so a
 * save never drops them; resolves the merged map.
 */
export async function saveSubjects(
  slot: SubjectSlot,
  key: CryptoKey,
  subjects: ReadonlyMap<string, string>,
): Promise<Map<string, string>> {
  const merged = new Map([...(await loadSubjects(slot, key)), ...subjects]);
  await slot.put(await sealJson(key, [...merged]));
  return merged;
}
