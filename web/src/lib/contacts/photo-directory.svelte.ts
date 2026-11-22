import { addressKey } from "@inbox/shared/compose";
import type { Contact } from "./contacts";

/** Contact photo URLs by address, for avatars anywhere in the app. */
class PhotoDirectory {
  #byAddress = $state.raw<ReadonlyMap<string, string>>(new Map());

  /** Take the photos of the address book. */
  set(contacts: readonly Contact[]): void {
    const next = new Map<string, string>();
    for (const contact of contacts) {
      if (contact.photo !== null) {
        next.set(addressKey(contact.email), contact.photo);
      }
    }
    this.#byAddress = next;
  }

  /** The photo URL of an address, or null when the contacts have none. */
  photoFor(email: string): string | null {
    return this.#byAddress.get(addressKey(email)) ?? null;
  }
}

/** The signed-in user's contact photos. */
export const photoDirectory = new PhotoDirectory();
