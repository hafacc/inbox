import { marked } from "marked";
import support from "../../../../docs/support.md?raw";
import type { PageLoad } from "./$types";

/** docs/support.md as HTML, rendered when the page is prerendered. */
export const load: PageLoad = () => ({
  html: marked.parse(support, { async: false, gfm: true }),
});
