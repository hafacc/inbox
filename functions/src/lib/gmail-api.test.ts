import { describe, expect, it } from "bun:test";
import { GmailError, type GmailFetch, gmailApi } from "./gmail-api.js";

function recorder(status: number, body: unknown) {
  const calls: { url: string; method: string; body: unknown; auth?: string }[] =
    [];
  const fetchImpl: GmailFetch = async (url, init) => {
    calls.push({
      url,
      method: init.method,
      body: init.body === undefined ? undefined : JSON.parse(init.body),
      auth: init.headers.Authorization,
    });
    return new Response(JSON.stringify(body), { status });
  };
  return { calls, fetchImpl };
}

describe("gmailApi", () => {
  it("starts a watch on changes that touch the inbox", async () => {
    const { calls, fetchImpl } = recorder(200, {
      historyId: "42",
      expiration: "1800000000000",
    });
    const watch = await gmailApi("token", fetchImpl).watch(
      "projects/p/topics/gmail",
    );
    expect(watch).toEqual({ historyId: "42", expiration: 1_800_000_000_000 });
    expect(calls[0]).toEqual({
      url: "https://gmail.googleapis.com/gmail/v1/users/me/watch",
      method: "POST",
      body: {
        topicName: "projects/p/topics/gmail",
        labelIds: ["INBOX"],
        labelFilterBehavior: "include",
      },
      auth: "Bearer token",
    });
  });

  it("stops the watch", async () => {
    const { calls, fetchImpl } = recorder(200, {});
    await gmailApi("token", fetchImpl).stop();
    expect(calls[0]).toEqual({
      url: "https://gmail.googleapis.com/gmail/v1/users/me/stop",
      method: "POST",
      body: {},
      auth: "Bearer token",
    });
  });

  it("asks history for message additions", async () => {
    const { calls, fetchImpl } = recorder(200, { history: [] });
    await gmailApi("token", fetchImpl).listHistory("7", "next");
    const url = new URL(calls[0]!.url);
    expect(url.pathname).toBe("/gmail/v1/users/me/history");
    expect(url.searchParams.get("startHistoryId")).toBe("7");
    expect(url.searchParams.get("historyTypes")).toBe("messageAdded");
    expect(url.searchParams.get("pageToken")).toBe("next");
  });

  it("fetches sender, subject, content type and reply headers only", async () => {
    const { calls, fetchImpl } = recorder(200, { id: "m", threadId: "t" });
    await gmailApi("token", fetchImpl).getMessage("m");
    const url = new URL(calls[0]!.url);
    expect(url.searchParams.get("format")).toBe("metadata");
    expect(url.searchParams.getAll("metadataHeaders")).toEqual([
      "From",
      "Subject",
      "Content-Type",
      "In-Reply-To",
      "References",
    ]);
  });

  it("fetches only the headers asked for", async () => {
    const { calls, fetchImpl } = recorder(200, { id: "m", threadId: "t" });
    await gmailApi("token", fetchImpl).getMessage("m", ["List-Unsubscribe"]);
    const url = new URL(calls[0]!.url);
    expect(url.searchParams.getAll("metadataHeaders")).toEqual([
      "List-Unsubscribe",
    ]);
  });

  it("throws the status and Gmail's message", async () => {
    const { fetchImpl } = recorder(404, {
      error: { message: "Requested entity was not found." },
    });
    const error = await gmailApi("token", fetchImpl)
      .listHistory("1")
      .catch((caught) => caught);
    expect(error).toBeInstanceOf(GmailError);
    expect((error as GmailError).status).toBe(404);
    expect((error as GmailError).message).toBe(
      "Requested entity was not found.",
    );
  });
});

describe("gmailApi sending", () => {
  it("reads a draft whole and sends it", async () => {
    const { calls, fetchImpl } = recorder(200, { id: "m", threadId: "t" });
    const gmail = gmailApi("token", fetchImpl);
    await gmail.getDraftRaw("d/1");
    expect(calls[0]?.url).toBe(
      "https://gmail.googleapis.com/gmail/v1/users/me/drafts/d%2F1?format=raw",
    );
    expect(await gmail.sendDraft("d1")).toEqual({ id: "m", threadId: "t" });
    expect(calls[1]).toMatchObject({
      url: "https://gmail.googleapis.com/gmail/v1/users/me/drafts/send",
      method: "POST",
      body: { id: "d1" },
    });
  });

  it("deletes a draft, which answers with no body", async () => {
    const calls: { url: string; method: string }[] = [];
    const gmail = gmailApi("token", async (url, init) => {
      calls.push({ url, method: init.method });
      return new Response(null, { status: 204 });
    });
    await gmail.deleteDraft("d1");
    expect(calls).toEqual([
      {
        url: "https://gmail.googleapis.com/gmail/v1/users/me/drafts/d1",
        method: "DELETE",
      },
    ]);
  });

  it("inserts a copy into a thread with labels", async () => {
    const { calls, fetchImpl } = recorder(200, { id: "c", threadId: "t" });
    await gmailApi("token", fetchImpl).insertMessage("cmF3", "t", ["SENT"]);
    expect(calls[0]).toMatchObject({
      url: "https://gmail.googleapis.com/gmail/v1/users/me/messages?internalDateSource=dateHeader",
      method: "POST",
      body: { raw: "cmF3", threadId: "t", labelIds: ["SENT"] },
    });
  });

  it("lists send-as addresses, empty when there are none", async () => {
    const { fetchImpl } = recorder(200, {});
    expect(await gmailApi("token", fetchImpl).listSendAs()).toEqual([]);
  });
});

describe("gmailApi mailbox", () => {
  it("reads a message whole and trashes it", async () => {
    const { calls, fetchImpl } = recorder(200, { id: "m", threadId: "t" });
    const gmail = gmailApi("token", fetchImpl);
    await gmail.getFullMessage("m/1");
    await gmail.trashMessage("m1");
    expect(calls.map((call) => [call.method, call.url])).toEqual([
      [
        "GET",
        "https://gmail.googleapis.com/gmail/v1/users/me/messages/m%2F1?format=full",
      ],
      [
        "POST",
        "https://gmail.googleapis.com/gmail/v1/users/me/messages/m1/trash",
      ],
    ]);
  });
});

describe("timeouts", () => {
  it("gives every request a deadline and answers a timeout as a retryable 408", async () => {
    let signal: AbortSignal | undefined;
    const fetchImpl: GmailFetch = async (_url, init) => {
      signal = init.signal;
      throw new DOMException("The operation timed out.", "TimeoutError");
    };
    const error = await gmailApi("token", fetchImpl)
      .getHistoryId()
      .catch((caught) => caught);
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(error).toBeInstanceOf(GmailError);
    expect(error).toMatchObject({ status: 408, reason: "timeout" });
  });
});
