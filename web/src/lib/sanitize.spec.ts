import { beforeAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";
import {
  type cleanMessageHtml as Clean,
  missingPartLabel,
  OPENS_ONLINE,
} from "./sanitize";

type SanitizeCase = {
  name: string;
  html: string;
  cidUrls: Record<string, string>;
  cidMissing?: Record<string, string>;
  hasRemoteImages: boolean;
  contains: string[];
  absent: string[];
  colorSchemes: string[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../../shared/testdata/sanitize-fixtures.json", import.meta.url),
    "utf8",
  ),
) as {
  cases: SanitizeCase[];
  opensOnline: string;
  missingLabels: { filename: string; size: number; expect: string }[];
};

let clean: typeof Clean;

beforeAll(async () => {
  // DOMPurify needs a DOM; jsdom stands in for the browser's.
  Object.assign(globalThis, { window: new JSDOM("").window });
  ({ cleanMessageHtml: clean } = await import("./sanitize"));
});

/** The fixture file's normalization: `<br>` forms, no whitespace between tags, single spaces. */
function normalized(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, "<br>")
    .replace(/>\s+</g, "><")
    .replace(/\s+/g, " ");
}

describe("shared sanitize fixtures", () => {
  for (const fixture of fixtures.cases) {
    test(fixture.name, () => {
      const result = clean(
        fixture.html,
        new Map(Object.entries(fixture.cidUrls)),
        new Map(Object.entries(fixture.cidMissing ?? {})),
      );
      expect(result.hasRemoteImages).toBe(fixture.hasRemoteImages);
      expect(result.colorSchemes).toEqual(fixture.colorSchemes);
      const html = normalized(result.html);
      for (const wanted of fixture.contains) expect(html).toContain(wanted);
      for (const unwanted of fixture.absent) {
        expect(html.toLowerCase()).not.toContain(unwanted.toLowerCase());
      }
    });
  }
});

test("an unfetched part says what both apps say", () => {
  expect(OPENS_ONLINE).toBe(fixtures.opensOnline);
  for (const { filename, size, expect: label } of fixtures.missingLabels) {
    expect(missingPartLabel(filename, size)).toBe(label);
  }
});

describe("remote images", () => {
  const html =
    '<img src="https://a.example/x.png"><img src="//b.example/y.png">' +
    '<img srcset="https://a.example/x.png 1x, https://c.example/z.png 2x">' +
    "<div style=\"background:url('https://d.example/bg.png')\">t</div>";

  test("lists each remote image once, protocol-relative as https", () => {
    expect(clean(html, new Map()).remoteImageUrls).toEqual([
      "https://a.example/x.png",
      "https://b.example/y.png",
      "https://c.example/z.png",
      "https://d.example/bg.png",
    ]);
  });

  test("leaves remote images in place for the frame to block", () => {
    const out = clean(html, new Map()).html;
    expect(out).toContain('src="https://a.example/x.png"');
    expect(out).toContain('src="//b.example/y.png"');
    expect(out).toContain("https://c.example/z.png 2x");
    expect(out).toContain("https://d.example/bg.png");
  });
});

test("links lose click-tracking wrappers", () => {
  const out = clean(
    '<a href="https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&amp;sa=D">x</a>',
    new Map(),
  ).html;
  expect(out).toContain('href="https://example.com/page"');
});

test("the same message cleans once; other inline images clean again", () => {
  const html = '<p>Hi</p><img src="cid:logo">';
  const first = clean(html, new Map());
  expect(clean(html, new Map())).toBe(first);
  const withLogo = clean(html, new Map([["logo", "data:image/png;base64,AA"]]));
  expect(withLogo).not.toBe(first);
  expect(withLogo.html).toContain('src="data:image/png;base64,AA"');
});
