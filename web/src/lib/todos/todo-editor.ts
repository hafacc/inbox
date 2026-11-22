// The todo editor's CodeMirror pieces, ported from latr's web/src/lib/components/text-editor.ts:
// latr's styles and tags marked in place, ⌘B/⌘I, and completing a `#tag` from the tags in use.

import {
  autocompletion,
  type Completion,
  type CompletionContext,
  pickedCompletion,
} from "@codemirror/autocomplete";
import {
  Compartment,
  EditorSelection,
  EditorState,
  type Extension,
  type StateEffect,
  StateField,
} from "@codemirror/state";
import {
  type Command,
  Decoration,
  type DecorationSet,
  EditorView,
} from "@codemirror/view";
import { parseInlineStyles, wrapSelection } from "@inbox/shared/compose";
import { completeTag, parseTags, typingTag } from "@inbox/shared/todos";

/** What a todo editor's owner can ask of it once it exists. */
export type TodoEditorHandle = {
  /** focus it, the caret at an offset or the end */
  focus: (caret?: number | "end") => void;
  /** take focus away */
  blur: () => void;
  /** the text offset nearest a point in the window */
  posAtCoords: (x: number, y: number) => number | null;
  /** select from `anchor` to `head` */
  select: (anchor: number, head: number) => void;
  /** focus with the caret on the first or last line nearest window x, kept for later up and down moves */
  focusAtEdge: (x: number, edge: "first" | "last") => void;
};

const BOLD = Decoration.mark({ class: "md-bold" });
const ITALIC = Decoration.mark({ class: "md-italic" });
const MARKER = Decoration.mark({ class: "md-marker" });
const TAG = Decoration.mark({ class: "md-tag" });

/** A `#tag`'s color (its name without `#`) as CSS, or null for the usual tag look. */
export type TagColor = (name: string) => string | null;

function inlineDecorations(doc: string, tagColor: TagColor): DecorationSet {
  const ranges = [];
  for (const span of parseInlineStyles(doc)) {
    ranges.push(
      (span.style === "bold" ? BOLD : ITALIC).range(span.start, span.end),
    );
    ranges.push(MARKER.range(span.start, span.start + 1));
    ranges.push(MARKER.range(span.end - 1, span.end));
  }
  for (const tag of parseTags(doc)) {
    const color = tagColor(doc.slice(tag.start + 1, tag.end));
    const mark =
      color === null
        ? TAG
        : Decoration.mark({
            class: "md-tag md-tag-label",
            attributes: { style: `--tag-color: ${color}` },
          });
    ranges.push(mark.range(tag.start, tag.end));
  }
  return Decoration.set(ranges, true);
}

// Holds the marks, so `recolorTags` can swap the colors in place.
const tagColors = new Compartment();

function inlineMarks(tagColor: TagColor): Extension {
  return StateField.define<DecorationSet>({
    create: (state) => inlineDecorations(state.doc.toString(), tagColor),
    update: (decorations, transaction) =>
      transaction.docChanged
        ? inlineDecorations(transaction.state.doc.toString(), tagColor)
        : decorations,
    provide: (field) => EditorView.decorations.from(field),
  });
}

/** latr's `*bold*`, `_italic_` and `#tags`, marked as they are typed; a tag naming a label in its color. */
export function inlineStyles(tagColor: TagColor): Extension {
  return tagColors.of(inlineMarks(tagColor));
}

/** The effect that marks the tags again in `tagColor`'s colors, as when the labels load or change. */
export function recolorTags(tagColor: TagColor): StateEffect<unknown> {
  return tagColors.reconfigure(inlineMarks(tagColor));
}

/** Wrap the selection in `marker`, or unwrap it, as latr's ⌘B and ⌘I do. */
export function toggleMarker(marker: "*" | "_"): Command {
  return (view) => {
    const { from, to } = view.state.selection.main;
    const edit = wrapSelection(view.state.doc.toString(), from, to, marker);
    view.dispatch({
      changes: { from: edit.from, to: edit.to, insert: edit.insert },
      selection: EditorSelection.range(edit.selStart, edit.selEnd),
      userEvent: "input",
    });
    return true;
  };
}

/** Known tags offered while a `#tag` is typed, and picking one. */
export type TagOffer = {
  /** the tag names offered, without `#` */
  names: string[];
  /** finish the typed tag as `#name` and a space */
  pick: (name: string) => void;
};

