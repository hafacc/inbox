import { getContext, setContext } from "svelte";

const PREVIEW = Symbol("preview");

/** Mark what this component draws as a preview of a thread that isn't open: it fetches nothing and loads no remote images. */
export function setPreview(): void {
  setContext(PREVIEW, true);
}

/** Whether this component draws inside a preview (see {@link setPreview}). */
export function inPreview(): boolean {
  return getContext<boolean | undefined>(PREVIEW) === true;
}
