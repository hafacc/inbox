import { describe, expect, it } from "bun:test";
import type { GmailFullMessage, GmailHeader } from "./gmail-api.js";
import {
  authenticatedAsCloudflare,
  isCloudflareLink,
  type LinkFetch,
  linksIn,
  openVerificationLink,
  senderDomain,
  verificationMail,
} from "./verification-mail.js";

const SENT = 1_800_000_000_000;
const DEST = "me@gmail.example.com";
const LINK =
  "https://dash.cloudflare.com/email-verification?token=abc&amp;id=d1";

const AUTHENTICATED =
  "mx.google.com;\r\n       dkim=pass header.i=@notify.cloudflare.com header.s=cf header.b=x;\r\n" +
  "       spf=pass (google.com: domain of bounce@notify.cloudflare.com designates 1.2.3.4) smtp.mailfrom=notify.cloudflare.com;\r\n" +
  "       dmarc=pass (p=REJECT sp=REJECT dis=NONE) header.from=cloudflare.com";

function encoded(text: string): string {
  return Buffer.from(text, "utf8").toString("base64url");
}

function message(
  overrides: {
    from?: string;
    to?: string;
    auth?: string | null;
    subject?: string;
    html?: string;
    text?: string;
    internalDate?: number;
  } = {},
): GmailFullMessage {
  const headers: GmailHeader[] = [];
  if (overrides.auth !== null) {
    headers.push({
      name: "Authentication-Results",
      value: overrides.auth ?? AUTHENTICATED,
    });
  }
  headers.push(
    {
      name: "From",
      value: overrides.from ?? "Cloudflare <noreply@notify.cloudflare.com>",
    },
    {
      name: "Subject",
      value: overrides.subject ?? "[Cloudflare]: Verify your email address",
    },
    { name: "To", value: overrides.to ?? `Me <${DEST}>` },
  );
  return {
    id: "m1",
    threadId: "t1",
    internalDate: String(overrides.internalDate ?? SENT + 5_000),
    payload: {
      mimeType: "multipart/alternative",
      headers,
      parts: [
        {
          mimeType: "text/plain",
          body: {
            data: encoded(overrides.text ?? "Please verify your address."),
          },
        },
        {
          mimeType: "text/html",
          body: {
            data: encoded(
              overrides.html ??
                `<p>Hi</p><a href="https://www.cloudflare.com/">Cloudflare</a>` +
                  `<a href="${LINK}">Verify email address</a>`,
            ),
          },
        },
      ],
    },
  };
}

describe("verificationMail", () => {
  it("finds the confirmation link in Cloudflare's mail", () => {
    expect(verificationMail(message(), SENT, DEST)).toEqual({
      messageId: "m1",
      link: "https://dash.cloudflare.com/email-verification?token=abc&id=d1",
    });
  });

  it("finds a link in the plain text when there is no HTML link", () => {
    const found = verificationMail(
      message({
        html: "<p>no links</p>",
        text: "Verify: https://dash.cloudflare.com/verify-email/xyz\n",
      }),
      SENT,
      DEST,
    );
    expect(found?.link).toBe("https://dash.cloudflare.com/verify-email/xyz");
  });

  it("ignores mail from before the destination was added", () => {
    expect(
      verificationMail(message({ internalDate: SENT - 1 }), SENT, DEST),
    ).toBeUndefined();
  });

  it("ignores mail from other senders", () => {
    for (const from of [
      "Cloudflare <noreply@cloudflare.com.evil.example>",
      "noreply@notcloudflare.example",
      "nobody",
    ]) {
      expect(verificationMail(message({ from }), SENT, DEST)).toBeUndefined();
    }
  });

  it("ignores mail Gmail didn't authenticate as Cloudflare", () => {
    for (const auth of [
      null,
      "mx.google.com; dkim=fail header.i=@cloudflare.com; dmarc=fail header.from=cloudflare.com",
      "mx.google.com; dkim=pass header.i=@evil.example; dmarc=pass header.from=evil.example",
      "evil.example; dkim=pass header.d=cloudflare.com",
    ]) {
      expect(verificationMail(message({ auth }), SENT, DEST)).toBeUndefined();
    }
  });

  it("ignores links off Cloudflare or not about verifying", () => {
    expect(
      verificationMail(
        message({
          html: `<a href="https://evil.example/verify?x=1">Verify</a>
                 <a href="https://dash.cloudflare.com.evil.example/verify">Verify</a>
                 <a href="http://dash.cloudflare.com/verify">Verify</a>
                 <a href="https://dash.cloudflare.com/unsubscribe">Stop</a>`,
          text: "verify",
        }),
        SENT,
        DEST,
      ),
    ).toBeUndefined();
  });

  it("ignores mail that doesn't name the destination", () => {
    expect(
      verificationMail(message({ to: "other@gmail.example.com" }), SENT, DEST),
    ).toBeUndefined();
    expect(
      verificationMail(
        message({ to: "undisclosed-recipients:;", text: `Verify ${DEST}` }),
        SENT,
        DEST,
      )?.messageId,
    ).toBe("m1");
  });

  it("ignores Cloudflare links on hosts that don't confirm destinations", () => {
    expect(
      verificationMail(
        message({
          html: '<a href="https://www.cloudflare.com/verify?token=abc">Verify</a>',
        }),
        SENT,
        DEST,
      ),
    ).toBeUndefined();
  });

  it("ignores Cloudflare mail that isn't about verifying", () => {
    expect(
      verificationMail(
        message({
          subject: "Your invoice",
          text: "Thanks",
          html: '<a href="https://dash.cloudflare.com/billing">Billing</a>',
        }),
        SENT,
        DEST,
      ),
    ).toBeUndefined();
  });
});

