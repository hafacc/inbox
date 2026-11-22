import { expect, test } from "bun:test";
import {
  applySync,
  type Contact,
  contactsOf,
  mergeBook,
  searchContacts,
} from "./contacts";

const MAYA = {
  resourceName: "people/c1",
  names: [{ displayName: "Maya Chen", metadata: { primary: true } }],
  emailAddresses: [
    { value: "maya@work.example.net" },
    { value: "maya.chen@gmail.example.com", metadata: { primary: true } },
    { value: "MAYA@work.example.net" },
  ],
  photos: [{ url: "https://lh3/p.jpg" }],
};

test("one contact per address, primary first, repeats dropped", () => {
  expect(contactsOf(MAYA, "connections")).toEqual([
    {
      resourceName: "people/c1",
      name: "Maya Chen",
      email: "maya.chen@gmail.example.com",
      photo: "https://lh3/p.jpg",
      source: "connections",
    },
    {
      resourceName: "people/c1",
      name: "Maya Chen",
      email: "maya@work.example.net",
      photo: "https://lh3/p.jpg",
      source: "connections",
    },
  ]);
});

test("a sync page replaces changed people and drops deleted ones", () => {
  const stored = applySync(new Map(), [MAYA], "connections");
  const next = applySync(
    stored,
    [
      { resourceName: "people/c1", metadata: { deleted: true } },
      {
        resourceName: "people/c2",
        emailAddresses: [{ value: "bo@x.example.net" }],
        photos: [{ url: "https://default", default: true }],
      },
    ],
    "connections",
  );
  expect([...next.keys()]).toEqual(["people/c2"]);
  expect(next.get("people/c2")?.[0]?.photo).toBeNull();
});

test("the primary photo is used, and Google's generated letter is not", () => {
  const photoOf = (photos: Parameters<typeof contactsOf>[0]["photos"]) =>
    contactsOf(
      {
        resourceName: "people/c3",
        emailAddresses: [{ value: "a@x.example.net" }],
        photos,
      },
      "other",
    )[0]?.photo;
  expect(
    photoOf([
      { url: "https://lh3/first.jpg" },
      { url: "https://lh3/main.jpg", metadata: { primary: true } },
    ]),
  ).toBe("https://lh3/main.jpg");
  expect(
    photoOf([
      { url: "https://lh3/letter", default: true, metadata: { primary: true } },
      { url: "https://lh3/other.jpg" },
    ]),
  ).toBeNull();
  expect(photoOf(undefined)).toBeNull();
});

function contact(
  name: string,
  email: string,
  source: Contact["source"] = "connections",
): Contact {
  return { resourceName: email, name, email, photo: null, source };
}

test("a saved contact wins over an other contact with the same address", () => {
  const book = mergeBook(
    [[contact("", "ana@x.example.net")]],
    [
      [
        contact("Ana Lopez", "ANA@x.example.net", "other"),
        contact("", "zed@x.example.net", "other"),
      ],
    ],
  );
  expect(book).toEqual([
    { ...contact("Ana Lopez", "ana@x.example.net") },
    contact("", "zed@x.example.net", "other"),
  ]);
});

test("search ranks saved contacts and name starts first, skipping chosen ones", () => {
  const book = [
    contact("Mark", "mark@x.example.net", "other"),
    contact("Maya Chen", "maya@x.example.net"),
    contact("Ada", "mada@x.example.net"),
    contact("Emma", "emma@x.example.net"),
  ];
  expect(searchContacts(book, "ma").map((entry) => entry.email)).toEqual([
    "maya@x.example.net",
    "mark@x.example.net",
    "mada@x.example.net",
  ]);
  expect(
    searchContacts(book, "ma", [{ name: "", email: "MAYA@x.example.net" }]).map(
      (entry) => entry.email,
    ),
  ).toEqual(["mark@x.example.net", "mada@x.example.net"]);
});
