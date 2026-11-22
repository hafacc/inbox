import type { Attachment } from "svelte/attachments";

/**
 * Mark a `position: sticky` element with `data-stuck` while it is held at its `top`, so it can
 * show a shadow only then.
 */
export const stuck: Attachment<HTMLElement> = (node) => {
  const top = Number.parseFloat(getComputedStyle(node).top) || 0;
  // Shrinking the viewport's top by one pixel past `top` cuts a held element short by that pixel.
  const observer = new IntersectionObserver(
    ([entry]) => {
      if (!entry) return;
      const held =
        entry.intersectionRatio < 1 &&
        entry.boundingClientRect.top <= top + 1 &&
        entry.boundingClientRect.bottom > top;
      node.toggleAttribute("data-stuck", held);
    },
    { rootMargin: `-${top + 1}px 0px 0px 0px`, threshold: [0, 1] },
  );
  observer.observe(node);
  return () => observer.disconnect();
};
