// User label names: nesting by `/`, the order labels are listed in, which names can be used, and
// renames that carry nested labels along.

/** A label as naming reads it. */
export type NamedLabel = {
  /** Gmail label id */
  id: string;
  /** full name, `/` between nested parts */
  name: string;
  /** `user` for the user's own labels, `system` for Gmail's */
  type?: string;
};

/** The parent part of a nested name (`Home/Bills` → `Home`), or null. */
export function parentName(name: string): string | null {
  const index = name.lastIndexOf("/");
  return index <= 0 ? null : name.slice(0, index);
}

/** The last part of a nested name. */
export function leafName(name: string): string {
  return name.slice(name.lastIndexOf("/") + 1);
}

/** A label's full name from its parent and own name; a `/` typed in its own name becomes `-`. */
export function nestedName(parent: string | null, leaf: string): string {
  const own = leaf.trim().replace(/\//g, "-");
  return parent ? `${parent}/${own}` : own;
}

function folded(text: string): string {
  return text.toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");
}

function compareText(left: string, right: string): number {
  if (left < right) return -1;
  else return left > right ? 1 : 0;
}

/**
 * Label names in list order: part by part, ignoring case and accents, so each label comes right
 * before the labels nested under it (`Work`, `Work/A`, `Work 2`).
 */
export function compareLabelNames(left: string, right: string): number {
  const leftParts = left.split("/").map(folded);
  const rightParts = right.split("/").map(folded);
  const length = Math.min(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index++) {
    const order = compareText(leftParts[index]!, rightParts[index]!);
    if (order !== 0) return order;
  }
  return leftParts.length - rightParts.length || compareText(left, right);
}

/** Labels sorted by {@link compareLabelNames}. */
export function sortLabels<Label extends { name: string }>(
  labels: readonly Label[],
): Label[] {
  return [...labels].sort((left, right) =>
    compareLabelNames(left.name, right.name),
  );
}

/**
 * The labels a rename touches: the label and every user label nested under it (its name and a `/`,
 * in any case), with their new names.
 */
export function renames(
  labels: readonly NamedLabel[],
  id: string,
  newName: string,
): { id: string; name: string }[] {
  const label = labels.find((candidate) => candidate.id === id);
  const name = newName.trim();
  if (!label || name === "" || name === label.name) return [];
  const prefix = `${label.name}/`.toLowerCase();
  return [
    { id, name },
    ...labels
      .filter(
        (candidate) =>
          candidate.type === "user" &&
          candidate.name.toLowerCase().startsWith(prefix),
      )
      .map((candidate) => ({
        id: candidate.id,
        name: name + candidate.name.slice(label.name.length),
      })),
  ];
}

/** Why a full label name can't be used, or null when it can; `exceptId` is the label being renamed. */
export function labelNameError(
  labels: readonly NamedLabel[],
  name: string,
  exceptId: string | null = null,
): string | null {
  const trimmed = name.trim();
  if (trimmed === "" || leafName(trimmed).trim() === "") return "Enter a name";
  const taken = labels.some(
    (label) =>
      label.id !== exceptId &&
      label.name.toLowerCase() === trimmed.toLowerCase(),
  );
  return taken ? "A label has that name" : null;
}

/**
 * The label Gmail counts as named `name`, ignoring case as Gmail does (so making `name` again is
 * refused as a duplicate), or null.
 */
export function labelNamed<Label extends NamedLabel>(
  labels: readonly Label[],
  name: string,
): Label | null {
  const wanted = name.trim().toLowerCase();
  return labels.find((label) => label.name.toLowerCase() === wanted) ?? null;
}
