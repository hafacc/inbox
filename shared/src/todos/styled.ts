// latr's styled text tree, ported from latr's web/src/lib/utils/inline-style.ts (styledTree), for
// showing a todo with its markers dimmed, its `#tags` as chips and its web addresses as links.

import { type InlineStyle, parseInlineStyles } from "../compose/inline-style";
import { findWebLinks } from "../web-links";
import { parseTags } from "./tags";

/** A piece of a todo's text as shown: plain text, a style marker, a `#tag`, a web address, or a styled span. */
export type StyledNode =
  | { kind: "text"; text: string }
  | { kind: "marker"; text: string }
  | { kind: "tag"; text: string }
  | { kind: "link"; text: string; href: string }
  | { kind: "span"; style: InlineStyle; children: StyledNode[] };

/**
 * The (properly nested) styled spans as a tree, with markers, `#tags` and web addresses as their
 * own leaves. Web addresses are found first and count as letters to the style and tag rules, so no
 * marker or `#` inside one styles it.
 */
export function styledTree(text: string): StyledNode[] {
  const links = findWebLinks(text);
  let masked = text;
  for (const link of links) {
    masked =
      masked.slice(0, link.start) +
      "a".repeat(link.end - link.start) +
      masked.slice(link.end);
  }
  const spans = parseInlineStyles(masked);
  const leaves = [
    ...parseTags(masked).map((tag) => ({ ...tag, href: null })),
    ...links,
  ].sort((first, second) => first.start - second.start);
  let next = 0;
  // Pushes text[from, to), cutting out the parts of tags and links inside it.
  function pushText(nodes: StyledNode[], from: number, to: number) {
    let pos = from;
    for (const leaf of leaves) {
      const start = Math.max(leaf.start, pos);
      const end = Math.min(leaf.end, to);
      if (start >= end) continue;
      if (start > pos) {
        nodes.push({ kind: "text", text: text.slice(pos, start) });
      }
      const piece = text.slice(start, end);
      nodes.push(
        leaf.href === null
          ? { kind: "tag", text: piece }
          : { kind: "link", text: piece, href: leaf.href },
      );
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

/** A todo's text as a one-line summary shows it: its style markers dropped, its tags and web addresses kept whole. */
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
