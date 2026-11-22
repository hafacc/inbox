import { expect, test } from "bun:test";
import {
  addRecipients,
  chipLabel,
  isValidEmail,
  parseEntry,
  parseRecipients,
  splitEntries,
} from "./recipients";

test("accepts plain addresses and refuses typos", () => {
  expect(isValidEmail("ana@example.com")).toBe(true);
  expect(isValidEmail(" ana+tag@mail.uk.example.org ")).toBe(true);
  expect(isValidEmail("ana@example")).toBe(false);
  expect(isValidEmail("ana.example.com")).toBe(false);
  expect(isValidEmail("ana @example.com")).toBe(false);
});

test("splits at commas, semicolons and line breaks outside quotes and brackets", () => {
  expect(
    splitEntries(
      '"Lopez, Ana" <ana@example.com>; bo@example.com\ncy@x.example.net,',
    ),
  ).toEqual([
    '"Lopez, Ana" <ana@example.com>',
    "bo@example.com",
    "cy@x.example.net",
  ]);
});

test("parses named, quoted and bare entries", () => {
  expect(parseEntry("Ana Lopez <ana@example.com>")).toEqual({
    name: "Ana Lopez",
    email: "ana@example.com",
  });
  expect(parseEntry('"Lopez, Ana" <ana@example.com>')).toEqual({
    name: "Lopez, Ana",
    email: "ana@example.com",
  });
  expect(parseEntry("mailto:bo@example.com")).toEqual({
    name: "",
    email: "bo@example.com",
  });
  expect(parseEntry("Bo <not-an-address>")).toBeNull();
});

test("keeps entries that aren't addresses for fixing", () => {
  expect(parseRecipients("ana@example.com, bob, cy@x.example.net")).toEqual({
    mailboxes: [
      { name: "", email: "ana@example.com" },
      { name: "", email: "cy@x.example.net" },
    ],
    invalid: ["bob"],
  });
});

test("adds without repeating an address in any case", () => {
  const list = [{ name: "Ana", email: "Ana@Example.com" }];
  expect(
    addRecipients(list, [
      { name: "", email: "ana@example.com" },
      { name: "Bo", email: "bo@example.com" },
    ]),
  ).toEqual([
    { name: "Ana", email: "Ana@Example.com" },
    { name: "Bo", email: "bo@example.com" },
  ]);
});

test("chips show the name, else the address", () => {
  expect(chipLabel({ name: " Ana ", email: "a@x.example.net" })).toBe("Ana");
  expect(chipLabel({ name: "", email: "a@x.example.net" })).toBe(
    "a@x.example.net",
  );
});