describe("helpers", () => {
  it("reads the sender's domain", () => {
    expect(senderDomain("Cloudflare <a@Notify.Cloudflare.com>")).toBe(
      "notify.cloudflare.com",
    );
    expect(senderDomain("a@cloudflare.com")).toBe("cloudflare.com");
    expect(senderDomain("nobody")).toBeUndefined();
  });

  it("trusts only Gmail's topmost verdict", () => {
    expect(
      authenticatedAsCloudflare([
        { name: "Authentication-Results", value: "mx.google.com; dkim=none" },
        { name: "Authentication-Results", value: AUTHENTICATED },
      ]),
    ).toBe(false);
  });

  it("decodes entities in links", () => {
    expect(
      linksIn(['<a href="https://x.cloudflare.com/a?b=1&#38;c=2&#x3D;">']),
    ).toEqual(["https://x.cloudflare.com/a?b=1&c=2="]);
  });

  it("accepts only plain https links on Cloudflare", () => {
    expect(isCloudflareLink("https://dash.cloudflare.com/x")).toBe(true);
    expect(isCloudflareLink("https://cloudflare.com/x")).toBe(true);
    expect(isCloudflareLink("https://u:p@dash.cloudflare.com/x")).toBe(false);
    expect(isCloudflareLink("https://dash.cloudflare.com:8443/x")).toBe(false);
    expect(isCloudflareLink("https://cloudflare.com.evil.example/")).toBe(
      false,
    );
    expect(isCloudflareLink("javascript:alert(1)")).toBe(false);
  });
});

describe("openVerificationLink", () => {
  function fetchFrom(
    responses: Record<string, { status: number; location?: string }>,
  ) {
    const opened: string[] = [];
    const fetchImpl: LinkFetch = async (url) => {
      opened.push(url);
      const next = responses[url] ?? { status: 404 };
      return new Response(null, {
        status: next.status,
        headers: next.location === undefined ? {} : { Location: next.location },
      });
    };
    return { opened, fetchImpl };
  }

  it("follows redirects that stay on Cloudflare", async () => {
    const { opened, fetchImpl } = fetchFrom({
      "https://dash.cloudflare.com/verify?t=1": {
        status: 302,
        location: "/verified",
      },
      "https://dash.cloudflare.com/verified": { status: 200 },
    });
    expect(
      await openVerificationLink(
        "https://dash.cloudflare.com/verify?t=1",
        fetchImpl,
      ),
    ).toBe(true);
    expect(opened).toEqual([
      "https://dash.cloudflare.com/verify?t=1",
      "https://dash.cloudflare.com/verified",
    ]);
  });

  it("stops at a redirect off Cloudflare", async () => {
    const { opened, fetchImpl } = fetchFrom({
      "https://dash.cloudflare.com/verify": {
        status: 302,
        location: "https://evil.example/",
      },
    });
    expect(
      await openVerificationLink(
        "https://dash.cloudflare.com/verify",
        fetchImpl,
      ),
    ).toBe(false);
    expect(opened).toEqual(["https://dash.cloudflare.com/verify"]);
  });

  it("never opens a link off Cloudflare", async () => {
    const { opened, fetchImpl } = fetchFrom({});
    expect(await openVerificationLink("https://evil.example/", fetchImpl)).toBe(
      false,
    );
    expect(
      await openVerificationLink(
        "https://community.cloudflare.com/verify",
        fetchImpl,
      ),
    ).toBe(false);
    expect(opened).toEqual([]);
  });

  it("reports a refusal", async () => {
    const { fetchImpl } = fetchFrom({
      "https://dash.cloudflare.com/verify": { status: 403 },
    });
    expect(
      await openVerificationLink(
        "https://dash.cloudflare.com/verify",
        fetchImpl,
      ),
    ).toBe(false);
  });
});
