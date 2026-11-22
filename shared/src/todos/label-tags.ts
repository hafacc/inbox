// A todo's `#tags` read as the user's own Gmail labels (design.md, "Todos"): a tag names a label
// when their `tagKey`s agree, so `#read-later` names "Read Later" and `#work/projects` names the
// nested "Work/Projects". Only user labels count, never Gmail's own or its categories; a tag naming
// none can be made a label (`unlabeledTags`). Android `:core` `todo/LabelTags.kt` is the same, and both run
// shared/testdata/label-tag-fixtures.json.

import { labelNameError } from "../label-names";
import { labelQueryValue } from "../search";
import type { ViewFilter } from "../view-filter";
import {
  type LabelTags,
  labelTag,
  labelTags,
  type TagLabel,
  todoLabelIds,
} from "./tag-index";
import { countTags, parseTags, tagKey } from "./tags";

export {
  type LabelTags,
  labelTag,
  labelTagNames,
  labelTags,
  type TagLabel,
  tagLabel,
  todoLabelIds,
} from "./tag-index";

import type { Todo } from "./todo";

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

/** A tag in use that names no label, and the name of the Gmail label that would. */
export type UnlabeledTag = {
  /** the tag, without `#` */
  tag: string;
  /** the label to make for it: the tag as written, nested at its `/` */
  label: string;
};

/**
 * The tags `texts` use that name no user label of `labels`, one per {@link tagKey} (its spelling
 * most texts use, ignoring case, as first written; the first seen of those tied), in key order,
 * each with the name of the label that
 * would answer to it, nested under the label its leading parts name, if any, as that label is
 * spelled. A tag whose label name another label (Gmail's own included) has already is left out,
 * as Gmail wouldn't make it.
 */
export function unlabeledTags(
  texts: Iterable<string>,
  labels: readonly TagLabel[],
): UnlabeledTag[] {
  const index = labelTags(labels);
  const byKey = new Map<string, { name: string; count: number }>();
  for (const entry of countTags(texts).values()) {
    const key = tagKey(entry.name);
    const best = byKey.get(key);
    if (best === undefined || entry.count > best.count) byKey.set(key, entry);
  }
  return [...byKey]
    .filter(([key]) => !index.has(key))
    .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0))
    .map(([, { name }]) => ({ tag: name, label: tagLabelName(name, index) }))
    .filter(({ label }) => labelNameError(labels, label) === null);
}

// The label name for a tag: its `/` parts as written, empty ones dropped, with the longest leading
// run that names a user label spelled as that label is, so `#work/meetings` nests under "Work".
function tagLabelName(tag: string, index: LabelTags): string {
  const parts = tag.split("/").filter((part) => tagKey(part) !== "");
  for (let depth = parts.length - 1; depth > 0; depth--) {
    const parent = index.get(tagKey(parts.slice(0, depth).join("/")))?.[0];
    if (parent !== undefined) {
      return [parent.name, ...parts.slice(depth)].join("/");
    }
  }
  return parts.join("/");
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
