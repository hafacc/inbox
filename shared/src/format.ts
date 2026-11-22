// Small text and date helpers used across the shared modules.

import { normalizeNewlines } from "./compose/encoding";

/** English month abbreviations, January first. */
export const MONTH_ABBREVIATIONS: readonly string[] = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/** Locale for day and month names, which always sit inside English text. */
export const LABEL_LOCALE = "en-US";

const dateTimeFormats = new Map<string, Intl.DateTimeFormat>();

/** An {@link LABEL_LOCALE} `Intl.DateTimeFormat` for these options, built once and reused. */
export function dateTimeFormat(
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  const key = JSON.stringify(options);
  let format = dateTimeFormats.get(key);
  if (format === undefined) {
    format = new Intl.DateTimeFormat(LABEL_LOCALE, options);
    dateTimeFormats.set(key, format);
  }
  return format;
}

/** A whole number in decimal, zero-padded to `width` digits. */
export function zeroPad(value: number, width = 2): string {
  return String(value).padStart(width, "0");
}

/** Text escaped for HTML content and double-quoted attributes. */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** Text with LF line breaks, trailing spaces and tabs on each line and leading and trailing blank lines removed. */
export function trimLines(text: string): string {
  return normalizeNewlines(text)
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/, ""))
    .join("\n")
    .replace(/^\n+|\n+$/g, "");
}
