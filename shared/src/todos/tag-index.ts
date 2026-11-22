// The user's labels by the tags that name them, and the labels a todo's text names (see
// label-tags.ts). Imported by relative path from functions/, so it imports only tags.ts, which
// imports nothing.

import { parseTags, tagKey } from "./tags.js";

/** A Gmail label as tags read it. */
export type TagLabel = { id: string; name: string; type: string };

/** User labels by the key of the tag that names them, each key's labels in name order. */
export type LabelTags<Label extends TagLabel = TagLabel> = ReadonlyMap<
  string,
  readonly Label[]
>;

const LETTER = /\p{L}/u;

/** A label's name as a tag, without `#` (its {@link tagKey}); null when no tag can name it (no letter). */
export function labelTag(name: string): string | null {
  const key = tagKey(name);
  return LETTER.test(key) ? key : null;
}

function byName(left: TagLabel, right: TagLabel): number {
  const leftFolded = left.name.toLowerCase();
  const rightFolded = right.name.toLowerCase();
  if (leftFolded !== rightFolded) return leftFolded < rightFolded ? -1 : 1;
  if (left.name !== right.name) return left.name < right.name ? -1 : 1;
  return 0;
}

/** The user labels of `labels` that a tag can name, by that tag's key. */
export function labelTags<Label extends TagLabel>(
  labels: readonly Label[],
): LabelTags<Label> {
  const index = new Map<string, Label[]>();
  for (const label of [...labels].sort(byName)) {
    if (label.type !== "user") continue;
    const tag = labelTag(label.name);
    if (tag === null) continue;
    const named = index.get(tag);
    if (named) named.push(label);
    else index.set(tag, [label]);
  }
  return index;
}

/** The tags `index` can offer as a `#tag` is typed, one per key, in key order. */
export function labelTagNames(index: LabelTags): string[] {
  return [...index.keys()].sort();
}

/** The label a tag (without `#`) names, the first by name when several do; null when none does. */
export function tagLabel<Label extends TagLabel>(
  index: LabelTags<Label>,
  name: string,
): Label | null {
  return index.get(tagKey(name))?.[0] ?? null;
}

/** The ids of the labels `text`'s tags name, in the order the tags come, each once. */
export function todoLabelIds(text: string, index: LabelTags): string[] {
  const ids: string[] = [];
  for (const span of parseTags(text)) {
    const named = index.get(tagKey(text.slice(span.start + 1, span.end)));
    for (const label of named ?? []) {
      if (!ids.includes(label.id)) ids.push(label.id);
    }
  }
  return ids;
}
