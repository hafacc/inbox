import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  charsetOf,
  decodeBase64Url,
  decodeBase64UrlText,
  decodeEntities,
  extractBody,
  headerValue,
  type MessageBody,
  type MimePart,
  type Person,
  parseAddressList,
  walkParts,
} from "./mime";

type Fixtures = {
  charset: { name: string; charset: string; data: string; text: string }[];
  contentType: {
    name: string;
    contentType: string | null;
    charset: string;
  }[];
  body: { name: string; payload: MimePart; expect: MessageBody }[];
  address: { name: string; value: string; expect: Person[] }[];
  entities: { name: string; value: string; expect: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../testdata/mime-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

describe("charset", () => {
  for (const fixture of fixtures.charset) {
    test(fixture.name, () => {
      expect(decodeBase64UrlText(fixture.data, fixture.charset)).toBe(
        fixture.text,
      );
    });
  }
});

describe("contentType", () => {
  for (const fixture of fixtures.contentType) {
    test(fixture.name, () => {
      expect(charsetOf(fixture.contentType)).toBe(fixture.charset);
    });
  }
});

describe("body", () => {
  for (const fixture of fixtures.body) {
    test(fixture.name, () => {
      expect(extractBody(fixture.payload)).toEqual(fixture.expect);
    });
  }
});

describe("address", () => {
  for (const fixture of fixtures.address) {
    test(fixture.name, () => {
      expect(parseAddressList(fixture.value)).toEqual(fixture.expect);
    });
  }
});

describe("entities", () => {
  for (const fixture of fixtures.entities) {
    test(fixture.name, () => {
      expect(decodeEntities(fixture.value)).toBe(fixture.expect);
    });
  }
});

test("decodes base64url's - and _ into bytes", () => {
  expect([...decodeBase64Url("-_-_")]).toEqual([0xfb, 0xff, 0xbf]);
});

test("reads headers case-insensitively", () => {
  expect(headerValue([{ name: "SUBJECT", value: "Hi" }], "subject")).toBe("Hi");
  expect(headerValue(undefined, "Subject")).toBeNull();
});

test("walks parts depth-first, parents before children", () => {
  const seen: string[] = [];
  walkParts(fixtures.body[0]!.payload, (part, ancestors) => {
    seen.push(`${ancestors.length}:${part.mimeType}`);
  });
  expect(seen).toEqual([
    "0:multipart/mixed",
    "1:multipart/related",
    "2:multipart/alternative",
    "3:text/plain",
    "3:text/html",
    "2:image/png",
    "1:application/pdf",
    "1:message/rfc822",
    "2:text/html",
  ]);
});
