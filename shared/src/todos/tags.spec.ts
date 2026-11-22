import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  completeTag,
  countTags,
  parseTags,
  suggestTags,
  typingTag,
} from "./tags";

function fixture<Shape>(name: string): Shape {
  return JSON.parse(
    readFileSync(new URL(`../../testdata/${name}`, import.meta.url), "utf8"),
  ) as Shape;
}

const tags = fixture<{
  cases: { name: string; in: string; tags: [number, number][] }[];
}>("tag-fixtures.json");

const completion = fixture<{
  typing: {
    name: string;
    in: string;
    caret: number;
    out: [number, string] | null;
  }[];
  suggest: {
    name: string;
    texts: string[];
    own: string;
    prefix: string;
    limit?: number;
    out: string[];
  }[];
  complete: {
    name: string;
    in: string;
    caret: number;
    pick: string;
    out: string;
    outCaret: number;
  }[];
}>("tag-complete-fixtures.json");

describe("tag fixtures", () => {
  for (const fixtureCase of tags.cases) {
    test(fixtureCase.name, () => {
      expect(
        parseTags(fixtureCase.in).map((tag) => [tag.start, tag.end]),
      ).toEqual(fixtureCase.tags);
    });
  }
});

describe("tag completion fixtures", () => {
  for (const fixtureCase of completion.typing) {
    test(`typing: ${fixtureCase.name}`, () => {
      const typed = typingTag(fixtureCase.in, fixtureCase.caret);
      expect(typed && [typed.start, typed.prefix]).toEqual(fixtureCase.out);
    });
  }
  for (const fixtureCase of completion.suggest) {
    test(`suggest: ${fixtureCase.name}`, () => {
      expect(
        suggestTags(
          countTags(fixtureCase.texts),
          fixtureCase.own,
          fixtureCase.prefix,
          fixtureCase.limit,
        ),
      ).toEqual(fixtureCase.out);
    });
  }
  for (const fixtureCase of completion.complete) {
    test(`complete: ${fixtureCase.name}`, () => {
      const typed = typingTag(fixtureCase.in, fixtureCase.caret);
      expect(typed).not.toBeNull();
      const done = completeTag(
        fixtureCase.in,
        typed!,
        fixtureCase.caret,
        fixtureCase.pick,
      );
      expect([done.text, done.caret]).toEqual([
        fixtureCase.out,
        fixtureCase.outCaret,
      ]);
    });
  }
});
