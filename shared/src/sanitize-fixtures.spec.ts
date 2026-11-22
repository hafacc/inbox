// Shared has no HTML sanitizer: web (DOMPurify) and Android (jsoup) each run these cases against
// their own. This only keeps the file well-formed.
import { expect, test } from "bun:test";
import { readFileSync } from "node:fs";

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
    new URL("../testdata/sanitize-fixtures.json", import.meta.url),
    "utf8",
  ),
) as { cases: SanitizeCase[]; opensOnline: string };

test("sanitize fixtures are well-formed", () => {
  const names = new Set<string>();
  for (const fixture of fixtures.cases) {
    expect(names.has(fixture.name)).toBe(false);
    names.add(fixture.name);
    expect(typeof fixture.html).toBe("string");
    expect(typeof fixture.hasRemoteImages).toBe("boolean");
    expect(Array.isArray(fixture.colorSchemes)).toBe(true);
    expect(fixture.contains.length + fixture.absent.length).toBeGreaterThan(0);
    for (const value of Object.values(fixture.cidUrls)) {
      expect(value).toStartWith("data:");
    }
    for (const label of Object.values(fixture.cidMissing ?? {})) {
      expect(label).toEndWith(fixtures.opensOnline);
    }
  }
});
