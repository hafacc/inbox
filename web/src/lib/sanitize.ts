import { unwrapLink } from "@inbox/shared/links";
import { formatSize } from "@inbox/shared/time-format";
import DOMPurify from "dompurify";
import { CSS_URL, isRemoteUrl } from "./email-html";

/** A message body made safe to show, and the remote images it asks for; shared by every caller, so never changed. */
export type CleanHtml = {
  readonly html: string;
  readonly hasRemoteImages: boolean;
  /** remote image URLs, absolute (`//host` taken as https), in order of appearance, without repeats */
  readonly remoteImageUrls: readonly string[];
  /** contents of its `color-scheme` and `supported-color-schemes` meta tags, which cleaning drops */
  readonly colorSchemes: readonly string[];
};

const IMAGE_ATTRIBUTES = ["src", "background", "poster"];

/** What an inline image that couldn't be fetched offline says, after its name and size. */
export const OPENS_ONLINE = "Opens when you're back online";

/** An unfetched part's name (when it has one), size and {@link OPENS_ONLINE}, for its placeholder. */
export function missingPartLabel(filename: string, size: number): string {
  return [filename, formatSize(size), OPENS_ONLINE]
    .filter((piece) => piece !== "")
    .join(" · ");
}

// The box an unfetched inline image shows instead; colors follow the text so dark mail suits it.
const MISSING_IMAGE_STYLE =
  "display:inline-block;padding:6px 10px;border:1px dashed currentColor;border-radius:8px;font-size:12px;opacity:0.7";

// Wrappers looked through for a body's first or last paragraph; they have no margins of their own.
const MARGINLESS_WRAPPERS = new Set([
  "DIV",
  "CENTER",
  "SECTION",
  "ARTICLE",
  "MAIN",
  "HEADER",
  "FOOTER",
]);
// Elements that show nothing, passed over when looking for the first or last paragraph.
const UNSHOWN = new Set(["STYLE", "META", "LINK", "TITLE"]);

/** The element at one edge of `parent` (first or last shown child), or null when text or nothing is there. */
function edgeElement(parent: Element, last: boolean): Element | null {
  const children = [...parent.childNodes];
  if (last) children.reverse();
  for (const child of children) {
    if (child.nodeType === 3) {
      if ((child.textContent ?? "").trim() !== "") return null;
    } else if (child.nodeType === 1) {
      if (!UNSHOWN.has((child as Element).tagName)) return child as Element;
    }
  }
  return null;
}

/**
 * Drop the top margin of a body's leading paragraph and the bottom margin of its trailing one
 * (looking through plain wrappers), so mail written as paragraphs (Thunderbird's) gets no extra
 * space above or below; `shared/testdata/sanitize-fixtures.json` pins the rule.
 */
function trimEdgeMargins(body: Element): void {
  for (const [last, property] of [
    [false, "margin-top"],
    [true, "margin-bottom"],
  ] as const) {
    let element = edgeElement(body, last);
    while (element && MARGINLESS_WRAPPERS.has(element.tagName)) {
      element = edgeElement(element, last);
    }
    if (element?.tagName === "P") {
      const style = (element.getAttribute("style") ?? "").trim();
      const separator = style === "" || style.endsWith(";") ? "" : ";";
      element.setAttribute("style", `${style}${separator}${property}:0`);
    }
  }
}

/** A remote image URL made absolute: protocol-relative ones become https. */
export function absoluteImageUrl(url: string): string {
  const trimmed = url.trim();
  return trimmed.startsWith("//") ? `https:${trimmed}` : trimmed;
}

/** The entries of a `srcset`: URL and descriptor. */
function srcsetEntries(value: string): [string, string][] {
  return value
    .split(/,(?=\s*\S)/)
    .map((entry) => entry.trim())
    .filter((entry) => entry !== "")
    .map((entry) => {
      const [url = "", ...descriptor] = entry.split(/\s+/);
      return [url, descriptor.join(" ")];
    });
}

// Messages cleaned lately, by HTML and inline images: opening a thread again, or drawing a
// message beside the open thread and then in it, reuses the result.
const cleaned = new Map<string, CleanHtml>();
const CLEANED_KEPT = 32;

/** Drop every kept cleaning result, so nothing decrypted outlives a lock or the account. */
export function forgetCleaned(): void {
  cleaned.clear();
}

