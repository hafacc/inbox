// The letters an avatar shows. Web and Android (:core Avatar.kt) both run
// shared/testdata/initials-fixtures.json.

/** Who an avatar stands for: a display name (may be empty) and an address. */
export type InitialsPerson = {
  /** display name, empty when none */
  name: string;
  /** address */
  email: string;
};

/**
 * An avatar's letters: the first letters of a name's first and last words (only the first word's
 * when `single`), else the address's first character, upper-cased; "?" when there is neither.
 *
 * Words are runs of letters, digits and combining marks, so `Mary-Kate O'Neil` gives `MN`, and a
 * letter keeps the accents that follow it.
 */
export function initials(person: InitialsPerson, single = false): string {
  const letters = person.name
    .replace(/[^\p{L}\p{N}\p{M}]+/gu, " ")
    .split(" ")
    .map((word) => /[\p{L}\p{N}]\p{M}*/u.exec(word)?.[0] ?? "")
    .filter((letter) => letter !== "");
  if (letters.length === 0) {
    const [first] = person.email.trim();
    return (first ?? "?").toUpperCase();
  } else if (letters.length === 1 || single) {
    return letters[0]!.toUpperCase();
  } else {
    return `${letters[0]}${letters.at(-1)}`.toUpperCase();
  }
}
