import type { Attachment } from "svelte/attachments";
import { MediaQuery } from "svelte/reactivity";

/** Whether the window is phone-width: narrower than Tailwind's `sm` breakpoint. */
export const phoneWidth = new MediaQuery("(max-width: 639.98px)", false);

/**
 * Move an element to the end of `<body>` while it is shown, out of any ancestor that clips it or
 * holds its fixed position (a backdrop blur, a clip path). Attach it to an element wrapped in one
 * that stays in place, so Svelte's own bookkeeping of the outer one is untouched.
 */
export const portal: Attachment<HTMLElement> = (node) => {
  document.body.appendChild(node);
  return () => node.remove();
};
