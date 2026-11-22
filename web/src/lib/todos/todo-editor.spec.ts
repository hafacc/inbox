import { describe, expect, test } from "bun:test";
import { EditorState } from "@codemirror/state";
import { type DecorationSet, EditorView } from "@codemirror/view";
import { inlineStyles, recolorTags, type TagColor } from "./todo-editor";

// The tag colors the editor's marks carry, in order.
function markedColors(state: EditorState): string[] {
  const colors: string[] = [];
  for (const source of state.facet(EditorView.decorations)) {
    const set = (
      typeof source === "function" ? null : source
    ) as DecorationSet | null;
    set?.between(0, state.doc.length, (_from, _to, decoration) => {
      const style = decoration.spec.attributes?.style as string | undefined;
      if (style) colors.push(style);
    });
  }
  return colors;
}

describe("inlineStyles", () => {
  test("tags take label colors that arrive after the editor opened", () => {
    const none: TagColor = () => null;
    const loaded: TagColor = (name) => (name === "work" ? "red" : null);
    const opened = EditorState.create({
      doc: "call #work #home",
      extensions: inlineStyles(none),
    });
    expect(markedColors(opened)).toEqual([]);
    const recolored = opened.update({
      effects: recolorTags(loaded),
    }).state;
    expect(markedColors(recolored)).toEqual(["--tag-color: red"]);
  });
});
