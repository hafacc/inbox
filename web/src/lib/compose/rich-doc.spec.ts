import { beforeAll, describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import {
  normalizeHtml,
  richToHtml,
  richToMarkdown,
} from "@inbox/shared/compose";
import type { Editor } from "@tiptap/core";
import { JSDOM } from "jsdom";

type Fixtures = {
  typing: { name: string; typed: string; html: string; text: string }[];
  markdown: { name: string; markdown: string }[];
};

const fixtures = JSON.parse(
  readFileSync(
    new URL("../../../../shared/testdata/rich-fixtures.json", import.meta.url),
    "utf8",
  ),
) as Fixtures;

let window: JSDOM["window"];
let editorClass: typeof Editor;
let rich: typeof import("./rich-doc");

// Tiptap reads the DOM's globals, so they are set before it loads.
beforeAll(async () => {
  window = new JSDOM("<!doctype html><body></body>", {
    pretendToBeVisual: true,
  }).window;
  const globals = globalThis as Record<string, unknown>;
  for (const name of [
    "window",
    "document",
    "navigator",
    "Node",
    "Element",
    "HTMLElement",
    "MutationObserver",
    "getComputedStyle",
    "requestAnimationFrame",
    "cancelAnimationFrame",
  ]) {
    globals[name] ??= (window as unknown as Record<string, unknown>)[name];
  }
  editorClass = (await import("@tiptap/core")).Editor;
  rich = await import("./rich-doc");
});

function newEditor(): Editor {
  const element = window.document.createElement("div");
  window.document.body.append(element);
  return new editorClass({ element, extensions: rich.richExtensions() });
}

// Each character as typed text, "\n" as the Enter key.
function typeInto(editor: Editor, typed: string): void {
  const { view } = editor;
  for (const char of typed) {
    if (char === "\n") {
      const enter = new window.KeyboardEvent("keydown", { key: "Enter" });
      view.someProp("handleKeyDown", (handle) => handle(view, enter));
    } else {
      const { from, to } = view.state.selection;
      const insert = () => view.state.tr.insertText(char, from, to);
      const handled = view.someProp("handleTextInput", (handle) =>
        handle(view, from, to, char, insert),
      );
      if (!handled) view.dispatch(insert());
    }
  }
}

describe("rich typing fixtures (must match Android)", () => {
  for (const { name, typed, html, text } of fixtures.typing) {
    test(name, () => {
      const editor = newEditor();
      typeInto(editor, typed);
      const blocks = rich.docToRich(editor.getJSON());
      editor.destroy();
      expect(normalizeHtml(richToHtml(blocks))).toBe(normalizeHtml(html));
      expect(richToMarkdown(blocks)).toBe(text);
    });
  }
});

describe("markdown loads into the editor unchanged", () => {
  for (const { name, markdown } of fixtures.markdown) {
    test(name, async () => {
      const { markdownToRich } = await import("@inbox/shared/compose");
      const blocks = markdownToRich(markdown);
      const editor = newEditor();
      editor.commands.setContent(rich.richToDoc(blocks));
      expect(rich.docToRich(editor.getJSON())).toEqual(blocks);
      editor.destroy();
    });
  }
});

test("code blocks inside a quote or list item keep their text", () => {
  const blocks = rich.docToRich({
    type: "doc",
    content: [
      {
        type: "blockquote",
        content: [
          { type: "paragraph", content: [{ type: "text", text: "q" }] },
          { type: "codeBlock", content: [{ type: "text", text: "a\nb" }] },
        ],
      },
      {
        type: "bulletList",
        content: [
          {
            type: "listItem",
            content: [
              { type: "paragraph", content: [{ type: "text", text: "i" }] },
              { type: "codeBlock", content: [{ type: "text", text: "c" }] },
            ],
          },
        ],
      },
    ],
  });
  expect(blocks).toEqual([
    { kind: "quote", runs: [{ text: "q" }] },
    { kind: "code", text: "a\nb" },
    { kind: "bullet", runs: [{ text: "i" }] },
    { kind: "code", text: "c" },
  ]);
});
