import { expect, test } from "bun:test";
import fixtures from "../testdata/web-link-fixtures.json" with { type: "json" };
import {
  findWebLinks,
  finishedWebLink,
  linkAddress,
  safeHref,
  webLinkPieces,
} from "./web-links";

for (const fixture of fixtures.find) {
  test(`findWebLinks: ${fixture.name}`, () => {
    const found = findWebLinks(fixture.text).map((link) => ({
      text: fixture.text.slice(link.start, link.end),
      href: link.href,
    }));
    expect(found).toEqual(fixture.links);
  });
}

test("webLinkPieces cuts a text into plain pieces and links", () => {
  expect(webLinkPieces("see example.com/x, then")).toEqual([
    { text: "see ", href: null },
    { text: "example.com/x", href: "https://example.com/x" },
    { text: ", then", href: null },
  ]);
  expect(webLinkPieces("")).toEqual([]);
  expect(webLinkPieces("https://example.com")).toEqual([
    { text: "https://example.com", href: "https://example.com" },
  ]);
});

for (const fixture of fixtures.address) {
  test(`linkAddress: ${fixture.typed}`, () => {
    expect(linkAddress(fixture.typed)).toBe(fixture.expect);
  });
}

for (const fixture of fixtures.safe) {
  test(`safeHref: ${JSON.stringify(fixture.href)}`, () => {
    expect(safeHref(fixture.href)).toBe(fixture.expect);
  });
}

for (const fixture of fixtures.finished) {
  test(`finishedWebLink: ${JSON.stringify(fixture.before)}`, () => {
    const link = finishedWebLink(fixture.before);
    expect(
      link === null
        ? null
        : { text: fixture.before.slice(link.start, link.end), href: link.href },
    ).toEqual(fixture.link);
  });
}
