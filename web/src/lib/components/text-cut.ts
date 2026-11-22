import type { Attachment } from "svelte/attachments";

/**
 * Report through `onCut` whether the element's text is cut short (clamped or truncated), as its
 * size or `text` changes.
 */
export function textCut(
  text: string,
  onCut: (cut: boolean) => void,
): Attachment<HTMLElement> {
  return (node) => {
    void text;
    const check = (): void =>
      onCut(
        node.scrollHeight > node.clientHeight + 1 ||
          node.scrollWidth > node.clientWidth + 1,
      );
    const observer = new ResizeObserver(check);
    observer.observe(node);
    check();
    return () => observer.disconnect();
  };
}
