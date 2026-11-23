import { afterAll, afterEach, expect, mock, test } from "bun:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { JSDOM } from "jsdom";

// The link dialog mounted inside a form standing in for the composer's, in jsdom, compiled with
// Svelte's client build as a browser gets it.
const dom = new JSDOM("<!doctype html><html><body></body></html>", {
  pretendToBeVisual: true,
  url: "http://localhost/",
});
const view = dom.window as unknown as Record<string, unknown>;
// This window's globals, all of them, as other spec files set theirs; put back once these end.
const replaced = new Map<string, PropertyDescriptor | undefined>();
for (const name of Object.getOwnPropertyNames(dom.window)) {
  const before = Object.getOwnPropertyDescriptor(globalThis, name);
  const isDom = /^[A-Z]/.test(name) || name === "window" || name === "document";
  if (before?.configurable === false || (name in globalThis && !isDom))
    continue;
  replaced.set(name, before);
  Object.defineProperty(globalThis, name, {
    configurable: true,
    writable: true,
    value: view[name],
  });
}
afterAll(() => {
  for (const [name, descriptor] of replaced) {
    if (descriptor === undefined) Reflect.deleteProperty(globalThis, name);
    else Object.defineProperty(globalThis, name, descriptor);
  }
});
// jsdom has no modal dialogs.
dom.window.HTMLDialogElement.prototype.showModal = function showModal() {
  this.open = true;
};

const svelteRoot = dirname(
  createRequire(import.meta.url).resolve("svelte/package.json"),
);
// Svelte's client build, which the "browser" condition picks and bun's tests don't.
const svelteClient = join(svelteRoot, "src/index-client.js");
Bun.plugin({
  name: "svelte client",
  setup(build) {
    build.onLoad({ filter: /\.svelte$/ }, async ({ path }) => {
      const { compile } = await import("svelte/compiler");
      const { js } = compile(readFileSync(path, "utf8"), {
        filename: path,
        generate: "client",
      });
      return {
        contents: js.code.replaceAll(
          'from "svelte";',
          `from ${JSON.stringify(svelteClient)};`,
        ),
        loader: "js",
      };
    });
  },
});
mock.module("../back-stack.svelte", () => ({
  pushBack: () => () => {},
}));
const { flushSync, mount, unmount } = (await import(
  svelteClient
)) as typeof import("svelte");
const { default: LinkDialog } = await import("./link-dialog.svelte");

let shown: ReturnType<typeof mount> | null = null;

afterEach(() => {
  if (shown !== null) unmount(shown);
  shown = null;
  document.body.replaceChildren();
});

// The dialog open in a form whose submit (Send) and keys are counted, as the composer's are.
function openInForm() {
  const form = document.createElement("form");
  document.body.append(form);
  const composer = { sent: 0, keys: [] as string[] };
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    composer.sent += 1;
  });
  form.addEventListener("keydown", (event) => {
    composer.keys.push(event.key);
  });
  const saved: [string, string][] = [];
  let cancelled = 0;
  shown = mount(LinkDialog, {
    target: form,
    props: {
      text: "docs",
      href: "",
      title: "Add link",
      onCancel: () => {
        cancelled += 1;
      },
      onSave: (text: string, href: string) => {
        saved.push([text, href]);
      },
    },
  });
  flushSync();
  const dialog = document.querySelector("dialog")!;
  const [, address] = [...dialog.querySelectorAll("input")];
  address!.value = "example.com/docs";
  address!.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  flushSync();
  return {
    composer,
    dialog,
    address: address!,
    saved,
    cancelled: () => cancelled,
  };
}

test("Save saves the link and doesn't submit the composer's form", () => {
  const { composer, dialog, saved } = openInForm();
  dialog.querySelector<HTMLButtonElement>("button[type=submit]")!.click();
  expect(saved).toEqual([["docs", "https://example.com/docs"]]);
  expect(composer.sent).toBe(0);
});

test("Enter in a field saves, and Enter and Escape never reach the composer", () => {
  const { composer, dialog, address, saved } = openInForm();
  for (const key of ["Enter", "Escape"]) {
    address.dispatchEvent(
      new dom.window.KeyboardEvent("keydown", { key, bubbles: true }),
    );
  }
  dialog.querySelector("form")!.requestSubmit();
  expect(composer.keys).toEqual([]);
  expect(saved).toEqual([["docs", "https://example.com/docs"]]);
  expect(composer.sent).toBe(0);
});

test("an address that isn't a web or mail address can't be saved", () => {
  const { dialog, address, saved } = openInForm();
  address.value = "javascript:alert(1)";
  address.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  flushSync();
  const save = dialog.querySelector<HTMLButtonElement>("button[type=submit]")!;
  expect(save.disabled).toBe(true);
  dialog.querySelector("form")!.requestSubmit();
  expect(saved).toEqual([]);
  expect(address.getAttribute("aria-invalid")).toBe("true");
});
