// A todo's `#tags` read as the user's own Gmail labels (design.md, "Todos"): a tag names a label
// when their `tagKey`s agree, so `#read-later` names "Read Later" and `#work/projects` names the
// nested "Work/Projects". Only user labels count, never Gmail's own or its categories, and nothing
// here creates a label. Android `:core` `todo/LabelTags.kt` is the same, and both run
// shared/testdata/label-tag-fixtures.json.

import { labelQueryValue } from "../search";
import type { ViewFilter } from "../view-filter";
import { parseTags, tagKey } from "./tags";
import type { Todo } from "./todo";

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

/** The todos whose text names the label `labelId`, in their order: a label's own list. */
export function labelTodos<Item extends Pick<Todo, "text">>(
  todos: readonly Item[],
  labelId: string,
  index: LabelTags,
): Item[] {
  return todos.filter((todo) =>
    todoLabelIds(todo.text, index).includes(labelId),
  );
}

/** Whether `text` names a label that Gmail's `label:` takes as `value`. */
export function namesLabelQuery(
  text: string,
  index: LabelTags,
  value: string,
): boolean {
  const wanted = labelQueryValue(value);
  for (const span of parseTags(text)) {
    const named = index.get(tagKey(text.slice(span.start + 1, span.end)));
    if (named?.some((label) => labelQueryValue(label.name) === wanted)) {
      return true;
    }
  }
  return false;
}

/**
 * `text` with the tags naming the label `oldName` renamed to name `newName`, and those naming a
 * label nested under it moved along (`#work/projects` to `#job/projects` when Work becomes Job).
 * A renamed tag is spelled as {@link labelTag} has it; the nested parts keep their spelling. Text
 * is left as it is when either name can't be a tag.
 */
export function renameTags(
  text: string,
  oldName: string,
  newName: string,
): string {
  const oldKey = labelTag(oldName);
  const newTag = labelTag(newName);
  if (oldKey === null || newTag === null || oldKey === newTag) return text;
  const depth = oldKey.split("/").length;
  let renamed = text;
  for (const span of parseTags(text).reverse()) {
    const name = text.slice(span.start + 1, span.end);
    const key = tagKey(name);
    let replacement: string | null = null;
    if (key === oldKey) {
      replacement = newTag;
    } else if (key.startsWith(`${oldKey}/`)) {
      const rest = name
        .split("/")
        .filter((part) => tagKey(part) !== "")
        .slice(depth)
        .join("/");
      replacement = `${newTag}/${rest}`;
    }
    if (replacement !== null) {
      renamed =
        renamed.slice(0, span.start + 1) +
        replacement +
        renamed.slice(span.end);
    }
  }
  return renamed;
}

/**
 * The Gmail threads a list adds for their todos: each of `threadIds` (the emails whose todo the
 * list shows through its text) that `listed` doesn't hold, each once, in order. Its row shows, the
 * todo inside, though Gmail doesn't give the thread the label or match it to the search.
 */
export function taggedThreadIds(
  threadIds: readonly string[],
  listed: readonly string[],
): string[] {
  const held = new Set(listed);
  const ids: string[] = [];
  for (const threadId of threadIds) {
    if (held.has(threadId)) continue;
    held.add(threadId);
    ids.push(threadId);
  }
  return ids;
}

/** `filter` as it checks a thread added by {@link taggedThreadIds}: no user label of `index` required. */
export function taggedFilter(filter: ViewFilter, index: LabelTags): ViewFilter {
  const tagged = new Set(
    [...index.values()].flatMap((labels) => labels.map((label) => label.id)),
  );
  return {
    ...filter,
    requires: filter.requires.filter((id) => !tagged.has(id)),
  };
}
