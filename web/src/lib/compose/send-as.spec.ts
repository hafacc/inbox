import { expect, test } from "bun:test";
import type { GmailSendAs } from "../gmail/types";
import { defaultFrom, fromOptions, optionFor } from "./send-as";

const SEND_AS: GmailSendAs[] = [
  {
    sendAsEmail: "jordan.lee@example.com",
    displayName: "Jordan Lee",
    isPrimary: true,
    verificationStatus: "accepted",
  },
  {
    sendAsEmail: "jordan@example.org",
    displayName: "Jordan",
    isDefault: true,
    verificationStatus: "accepted",
  },
  { sendAsEmail: "pending@x.example.net", verificationStatus: "pending" },
];

test("lists hafa.cc, then Gmail, then verified aliases", () => {
  expect(
    fromOptions(SEND_AS, "jordan.lee@example.com", "test@hafa.cc"),
  ).toEqual([
    {
      mailbox: { name: "Jordan Lee", email: "test@hafa.cc" },
      route: "hafa",
    },
    {
      mailbox: { name: "Jordan Lee", email: "jordan.lee@example.com" },
      route: "gmail",
    },
    {
      mailbox: { name: "Jordan", email: "jordan@example.org" },
      route: "alias",
    },
  ]);
});

test("without a send-as list the Gmail address is the only option", () => {
  expect(fromOptions([], "me@gmail.example.com", null)).toEqual([
    { mailbox: { name: "", email: "me@gmail.example.com" }, route: "gmail" },
  ]);
});

test("the saved default wins, then Gmail's default, then the first", () => {
  const options = fromOptions(
    SEND_AS,
    "jordan.lee@example.com",
    "test@hafa.cc",
  );
  expect(defaultFrom(options, SEND_AS, "TEST@hafa.cc")?.route).toBe("hafa");
  expect(
    defaultFrom(options, SEND_AS, "gone@x.example.net")?.mailbox.email,
  ).toBe("jordan@example.org");
  expect(defaultFrom(options, [], null)?.mailbox.email).toBe("test@hafa.cc");
  expect(optionFor(options, "Jordan@Example.org")?.route).toBe("alias");
});
