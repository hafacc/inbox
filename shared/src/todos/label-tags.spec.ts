import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { ViewFilter } from "../view-filter";
import {
  labelTag,
  labelTagNames,
  labelTags,
  labelTodos,
  renameTags,
  type TagLabel,
  taggedFilter,
  taggedThreadIds,
  tagLabel,
  todoLabelIds,
  unlabeledTags,
} from "./label-tags";
import { countTags, suggestTags, tagKey } from "./tags";

type Fixtures = {
  labels: TagLabel[];
  keys: { name: string; in: string; key: string; tag: string | null }[];
  carried: { name: string; text: string; ids: string[] }[];
  color: { name: string; tag: string; id: string | null }[];
  suggest: {
    name: string;
    texts: string[];
    own: string;
    prefix: string;
    limit?: number;
    out: string[];
  }[];
  rename: { name: string; in: string; from: string; to: string; out: string }[];
  list: {
    name: string;
    labelId: string;
    todos: { id: string; text: string }[];
    ids: string[];
  }[];
  threads: {
    name: string;
    threadIds: string[];
    listed: string[];
    ids: string[];
  }[];
  filter: { name: string; filter: ViewFilter; out: ViewFilter }[];
  unlabeled: {
    name: string;
    texts: string[];
    out: { tag: string; label: string }[];
  }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../testdata/label-tag-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

const index = labelTags(fixtures.labels);

describe("label tag fixtures", () => {
  for (const fixtureCase of fixtures.keys) {
    test(`keys: ${fixtureCase.name}`, () => {
      expect([tagKey(fixtureCase.in), labelTag(fixtureCase.in)]).toEqual([
        fixtureCase.key,
        fixtureCase.tag,
      ]);
    });
  }
  for (const fixtureCase of fixtures.carried) {
    test(`carried: ${fixtureCase.name}`, () => {
      expect(todoLabelIds(fixtureCase.text, index)).toEqual(fixtureCase.ids);
    });
  }
  for (const fixtureCase of fixtures.color) {
    test(`color: ${fixtureCase.name}`, () => {
      expect(tagLabel(index, fixtureCase.tag)?.id ?? null).toEqual(
        fixtureCase.id,
      );
    });
  }
  for (const fixtureCase of fixtures.suggest) {
    test(`suggest: ${fixtureCase.name}`, () => {
      expect(
        suggestTags(
          countTags(fixtureCase.texts),
          fixtureCase.own,
          fixtureCase.prefix,
          fixtureCase.limit,
          labelTagNames(index),
        ),
      ).toEqual(fixtureCase.out);
    });
  }
  for (const fixtureCase of fixtures.rename) {
    test(`rename: ${fixtureCase.name}`, () => {
      expect(
        renameTags(fixtureCase.in, fixtureCase.from, fixtureCase.to),
      ).toEqual(fixtureCase.out);
    });
  }
  for (const fixtureCase of fixtures.list) {
    test(`list: ${fixtureCase.name}`, () => {
      expect(
        labelTodos(fixtureCase.todos, fixtureCase.labelId, index).map(
          (todo) => todo.id,
        ),
      ).toEqual(fixtureCase.ids);
    });
  }
  for (const fixtureCase of fixtures.unlabeled) {
    test(`unlabeled: ${fixtureCase.name}`, () => {
      expect(unlabeledTags(fixtureCase.texts, fixtures.labels)).toEqual(
        fixtureCase.out,
      );
    });
  }
  for (const fixtureCase of fixtures.threads) {
    test(`threads: ${fixtureCase.name}`, () => {
      expect(
        taggedThreadIds(fixtureCase.threadIds, fixtureCase.listed),
      ).toEqual(fixtureCase.ids);
    });
  }
  for (const fixtureCase of fixtures.filter) {
    test(`filter: ${fixtureCase.name}`, () => {
      expect(taggedFilter(fixtureCase.filter, index)).toEqual(fixtureCase.out);
    });
  }
});
