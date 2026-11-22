import {
  normalizeRuns,
  type RichBlock,
  type RichMark,
  type RichRun,
} from "@inbox/shared/compose";
import {
  type AnyExtension,
  Extension,
  type JSONContent,
  markInputRule,
  markPasteRule,
} from "@tiptap/core";
import BoldBase from "@tiptap/extension-bold";
import ItalicBase from "@tiptap/extension-italic";
import { Placeholder } from "@tiptap/extensions";
import { Plugin } from "@tiptap/pm/state";
import StarterKit from "@tiptap/starter-kit";

// This app's markers: one star for bold (Tiptap's own takes two), underscores alone for italic
// (Tiptap's takes one star too), by latr's rules as markdownToRich reads them: no letter, digit,
// backtick or second marker just outside, and no space just inside. A letter typed right after
// the closing marker takes the style back off (markerEnd), as markdown wouldn't read it styled.
const STAR_INPUT = /(?<![\p{L}\p{Nd}\p{M}*`])(\*([^*\s](?:[^*]*[^*\s])?)\*)$/u;
const STAR_PASTE =
  /(?<![\p{L}\p{Nd}\p{M}*`])(\*([^*\s](?:[^*]*[^*\s])?)\*)(?![\p{L}\p{Nd}\p{M}*`])/gu;
const UNDERSCORE_INPUT =
  /(?<![\p{L}\p{Nd}\p{M}_`])(_([^_\s](?:[^_]*[^_\s])?)_)$/u;
const UNDERSCORE_PASTE =
  /(?<![\p{L}\p{Nd}\p{M}_`])(_([^_\s](?:[^_]*[^_\s])?)_)(?![\p{L}\p{Nd}\p{M}_`])/gu;
const WORD_CHARACTER = /^[\p{L}\p{Nd}\p{M}]/u;

/** The composer's editing features: Tiptap's starter kit with this app's bold and italic markers. */
export function richExtensions(placeholder = ""): AnyExtension[] {
  return [
    StarterKit.configure({
      bold: false,
      italic: false,
      underline: false,
      horizontalRule: false,
      trailingNode: false,
      link: { autolink: false, markdownLinks: true, openOnClick: false },
    }),
    boldExtension(),
    italicExtension(),
    markerEnd,
    Placeholder.configure({ placeholder }),
  ];
}

function boldExtension(): AnyExtension {
  return BoldBase.extend({
    addInputRules() {
      return [markInputRule({ find: STAR_INPUT, type: this.type })];
    },
    addPasteRules() {
      return [markPasteRule({ find: STAR_PASTE, type: this.type })];
    },
  });
}

function italicExtension(): AnyExtension {
  return ItalicBase.extend({
    addInputRules() {
      return [markInputRule({ find: UNDERSCORE_INPUT, type: this.type })];
    },
    addPasteRules() {
      return [markPasteRule({ find: UNDERSCORE_PASTE, type: this.type })];
    },
  });
}

// Typing a letter or digit right after `*bold*` or `_italic_` turned into a style puts the markers back.
const markerEnd = Extension.create({
  name: "markerEnd",
  addProseMirrorPlugins() {
    const editor = this.editor;
    return [
      new Plugin({
        props: {
          handleTextInput(view, _from, _to, text) {
            if (!WORD_CHARACTER.test(text)) return false;
            // Each extension's input rules are a plugin of their own; the one that just ran holds what it typed.
            const ended = view.state.plugins.some((plugin) => {
              if (!(plugin.spec as { isInputRules?: boolean }).isInputRules) {
                return false;
              } else {
                const last = plugin.getState(view.state) as
                  | { text?: unknown }
                  | null
                  | undefined;
                return last?.text === "*" || last?.text === "_";
              }
            });
            if (!ended) return false;
            return editor
              .chain()
              .undoInputRule()
              .command(({ tr }) => {
                tr.insertText(text);
                return true;
              })
              .run();
          },
        },
      }),
    ];
  },
});

const MARK_NAMES: Record<string, RichMark> = {
  bold: "bold",
  italic: "italic",
  strike: "strike",
  code: "code",
};

// A textblock's inline content as lines of runs; a hard break starts a new line.
function lines(node: JSONContent): RichRun[][] {
  const out: RichRun[][] = [[]];
  for (const child of node.content ?? []) {
    if (child.type === "hardBreak") {
      out.push([]);
    } else if (child.type === "text") {
      const marks: RichMark[] = [];
      let href: string | undefined;
      for (const mark of child.marks ?? []) {
        const name = MARK_NAMES[mark.type];
        if (name !== undefined) marks.push(name);
        else if (mark.type === "link") href = String(mark.attrs?.href ?? "");
      }
      const run: RichRun = { text: child.text ?? "", marks };
      if (href !== undefined) run.href = href;
      out[out.length - 1]!.push(run);
    }
  }
  return out.map(normalizeRuns);
}

