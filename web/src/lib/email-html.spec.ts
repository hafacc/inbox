import { expect, test } from "bun:test";
import { emailStyle } from "@inbox/shared/themes";
import {
  hasRemoteCssUrl,
  isRemoteUrl,
  messageCsp,
  messageSrcdoc,
} from "./email-html";

test("the frame's policy allows remote images only when shown", () => {
  expect(messageCsp(false)).toBe(
    "default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:",
  );
  expect(messageCsp(true)).toContain("img-src https: http: data: blob:");
  const doc = messageSrcdoc("<p>Hi</p>", false);
  expect(doc).toContain('http-equiv="Content-Security-Policy"');
  expect(doc).toContain('<base target="_blank">');
  expect(doc).toContain("<body><p>Hi</p></body>");
  expect(doc).not.toContain("script-src");
  expect(doc).toContain("html{background:#ffffff}");
});

test("styles inline code as a chip only when given its color", () => {
  expect(messageSrcdoc("<code>x</code>", false)).not.toContain(">code{");
  expect(
    messageSrcdoc("<code>x</code>", false, undefined, "light", "#f3f0ea"),
  ).toContain(
    ":where(:not(pre)>code){font-family:monospace;font-size:13px;padding:1px 4px;border-radius:4px;background:#f3f0ea}",
  );
});

test("dark mode draws mail on the dark page with the frame's color scheme, pictures dimmed", () => {
  const doc = messageSrcdoc(
    "<p>Hi</p>",
    false,
    emailStyle("green", "dark"),
    "dark",
  );
  expect(doc).toContain(":root{color-scheme:dark}");
  expect(doc).toContain("html{background:#201e1b}");
  expect(doc).toContain("img,video{filter:brightness(0.9)}");
  expect(doc).not.toContain("invert");
});

test("spots remote image sources", () => {
  expect(isRemoteUrl("https://t.example/p.gif")).toBe(true);
  expect(isRemoteUrl("//cdn.example/x.png")).toBe(true);
  expect(isRemoteUrl("data:image/png;base64,AAAA")).toBe(false);
  expect(isRemoteUrl("cid:logo@x")).toBe(false);
  expect(hasRemoteCssUrl("background: url('https://t.example/bg.png')")).toBe(
    true,
  );
  expect(hasRemoteCssUrl("background: url(data:image/png;base64,AA)")).toBe(
    false,
  );
});

test("only a phone drops designed mail's side padding, before plain mail's own", () => {
  const document = messageSrcdoc("<p>Hi</p>", false);
  expect(document).toContain("body{padding:12px 16px;");
  const phone = document.indexOf("html[data-phone] body{padding:12px 0}");
  expect(phone).toBeGreaterThan(-1);
  expect(phone).toBeLessThan(
    document.indexOf("html[data-plain] body{padding:2px 0}"),
  );
});
