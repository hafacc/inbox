import { httpsCallable } from "firebase/functions";
import { functions } from "../firebase";
import type { AddressSetup } from "./address-model";

/** What the address callables answer. */
export type AddressResult = { address: string; setup?: AddressSetup };

/** Claim a name; it is the user's at once and setup starts. */
export async function claimAddress(name: string): Promise<AddressResult> {
  const call = httpsCallable<{ name: string }, AddressResult>(
    functions(),
    "claimAddress",
  );
  return (await call({ name })).data;
}

/** The address this account held before it was deleted, the only one it can take back; or null. */
export async function formerAddress(): Promise<string | null> {
  const call = httpsCallable<undefined, { address: string | null }>(
    functions(),
    "formerAddress",
  );
  return (await call()).data.address;
}

/** Look again now whether the Gmail address was confirmed. */
export async function checkAddress(): Promise<AddressResult> {
  const call = httpsCallable<undefined, AddressResult>(
    functions(),
    "checkAddress",
  );
  return (await call()).data;
}