/**
 * Sanitize message HTML with DOMPurify: no scripts, forms or event handlers; `cid:` images
 * swapped for the data URLs in `cidUrls`, and an `<img>` whose part couldn't be fetched (offline)
 * swapped for a small box with its label from `cidMissing`; links unwrapped from click trackers
 * and opened in a new tab without a referrer; remote images reported and left in place for the
 * frame's policy to block until shown; a leading paragraph's top margin and a trailing one's
 * bottom margin dropped. The latest results are kept, so the same message cleans once.
 */
export function cleanMessageHtml(
  html: string,
  cidUrls: ReadonlyMap<string, string>,
  cidMissing: ReadonlyMap<string, string> = new Map(),
): CleanHtml {
  const key = `${JSON.stringify([[...cidUrls], [...cidMissing]])}\n${html}`;
  const known = cleaned.get(key);
  if (known !== undefined) {
    // Read again, so it moves to the back of the queue of entries to drop.
    cleaned.delete(key);
    cleaned.set(key, known);
    return known;
  }
  const result = clean(html, cidUrls, cidMissing);
  cleaned.set(key, result);
  for (const oldest of cleaned.keys()) {
    if (cleaned.size <= CLEANED_KEPT) break;
    cleaned.delete(oldest);
  }
  return result;
}

function clean(
  html: string,
  cidUrls: ReadonlyMap<string, string>,
  cidMissing: ReadonlyMap<string, string>,
): CleanHtml {
  const remote = new Set<string>();
  const note = (url: string): void => {
    remote.add(absoluteImageUrl(url));
  };
  const noteCss = (css: string): void => {
    for (const match of css.matchAll(CSS_URL)) {
      if (isRemoteUrl(match[2]!)) note(match[2]!);
    }
  };
  const purifier = DOMPurify(globalThis.window);
  const colorSchemes: string[] = [];
  purifier.addHook("uponSanitizeElement", (node, data) => {
    if (data.tagName !== "meta") return;
    const name = (node as Element).getAttribute("name")?.trim().toLowerCase();
    const content = (node as Element).getAttribute("content");
    if (
      content !== null &&
      (name === "color-scheme" || name === "supported-color-schemes")
    ) {
      colorSchemes.push(content);
    }
  });
  purifier.addHook("afterSanitizeAttributes", (node) => {
    if (node.nodeType !== 1) return;
    const element = node as Element;
    for (const attribute of IMAGE_ATTRIBUTES) {
      const value = element.getAttribute(attribute);
      if (value === null) continue;
      if (/^\s*cid:/i.test(value)) {
        const contentId = value.trim().slice(4).replace(/^<|>$/g, "");
        const url = cidUrls.get(contentId);
        const missing = cidMissing.get(contentId);
        if (url) {
          element.setAttribute(attribute, url);
        } else if (
          missing !== undefined &&
          attribute === "src" &&
          element.tagName === "IMG"
        ) {
          const box = element.ownerDocument.createElement("span");
          box.setAttribute("data-cid-missing", "");
          box.setAttribute("style", MISSING_IMAGE_STYLE);
          box.textContent = missing;
          element.replaceWith(box);
          return;
        } else {
          element.removeAttribute(attribute);
        }
      } else if (isRemoteUrl(value)) {
        note(value);
      }
    }
    const srcset = element.getAttribute("srcset");
    if (srcset !== null) {
      const entries = srcsetEntries(srcset);
      for (const [url] of entries) if (isRemoteUrl(url)) note(url);
      if (entries.length === 0) element.removeAttribute("srcset");
    }
    const style = element.getAttribute("style");
    if (style) noteCss(style);
    if (element.tagName === "STYLE" && element.textContent) {
      noteCss(element.textContent);
    }
    if (element.tagName === "A") {
      const href = element.getAttribute("href");
      if (href && /^\s*https?:/i.test(href)) {
        element.setAttribute("href", unwrapLink(href.trim()));
      }
      element.setAttribute("target", "_blank");
      element.setAttribute("rel", "noopener noreferrer");
    }
  });
  // With RETURN_DOM it hands back the cleaned `<body>`, though its type says only Node.
  const body = purifier.sanitize(html, {
    FORCE_BODY: true,
    FORBID_TAGS: ["form", "input", "button", "textarea", "select", "base"],
    ADD_ATTR: ["target"],
    ALLOW_UNKNOWN_PROTOCOLS: false,
    RETURN_DOM: true,
  }) as HTMLElement;
  trimEdgeMargins(body);
  return {
    html: body.innerHTML,
    hasRemoteImages: remote.size > 0,
    remoteImageUrls: [...remote],
    colorSchemes,
  };
}
