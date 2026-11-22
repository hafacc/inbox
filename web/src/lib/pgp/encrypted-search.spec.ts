import { describe, expect, it } from "bun:test";
import { searchPlan } from "../search/search-plan";
import type { MessageView } from "../thread-detail";
import {
  ENCRYPTED_MAIL_QUERY,
  encryptedSearch,
  matchesTerms,
  uncheckedMessages,
} from "./encrypted-search";

function message(subject: string, text: string): MessageView {
  return {
    id: "m",
    threadId: "t",
    from: { name: "", email: "a@b.c" },
    to: [],
    date: 0,
    snippet: "",
    unread: false,
    body: { html: null, text, attachments: [], inline: [], calendar: null },
    headers: {
      messageId: "",
      references: null,
      inReplyTo: null,
      subject,
      from: [],
      replyTo: [],
      to: [],
      cc: [],
    },
  };
}

describe("encrypted search", () => {
  it("keeps the operators, drops the words, and asks for encrypted mail", () => {
    const search = encryptedSearch(
      searchPlan("from:ana after:2026/01/01 lease paper"),
    );
    expect(search?.query).toBe(
      `from:ana after:2026/01/01 ${ENCRYPTED_MAIL_QUERY}`,
    );
    expect(search?.terms).toEqual({ include: ["lease", "paper"], exclude: [] });
    expect(encryptedSearch(searchPlan("lease"))?.query).toBe(
      ENCRYPTED_MAIL_QUERY,
    );
  });

  it("has nothing to do without words", () => {
    expect(encryptedSearch(searchPlan("from:ana"))).toBeNull();
    expect(encryptedSearch(searchPlan("is:snoozed"))).toBeNull();
  });

  it("leaves out the device's own chips", () => {
    expect(encryptedSearch(searchPlan("is:snoozed lease"))?.query).toBe(
      ENCRYPTED_MAIL_QUERY,
    );
  });

  it("asks Gmail for pinned and done in its own words", () => {
    expect(encryptedSearch(searchPlan("is:pinned is:done lease"))?.query).toBe(
      `is:starred -in:inbox ${ENCRYPTED_MAIL_QUERY}`,
    );
  });

  it("matches every word in the subject and text, ignoring case", () => {
    const lease = message("Lease Paperwork", "Signed copy attached");
    expect(
      matchesTerms(lease, { include: ["lease", "signed"], exclude: [] }),
    ).toBe(true);
    expect(
      matchesTerms(lease, { include: ["lease", "rent"], exclude: [] }),
    ).toBe(false);
    expect(matchesTerms(lease, { include: ["lease"], exclude: ["copy"] })).toBe(
      false,
    );
  });

  it("checks again only mail no earlier run checked", () => {
    const progress = {
      checked: new Set(["m1", "m2"]),
      matches: new Set(["t2"]),
    };
    expect(
      uncheckedMessages(
        [
          { id: "m1", threadId: "t1" },
          { id: "m3", threadId: "t1" },
          { id: "m4", threadId: "t2" },
          { id: "m5", threadId: "t3" },
        ],
        progress,
      ).map(({ id }) => id),
    ).toEqual(["m3", "m5"]);
  });
});