/** The `#tag` typed at the caret finished as `#name` plus a space, the caret after it. */
export function completeTagAt(view: EditorView, name: string): void {
  const doc = view.state.doc.toString();
  const caret = view.state.selection.main.head;
  const typed = typingTag(doc, caret);
  if (!typed) return;
  const done = completeTag(doc, typed, caret, name);
  view.dispatch({
    changes: {
      from: typed.start,
      to: caret,
      insert: done.text.slice(
        typed.start,
        done.text.length - (doc.length - caret),
      ),
    },
    selection: EditorSelection.cursor(done.caret),
    userEvent: "input.complete",
  });
}

/**
 * A dropdown of known tags while a `#tag` is typed; picking one finishes it and adds a space. A tag
 * naming a label shows in `tagColor`'s color.
 */
export function tagCompletion(
  suggest: (prefix: string) => string[],
  tagColor: TagColor = () => null,
): Extension {
  return autocompletion({
    override: [
      (context: CompletionContext) => {
        const typed = typingTag(context.state.doc.toString(), context.pos);
        if (!typed) return null;
        const names = suggest(typed.prefix);
        if (names.length === 0) return null;
        return {
          from: typed.start,
          filter: false,
          options: names.map(
            (name): Completion => ({
              label: `#${name}`,
              apply: (view, completion, from, to) => {
                const doc = view.state.doc.toString();
                const done = completeTag(
                  doc,
                  { start: from, prefix: "" },
                  to,
                  name,
                );
                view.dispatch({
                  changes: {
                    from,
                    to,
                    insert: done.text.slice(
                      from,
                      done.text.length - (doc.length - to),
                    ),
                  },
                  selection: EditorSelection.cursor(done.caret),
                  annotations: pickedCompletion.of(completion),
                  userEvent: "input.complete",
                });
              },
            }),
          ),
        };
      },
    ],
    icons: false,
    // In place of the plain label, which can't take a color of its own (hidden by the theme).
    addToOptions: [
      {
        position: 50,
        render: (completion) => {
          const label = document.createElement("span");
          label.className = "cm-tagCompletion";
          label.textContent = completion.label;
          const color = tagColor(completion.label.slice(1));
          if (color !== null) label.style.color = color;
          return label;
        },
      },
    ],
  });
}

/** The editor's look: the row's own type, with inbox's menu look for the tag dropdown. */
export const todoEditorTheme = EditorView.theme({
  "&": { backgroundColor: "transparent" },
  "&.cm-focused": { outline: "none" },
  ".cm-scroller": {
    fontFamily: "inherit",
    lineHeight: "inherit",
    overflow: "visible",
  },
  ".cm-content": { padding: "0", caretColor: "var(--color-accent)" },
  ".cm-line": { padding: "0" },
  ".cm-cursor": { borderLeftColor: "var(--color-accent)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection":
    { backgroundColor: "var(--color-accent-soft)" },
  ".cm-placeholder": { color: "var(--color-muted)" },
  ".cm-tooltip.cm-tooltip-autocomplete": {
    backgroundColor: "var(--color-surface-raised)",
    border: "none",
    borderRadius: "16px",
    boxShadow: "var(--shadow-pop)",
    padding: "4px",
    fontFamily: "inherit",
  },
  ".cm-tooltip.cm-tooltip-autocomplete > ul": {
    fontFamily: "inherit",
    maxHeight: "15em",
    minWidth: "8em",
  },
  ".cm-tooltip.cm-tooltip-autocomplete > ul > li": {
    padding: "4px 10px",
    borderRadius: "999px",
    color: "var(--color-accent-strong)",
    fontSize: "14px",
    lineHeight: "22px",
  },
  ".cm-tooltip.cm-tooltip-autocomplete .cm-completionLabel": {
    display: "none",
  },
  ".cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]": {
    backgroundColor: "var(--color-surface-hover)",
    color: "var(--color-accent-strong)",
  },
});

/** A one-line field: newlines typed or pasted become spaces. */
export const singleLine: Extension = [
  EditorView.clipboardInputFilter.of((text) => text.replace(/\r?\n/g, " ")),
  EditorState.transactionFilter.of((transaction) => {
    if (
      !transaction.docChanged ||
      !transaction.newDoc.toString().includes("\n")
    ) {
      return transaction;
    }
    const text = transaction.newDoc.toString();
    const changes = [];
    for (
      let index = text.indexOf("\n");
      index >= 0;
      index = text.indexOf("\n", index + 1)
    ) {
      changes.push({ from: index, to: index + 1, insert: " " });
    }
    return [transaction, { changes, sequential: true }];
  }),
];
