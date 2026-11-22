/** Formatting the composer's toolbar and shortcuts apply. */
export type FormatCommand = "bold" | "italic";

/** The reference to an inline image the body holds. */
export function imageMarkdown(filename: string, contentId: string): string {
  const alt = filename.replace(/[[\]\\]/g, "").replace(/\.[^.]+$/, "");
  return `![${alt}](cid:${contentId})`;
}

/** The body without its reference to image `contentId`; a line left blank by it goes too. */
export function withoutImage(markdown: string, contentId: string): string {
  const escaped = contentId.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const reference = new RegExp(`!\\[[^\\]]*\\]\\(cid:${escaped}\\)`, "g");
  return markdown
    .split("\n")
    .flatMap((line) => {
      const rest = line.replace(reference, "");
      return rest === line || rest.trim() !== "" ? [rest] : [];
    })
    .join("\n");
}
