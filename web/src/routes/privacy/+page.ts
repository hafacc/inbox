import { marked } from "marked";
import privacy from "../../../../docs/privacy.md?raw";
import type { PageLoad } from "./$types";

/** docs/privacy.md as HTML, rendered when the page is prerendered. */
export const load: PageLoad = () => ({
  html: marked.parse(privacy, { async: false, gfm: true }),
});
