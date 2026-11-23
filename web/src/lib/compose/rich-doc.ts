import {
  normalizeRuns,
  type RichBlock,
  type RichMark,
  type RichRun,
} from "@inbox/shared/compose";
import { finishedWebLink, safeHref } from "@inbox/shared/web-links";
import {
  type AnyExtension,
  type Editor,
  Extension,
  getMarkRange,
  InputRule,
  type JSONContent,
  markInputRule,
  markPasteRule,
} from "@tiptap/core";
import BoldBase from "@tiptap/extension-bold";
import ItalicBase from "@tiptap/extension-italic";
import { Placeholder } from "@tiptap/extensions";
import type { EditorState, Transaction } from "@tiptap/pm/state";
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
      link: {
        autolink: false,
        markdownLinks: true,
        openOnClick: false,
        // Typed, pasted or loaded, a link leads only to a web or mail address.
        isAllowedUri: (url) => safeHref(url) !== null,
      },
    }),
    boldExtension(),
    italicExtension(),
    markerEnd,
    addressEnd,
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

// Links the web address the caret ends, if any, in `tr`: the paragraph's text before the caret,
// less the `typed` characters just typed (a leaf such as a line break counts as one character, as
// in the document), by the shared rule, unless it is in code or already a link. Whether it linked one.
function linkFinishedAddress(
  state: EditorState,
  tr: Transaction,
  typed = 0,
): boolean {
  const { $from } = state.selection;
  const link = state.schema.marks.link;
  const code = state.schema.marks.code;
  if (!link || $from.parent.type.spec.code) return false;
  const before = $from.parent.textBetween(
    0,
    $from.parentOffset - typed,
    undefined,
    "\ufffc",
  );
  const found = finishedWebLink(before);
  if (found === null) return false;
  const from = $from.start() + found.start;
  const to = $from.start() + found.end;
  if (state.doc.rangeHasMark(from, to, link)) return false;
  if (code && state.doc.rangeHasMark(from, to, code)) return false;
  tr.addMark(from, to, link.create({ href: found.href }));
  return true;
}

// A web address becomes a link once it is finished: a space typed after it (a step of its own after
// the space, so undo or Backspace takes the link off and keeps the space) or Enter.
const addressEnd = Extension.create({
  name: "addressEnd",
  // Before the lists' and code blocks' own Enter.
  priority: 1000,
  addInputRules() {
    return [
      new InputRule({
        find: / $/,
        // Tiptap runs it on the text with the space already in.
        handler: ({ state }) =>
          linkFinishedAddress(state, state.tr, 1) ? undefined : null,
      }),
    ];
  },
  addKeyboardShortcuts() {
    return {
      Enter: ({ editor }) => {
        const { tr } = editor.state;
        if (linkFinishedAddress(editor.state, tr)) editor.view.dispatch(tr);
        return false;
      },
    };
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

/** A link in the editor: its document range, text and address. */
export type EditorLink = {
  from: number;
  to: number;
  text: string;
  href: string;
};

/**
 * What the link dialog is for: the link the selection starts in (`edit`), the selected text to
 * make a link (`selection`), or a new link at the caret (`insert`); where, and the text and
 * address it starts with.
 */
export type LinkRequest = EditorLink & {
  mode: "edit" | "selection" | "insert";
};

/** The link around document position `pos`, at either of its edges or inside it, or null. */
export function linkAround(editor: Editor, pos: number): EditorLink | null {
  const type = editor.schema.marks.link;
  const { doc } = editor.state;
  if (!type || pos < 0 || pos > doc.content.size) return null;
  const range = getMarkRange(doc.resolve(pos), type);
  if (!range) return null;
  const { from, to } = range;
  const mark = doc
    .resolve(from + 1)
    .marks()
    .find((each) => each.type === type);
  if (!mark) return null;
  return {
    from,
    to,
    text: doc.textBetween(from, to),
    href: String(mark.attrs.href ?? ""),
  };
}

/** What the link button (⌘K) opens the dialog for, from the editor's selection. */
export function linkRequest(editor: Editor): LinkRequest {
  const { from, to, empty } = editor.state.selection;
  const link = linkAround(editor, from);
  if (link !== null) {
    return { mode: "edit", ...link };
  } else if (!empty) {
    const text = editor.state.doc.textBetween(from, to);
    return { mode: "selection", from, to, text, href: "" };
  } else {
    return { mode: "insert", from, to, text: "", href: "" };
  }
}

/**
 * Save the link dialog: make `request`'s text a link to `href` with its words as `text` (an
 * edited link's words keep the styles its first letter had), the caret after it. A link to
 * anything but an http, https or mailto address isn't saved.
 */
export function saveLink(
  editor: Editor,
  request: LinkRequest,
  text: string,
  typedHref: string,
): void {
  const href = safeHref(typedHref);
  if (href === null) {
    return;
  } else if (request.mode === "selection") {
    editor
      .chain()
      .focus()
      .setTextSelection({ from: request.from, to: request.to })
      .setLink({ href })
      .setTextSelection(request.to)
      .run();
  } else if (request.mode === "insert") {
    editor
      .chain()
      .focus()
      .insertContentAt(request.from, {
        type: "text",
        text,
        marks: [{ type: "link", attrs: { href } }],
      })
      .unsetMark("link")
      .run();
  } else {
    const link = linkAround(editor, request.from + 1);
    if (link === null) return;
    const marks = editor.state.doc
      .resolve(link.from + 1)
      .marks()
      .filter((mark) => mark.type.name !== "link")
      .map((mark) => mark.toJSON());
    editor
      .chain()
      .focus()
      .insertContentAt(
        { from: link.from, to: link.to },
        {
          type: "text",
          text,
          marks: [...marks, { type: "link", attrs: { href } }],
        },
      )
      .unsetMark("link")
      .run();
  }
}

/** Turn `link` back into plain text, its words kept, the caret after it. */
export function removeLink(editor: Editor, link: EditorLink): void {
  editor
    .chain()
    .focus()
    .setTextSelection({ from: link.from, to: link.to })
    .unsetLink()
    .setTextSelection(link.to)
    .run();
}
