import {
  type ColorMode,
  DEFAULT_THEME,
  emailStyle,
} from "@inbox/shared/themes";

/** Content-Security-Policy for a message frame; remote images only once the reader allows them. */
export function messageCsp(showImages: boolean): string {
  const images = showImages ? "https: http: data: blob:" : "data: blob:";
  return [
    "default-src 'none'",
    `img-src ${images}`,
    "style-src 'unsafe-inline'",
    "font-src data:",
  ].join("; ");
}

/**
 * The document a sanitized message body is shown in: CSP, links opening in a new tab, base styles,
 * and the colors from `emailStyle` for the theme shown (in dark mode, for mail `darkenMessageHtml`
 * has turned dark). Its color scheme matches the frame's `scheme`, or the browser draws an opaque
 * white page behind it. With `codeBackground`, inline code the mail doesn't style itself is a
 * monospace chip on that color, as in the composer. On a phone (the frame marks its root
 * `data-phone`), designed mail gets no side padding of its own: the thread's gutter around the
 * frame is the only one.
 */
export function messageSrcdoc(
  cleanHtml: string,
  showImages: boolean,
  colors: string = emailStyle(DEFAULT_THEME, "light"),
  scheme: ColorMode = "light",
  codeBackground?: string,
): string {
  // Blocked images keep their space but don't show the browser's broken-image icon.
  const hideBlocked = showImages
    ? ""
    : 'img[src^="http"],img[src^="//"]{visibility:hidden}';
  const code = codeBackground
    ? `:where(:not(pre)>code){font-family:monospace;font-size:13px;padding:1px 4px;border-radius:4px;background:${codeBackground}}`
    : "";
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${messageCsp(showImages)}"><base target="_blank"><style>${hideBlocked}:root{color-scheme:${scheme}}html,body{margin:0;padding:0;font:14px/22px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;overflow-wrap:anywhere;height:auto!important;min-height:0!important}html{overflow-y:hidden}body{padding:12px 16px;overflow-x:auto}html[data-phone] body{padding:12px 0}img{max-width:100%;height:auto}table{max-width:100%}blockquote{margin:0 0 0 8px;padding-left:8px;border-left:2px solid}${colors}html[data-plain]{background:transparent}html[data-plain] body{padding:2px 0}${code}</style></head><body>${cleanHtml}</body></html>`;
}

/** Whether an image source loads from the network (rather than `data:`, `blob:` or `cid:`). */
export function isRemoteUrl(url: string): boolean {
  return /^\s*(https?:)?\/\//i.test(url);
}

/** A CSS `url(...)` reference; the second group is the URL. */
export const CSS_URL = /url\(\s*(['"]?)([^'")]+)\1\s*\)/gi;

/** Remote `url(...)` references in inline CSS, which load images just like `<img>`. */
export function hasRemoteCssUrl(css: string): boolean {
  for (const match of css.matchAll(CSS_URL)) {
    if (isRemoteUrl(match[2]!)) return true;
  }
  return false;
}
