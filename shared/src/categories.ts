// Moving a thread between Gmail's categories from the label menu's Category choice. Pure; web uses
// it directly, Android ports it to :core (category-fixtures.json).
import data from "../themes/label-icons.json";
import type { LabelTone } from "./themes";

/** Gmail's Primary category. */
export const PRIMARY_CATEGORY = data.primary.id;

/** Gmail's categories in the order the label menu offers them, Primary first. */
export const CATEGORY_IDS: readonly string[] = [
  PRIMARY_CATEGORY,
  "CATEGORY_PROMOTIONS",
  "CATEGORY_SOCIAL",
  "CATEGORY_UPDATES",
  "CATEGORY_FORUMS",
];

/** Each category's fixed icon id, Primary included. */
export const CATEGORY_CHOICE_ICONS: Readonly<Record<string, string>> = {
  ...data.categories,
  [PRIMARY_CATEGORY]: data.primary.icon,
};

/** Each category's fixed color tone, Primary included. */
export const CATEGORY_CHOICE_TONES: Readonly<Record<string, LabelTone>> = {
  ...(data.categoryTones as Record<string, LabelTone>),
  [PRIMARY_CATEGORY]: data.primary.tone as LabelTone,
};

/**
 * The category a thread is in, from the labels of all its messages: the first category other than
 * Primary that it carries, in {@link CATEGORY_IDS} order; Primary when it carries none.
 */
export function threadCategory(labelIds: readonly string[]): string {
  return (
    CATEGORY_IDS.find(
      (id) => id !== PRIMARY_CATEGORY && labelIds.includes(id),
    ) ?? PRIMARY_CATEGORY
  );
}

/** Labels a `threads.modify` adds and removes. */
export type LabelDelta = {
  /** label ids added */
  add: string[];
  /** label ids removed */
  remove: string[];
};

/**
 * The label change that moves a thread into `chosen`: add it and remove every other category the
 * thread carries. Nothing when the thread is already only in `chosen`.
 */
export function categoryChange(
  labelIds: readonly string[],
  chosen: string,
): LabelDelta {
  const remove = CATEGORY_IDS.filter(
    (id) => id !== chosen && labelIds.includes(id),
  );
  if (remove.length === 0 && threadCategory(labelIds) === chosen) {
    return { add: [], remove: [] };
  }
  return { add: [chosen], remove };
}

/**
 * A newest-first id list with `id` placed by date, so a thread moved out of a bundle stays listed
 * until the lists are fetched again. Unchanged when `id` is already there; ids without a date are
 * passed over; `id` goes last when nothing listed is older.
 */
export function keepListed(
  ids: readonly string[],
  id: string,
  dateOf: (id: string) => number | undefined,
): string[] {
  if (ids.includes(id)) return [...ids];
  const date = dateOf(id) ?? 0;
  const index = ids.findIndex((other) => {
    const otherDate = dateOf(other);
    return otherDate !== undefined && otherDate < date;
  });
  return index === -1
    ? [...ids, id]
    : [...ids.slice(0, index), id, ...ids.slice(index)];
}
