import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import type { Mailbox } from "./compose/address";
import {
  applyCompletion,
  awaitsValue,
  type Candidate,
  type Chip,
  COMMON_OPERATORS,
  type CompletionContext,
  completions,
  type Edit,
  formatChip,
  gmailQueryText,
  isLocalChip,
  OPERATOR_DETAILS,
  OPERATOR_EXAMPLES,
  OPERATORS,
  type Operator,
  OWN_TERMS,
  type ParsedQuery,
  parseQuery,
  queryText,
  unchip,
  withoutOperator,
} from "./search";

type Fixtures = {
  parse: {
    name: string;
    text: string;
    cursor: number | null;
    tags?: boolean;
    expect: ParsedQuery;
  }[];
  format: { chip: Chip; expect: string }[];
  query: { chips: Chip[]; free: string; expect: string }[];
  gmailQuery: { chips: Chip[]; free: string; expect: string }[];
  completions: {
    name: string;
    context: CompletionContext;
    now: string;
    timeZone: string;
    labels?: string[];
    contacts?: Mailbox[];
    expect: Candidate[];
  }[];
  apply: {
    name: string;
    text: string;
    context: CompletionContext;
    candidate: Candidate;
    expect: Edit;
  }[];
  unchip: {
    chips: Chip[];
    index: number;
    free: string;
    expect: ReturnType<typeof unchip>;
  }[];
  local: { chip: Chip; expect: boolean }[];
  awaitsValue: {
    name: string;
    text: string;
    cursor: number | null;
    expect: boolean;
  }[];
  withoutOperator: {
    name: string;
    text: string;
    cursor: number;
    expect: string;
  }[];
  help: {
    common: Operator[];
    operators: { operator: Operator; detail: string; example: string }[];
    own: { term: string; detail: string }[];
  };
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/search-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("search fixtures (must match Android)", () => {
  test("help lists every operator with its detail and example", () => {
    expect(
      OPERATORS.map((operator) => ({
        operator,
        detail: OPERATOR_DETAILS[operator],
        example: OPERATOR_EXAMPLES[operator],
      })),
    ).toEqual(fixtures.help.operators);
    expect<Operator[]>([...COMMON_OPERATORS]).toEqual(fixtures.help.common);
    expect(OWN_TERMS.map(([term, detail]) => ({ term, detail }))).toEqual(
      fixtures.help.own,
    );
  });
  for (const fixture of fixtures.parse) {
    test(`parse: ${fixture.name}`, () => {
      expect(parseQuery(fixture.text, fixture.cursor, fixture.tags)).toEqual(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.format) {
    test(`format: ${fixture.expect}`, () => {
      expect(formatChip(fixture.chip)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.query) {
    test(`query: ${JSON.stringify(fixture.expect)}`, () => {
      expect(queryText(fixture.chips, fixture.free)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.gmailQuery) {
    test(`gmailQuery: ${JSON.stringify(fixture.expect)}`, () => {
      expect(gmailQueryText(fixture.chips, fixture.free)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.completions) {
    test(`completions: ${fixture.name}`, () => {
      expect(
        completions(fixture.context, {
          now: Date.parse(fixture.now),
          timeZone: fixture.timeZone,
          labels: fixture.labels,
          contacts: fixture.contacts,
        }),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.apply) {
    test(`apply: ${fixture.name}`, () => {
      expect(
        applyCompletion(fixture.text, fixture.context, fixture.candidate),
      ).toEqual(fixture.expect);
    });
  }
  for (const fixture of fixtures.unchip) {
    test(`unchip: ${fixture.expect.free}`, () => {
      expect(unchip(fixture.chips, fixture.index, fixture.free)).toEqual(
        fixture.expect,
      );
    });
  }
  for (const fixture of fixtures.local) {
    test(`local: ${formatChip(fixture.chip)}`, () => {
      expect(isLocalChip(fixture.chip)).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.awaitsValue) {
    test(`awaitsValue: ${fixture.name}`, () => {
      expect(
        awaitsValue(parseQuery(fixture.text, fixture.cursor).context),
      ).toBe(fixture.expect);
    });
  }
  for (const fixture of fixtures.withoutOperator) {
    test(`withoutOperator: ${fixture.name}`, () => {
      const parsed = parseQuery(fixture.text, fixture.cursor);
      expect(
        parsed.context && withoutOperator(parsed.free, parsed.context),
      ).toBe(fixture.expect);
    });
  }
});

test("chips round-trip through query text", () => {
  const text = 'from:"Maya Chen" -is:unread subject:(a b) larger:5M report';
  const parsed = parseQuery(text, null);
  expect(queryText(parsed.chips, parsed.free)).toBe(
    'from:"Maya Chen" -is:unread larger:5M subject:(a b) report',
  );
  const again = parseQuery(queryText(parsed.chips, parsed.free), null);
  expect(again.chips).toEqual(parsed.chips);
  expect(again.free).toBe(parsed.free);
});

test("a completed value becomes a chip on the next parse", () => {
  const parsed = parseQuery("in:sp", 5);
  const context = parsed.context!;
  const [spam] = completions(context, {
    now: 0,
    timeZone: "UTC",
  });
  const edit = applyCompletion(parsed.free, context, spam!);
  expect(parseQuery(edit.text, edit.cursor).chips).toEqual([
    { operator: "in", value: "spam", negated: false },
  ]);
});

test("an unchipped chip stays text until the cursor leaves it", () => {
  const back = unchip(
    [{ operator: "from", value: "a", negated: false }],
    0,
    "",
  );
  expect(parseQuery(back.free, back.cursor).chips).toEqual([]);
  expect(parseQuery(`${back.free} `, back.cursor + 1).chips).toHaveLength(1);
});
