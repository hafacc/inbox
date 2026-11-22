// latr's styled text tree, ported from latr's web/src/lib/utils/inline-style.ts (styledTree), for
// showing a todo with its markers dimmed and its `#tags` as chips.

import { type InlineStyle, parseInlineStyles } from "../compose/inline-style";
import { parseTags } from "./tags";

/** A piece of a todo's text as shown: plain text, a style marker, a `#tag`, or a styled span. */
export type StyledNode =
  | { kind: "text"; text: string }
  | { kind: "marker"; text: string }
  | { kind: "tag"; text: string }
  | { kind: "span"; style: InlineStyle; children: StyledNode[] };

/** The (properly nested) styled spans as a tree, with markers and `#tags` as their own leaves. */
export function styledTree(text: string): StyledNode[] {
  const spans = parseInlineStyles(text);
  const tags = parseTags(text);
  let next = 0;
  // Pushes text[from, to), cutting out the parts of tags inside it.
  function pushText(nodes: StyledNode[], from: number, to: number) {
    let pos = from;
    for (const tag of tags) {
      const start = Math.max(tag.start, pos);
      const end = Math.min(tag.end, to);
      if (start >= end) continue;
      if (start > pos) {
        nodes.push({ kind: "text", text: text.slice(pos, start) });
      }
      nodes.push({ kind: "tag", text: text.slice(start, end) });
      pos = end;
    }
    if (pos < to) nodes.push({ kind: "text", text: text.slice(pos, to) });
  }
  function build(limit: number, from: number): StyledNode[] {
    const nodes: StyledNode[] = [];
    let pos = from;
    while (pos < limit) {
      const span = spans[next];
      if (span && span.start < limit) {
        if (span.start > pos) pushText(nodes, pos, span.start);
        next++;
        const inner = build(span.end - 1, span.start + 1);
        nodes.push({
          kind: "span",
          style: span.style,
          children: [
            { kind: "marker", text: text.charAt(span.start) },
            ...inner,
            { kind: "marker", text: text.charAt(span.end - 1) },
          ],
        });
        pos = span.end;
      } else {
        pushText(nodes, pos, limit);
        pos = limit;
      }
    }
    return nodes;
  }
  return build(text.length, 0);
}

/** A todo's text as a one-line summary shows it: its style markers dropped, its tags kept. */
export function styledPlain(text: string): string {
  const flatten = (nodes: readonly StyledNode[]): string =>
    nodes
      .map((node) =>
        node.kind === "span"
          ? flatten(node.children)
          : node.kind === "marker"
            ? ""
            : node.text,
      )
      .join("");
  return flatten(styledTree(text)).trim();
}