// Every textblock inside a node, in order, code blocks among them.
function textblocks(node: JSONContent): JSONContent[] {
  if (
    node.type === "paragraph" ||
    node.type === "heading" ||
    node.type === "codeBlock"
  ) {
    return [node];
  } else {
    return (node.content ?? []).flatMap(textblocks);
  }
}

function codeBlock(node: JSONContent): RichBlock {
  return {
    kind: "code",
    text: (node.content ?? []).map((child) => child.text ?? "").join(""),
  };
}

/** The editor's document as rich text, a block per line. */
export function docToRich(doc: JSONContent): RichBlock[] {
  const blocks: RichBlock[] = [];
  for (const node of doc.content ?? []) {
    switch (node.type) {
      case "heading": {
        const level = Number(node.attrs?.level ?? 1);
        for (const runs of lines(node)) {
          blocks.push({ kind: "heading", level, runs });
        }
        break;
      }
      case "bulletList":
      case "orderedList": {
        let number = Number(node.attrs?.start ?? 1);
        for (const item of node.content ?? []) {
          for (const block of textblocks(item)) {
            if (block.type === "codeBlock") blocks.push(codeBlock(block));
            else {
              for (const runs of lines(block)) {
                blocks.push(
                  node.type === "bulletList"
                    ? { kind: "bullet", runs }
                    : { kind: "ordered", number: number++, runs },
                );
              }
            }
          }
        }
        break;
      }
      case "blockquote":
        for (const block of textblocks(node)) {
          if (block.type === "codeBlock") blocks.push(codeBlock(block));
          else {
            for (const runs of lines(block)) {
              blocks.push({ kind: "quote", runs });
            }
          }
        }
        break;
      case "codeBlock":
        blocks.push(codeBlock(node));
        break;
      default:
        for (const runs of lines(node)) {
          blocks.push({ kind: "paragraph", runs });
        }
    }
  }
  return blocks;
}

function inline(runs: readonly RichRun[]): JSONContent[] {
  return runs.map((run) => {
    const marks: JSONContent["marks"] = (run.marks ?? []).map((mark) => ({
      type: mark,
    }));
    if (run.href !== undefined) {
      marks.push({ type: "link", attrs: { href: run.href } });
    }
    return marks.length > 0
      ? { type: "text", text: run.text, marks }
      : { type: "text", text: run.text };
  });
}

function paragraph(runs: readonly RichRun[]): JSONContent {
  return runs.length > 0
    ? { type: "paragraph", content: inline(runs) }
    : { type: "paragraph" };
}

/** Rich text as the editor's document: neighboring list items and quote lines share one list or quote. */
export function richToDoc(blocks: readonly RichBlock[]): JSONContent {
  const content: JSONContent[] = [];
  let index = 0;
  while (index < blocks.length) {
    const block = blocks[index]!;
    if (block.kind === "paragraph") {
      content.push(paragraph(block.runs));
      index++;
    } else if (block.kind === "heading") {
      content.push({
        type: "heading",
        attrs: { level: block.level },
        content: inline(block.runs),
      });
      index++;
    } else if (block.kind === "code") {
      content.push(
        block.text === ""
          ? { type: "codeBlock" }
          : {
              type: "codeBlock",
              content: [{ type: "text", text: block.text }],
            },
      );
      index++;
    } else {
      const kind = block.kind;
      const group: RichBlock[] = [];
      while (index < blocks.length && blocks[index]!.kind === kind) {
        group.push(blocks[index]!);
        index++;
      }
      const paragraphs = group.map((item) =>
        paragraph("runs" in item ? item.runs : []),
      );
      if (kind === "quote") {
        content.push({ type: "blockquote", content: paragraphs });
      } else {
        const items = paragraphs.map((child) => ({
          type: "listItem",
          content: [child],
        }));
        const first = group[0]!;
        content.push(
          kind === "bullet"
            ? { type: "bulletList", content: items }
            : {
                type: "orderedList",
                attrs: { start: first.kind === "ordered" ? first.number : 1 },
                content: items,
              },
        );
      }
    }
  }
  return {
    type: "doc",
    content: content.length > 0 ? content : [{ type: "paragraph" }],
  };
}
