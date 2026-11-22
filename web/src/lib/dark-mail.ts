import {
  attributeColor,
  type ColorArgument,
  type ColorRole,
  colorRole,
  composite,
  cssColor,
  type DarkPalette,
  darkBackground,
  darkColor,
  functionColor,
  hasOwnDarkDesign,
  hexColor,
  isDarkMail,
  lightText,
  mediaApplies,
  namedColor,
  type Rgba,
  readableText,
  rgb,
  type TextSample,
  visibleLength,
} from "@inbox/shared/dark-mail";
import * as csstree from "css-tree";

/** How `darkenMessageHtml` draws mail. */
export type DarkenOptions = {
  /** the colors mail is turned toward */
  palette: DarkPalette;
  /** the mail's `color-scheme` meta contents, from cleaning */
  colorSchemes?: readonly string[];
  /** whether the mail's backgrounds give way to the page (plain mail), so text is judged on the page */
  plain?: boolean;
};

type Value =
  | { kind: "color"; color: Rgba }
  | { kind: "inherit" }
  | { kind: "initial" }
  | { kind: "current" };

type Longhand = "color" | "background-color";

type Declaration = { property: Longhand; value: Value; important: boolean };

type Rule = {
  selector: string;
  specificity: [number, number, number];
  order: number;
  declarations: Declaration[];
};

type Candidate = Declaration & {
  inline: boolean;
  specificity: [number, number, number];
  order: number;
};

type TextValue =
  | { kind: "author"; color: Rgba }
  | { kind: "default" }
  | { kind: "link" };

type Resolved = {
  text: TextValue;
  ownBackground: Rgba | null;
  behindOriginal: Rgba;
  behindFinal: Rgba;
};

// Pseudo-classes that depend only on the document, so a rule using them can be matched as written.
const STRUCTURAL = new Set([
  "first-child",
  "last-child",
  "only-child",
  "nth-child",
  "nth-last-child",
  "first-of-type",
  "last-of-type",
  "only-of-type",
  "nth-of-type",
  "nth-last-of-type",
  "root",
  "empty",
  "not",
]);
const COLOR_FUNCTIONS = new Set(["rgb", "rgba", "hsl", "hsla"]);
const ANGLE_UNITS = new Set(["deg", "turn", "rad", "grad"]);
// Elements whose `bgcolor` attribute browsers draw.
const BGCOLOR_TAGS = new Set([
  "BODY",
  "TABLE",
  "THEAD",
  "TBODY",
  "TFOOT",
  "TR",
  "TD",
  "TH",
]);
const WHITE = rgb(255, 255, 255);
// What mail assumes unset text and links look like: browsers' defaults on white.
const DEFAULT_TEXT = rgb(0, 0, 0);
const DEFAULT_LINK = rgb(0, 0, 238);

function nodeColor(node: csstree.CssNode): Rgba | null {
  if (node.type === "Hash") return hexColor(node.value);
  if (node.type === "Identifier") return namedColor(node.name);
  if (
    node.type !== "Function" ||
    !COLOR_FUNCTIONS.has(node.name.toLowerCase())
  ) {
    return null;
  }
  const args: ColorArgument[] = [];
  for (const child of node.children) {
    if (child.type === "Operator") continue;
    if (child.type === "Number") {
      args.push({ value: Number(child.value), unit: "" });
    } else if (child.type === "Percentage") {
      args.push({ value: Number(child.value), unit: "%" });
    } else if (
      child.type === "Dimension" &&
      ANGLE_UNITS.has(child.unit.toLowerCase())
    ) {
      args.push({
        value: Number(child.value),
        unit: child.unit.toLowerCase() as ColorArgument["unit"],
      });
    } else {
      return null;
    }
  }
  return functionColor(node.name, args);
}

function keyword(node: csstree.CssNode): Value | null {
  if (node.type !== "Identifier") return null;
  switch (node.name.toLowerCase()) {
    case "inherit":
      return { kind: "inherit" };
    case "initial":
    case "unset":
    case "revert":
    case "revert-layer":
      return { kind: "initial" };
    case "currentcolor":
      return { kind: "current" };
    default:
      return null;
  }
}

function singleValue(value: csstree.CssNode): Value | null {
  if (value.type !== "Value" || value.children.size !== 1) return null;
  const node = value.children.first!;
  const color = nodeColor(node);
  return color ? { kind: "color", color } : keyword(node);
}

/** The background color a `background` shorthand sets: its color, or transparent when it names none. */
function shorthandBackground(value: csstree.CssNode): Value | null {
  if (value.type !== "Value") return null;
  if (value.children.size === 1) {
    const only = keyword(value.children.first!);
    if (only && only.kind !== "current") return only;
  }
  let found: Value = { kind: "color", color: namedColor("transparent")! };
  for (const node of value.children) {
    const color = nodeColor(node);
    if (color) found = { kind: "color", color };
    else if (keyword(node)?.kind === "current") found = { kind: "current" };
  }
  return found;
}

function declarationsOf(
  block: csstree.CssNode,
  signals: string[],
): Declaration[] {
  const declarations: Declaration[] = [];
  csstree.walk(block, {
    visit: "Declaration",
    enter(node) {
      const property = node.property.toLowerCase();
      const important = Boolean(node.important);
      if (property === "color-scheme")
        signals.push(csstree.generate(node.value));
      let value: Value | null = null;
      let longhand: Longhand | null = null;
      if (property === "color" || property === "background-color") {
        value = singleValue(node.value);
        longhand = property;
      } else if (property === "background") {
        value = shorthandBackground(node.value);
        longhand = "background-color";
      }
      if (value && longhand) {
        declarations.push({ property: longhand, value, important });
      }
    },
  });
  return declarations;
}

function matchable(selector: csstree.CssNode): boolean {
  let allowed = true;
  csstree.walk(selector, (node) => {
    if (
      node.type === "PseudoElementSelector" ||
      node.type === "NestingSelector" ||
      (node.type === "PseudoClassSelector" &&
        !STRUCTURAL.has(node.name.toLowerCase()))
    ) {
      allowed = false;
    }
  });
  return allowed;
}

/**
 * A selector's specificity as [ids, classes, types]: attribute and pseudo-class selectors count as
 * classes, pseudo-elements as types, and `:not()` as its most specific argument.
 */
function specificityOf(selector: csstree.CssNode): [number, number, number] {
  const total: [number, number, number] = [0, 0, 0];
  const add = ([ids, classes, types]: [number, number, number]) => {
    total[0] += ids;
    total[1] += classes;
    total[2] += types;
  };
  if (selector.type !== "Selector") return total;
  for (const node of selector.children) {
    if (node.type === "IdSelector") add([1, 0, 0]);
    else if (
      node.type === "ClassSelector" ||
      node.type === "AttributeSelector"
    ) {
      add([0, 1, 0]);
    } else if (node.type === "TypeSelector" && node.name !== "*")
      add([0, 0, 1]);
    else if (node.type === "PseudoElementSelector") add([0, 0, 1]);
    else if (node.type === "PseudoClassSelector") {
      const list = node.children?.first;
      if (node.name.toLowerCase() === "not" && list?.type === "SelectorList") {
        let most: [number, number, number] = [0, 0, 0];
        for (const argument of list.children) {
          const part = specificityOf(argument);
          if (compareSpecificity(part, most) > 0) most = part;
        }
        add(most);
      } else {
        add([0, 1, 0]);
      }
    }
  }
  return total;
}

type Sheets = { rules: Rule[]; media: string[]; colorSchemes: string[] };

function collectRules(
  children: csstree.List<csstree.CssNode>,
  applies: boolean,
  sheets: Sheets,
): void {
  for (const node of children) {
    if (node.type === "Rule") {
      const declarations = declarationsOf(node.block, sheets.colorSchemes);
      if (!applies || node.prelude.type !== "SelectorList") continue;
      for (const selector of node.prelude.children) {
        if (!matchable(selector)) continue;
        sheets.rules.push({
          selector: csstree.generate(selector),
          specificity: specificityOf(selector),
          order: sheets.rules.length,
          declarations,
        });
      }
    } else if (node.type === "Atrule" && node.block) {
      const name = node.name.toLowerCase();
      if (name === "media") {
        const queries =
          node.prelude?.type === "AtrulePrelude"
            ? [...node.prelude.children].flatMap((child) =>
                child.type === "MediaQueryList"
                  ? [...child.children].map((query) => csstree.generate(query))
                  : [csstree.generate(child)],
              )
            : node.prelude
              ? [csstree.generate(node.prelude)]
              : [];
        sheets.media.push(...queries);
        collectRules(
          node.block.children,
          applies && queries.some(mediaApplies),
          sheets,
        );
      }
    }
  }
}

function compareSpecificity(
  left: [number, number, number],
  right: [number, number, number],
): number {
  for (let index = 0; index < 3; index++) {
    const difference = left[index]! - right[index]!;
    if (difference !== 0) return difference;
  }
  return 0;
}

/** The candidate the cascade picks: important first, then inline, then specificity, then order. */
function winner(candidates: readonly Candidate[]): Value | null {
  let best: Candidate | null = null;
  for (const candidate of candidates) {
    if (best === null) {
      best = candidate;
      continue;
    }
    const byImportance = Number(candidate.important) - Number(best.important);
    const byInline = Number(candidate.inline) - Number(best.inline);
    const bySpecificity = candidate.inline
      ? 0
      : compareSpecificity(candidate.specificity, best.specificity);
    const later =
      byImportance > 0 ||
      (byImportance === 0 &&
        (byInline > 0 ||
          (byInline === 0 &&
            (bySpecificity > 0 ||
              (bySpecificity === 0 && candidate.order >= best.order)))));
    if (later) best = candidate;
  }
  return best?.value ?? null;
}

function textOriginal(text: TextValue): Rgba {
  if (text.kind === "author") return text.color;
  else return text.kind === "link" ? DEFAULT_LINK : DEFAULT_TEXT;
}

function ownTextLength(element: Element): number {
  let length = 0;
  for (const child of element.childNodes) {
    if (child.nodeType === 3) length += visibleLength(child.textContent ?? "");
  }
  return length;
}

/** Turn every color in a declaration value that a role covers, in place. */
function turnColors(
  value: csstree.CssNode,
  role: ColorRole,
  palette: DarkPalette,
): void {
  csstree.walk(value, {
    enter(
      node: csstree.CssNode,
      item: csstree.ListItem<csstree.CssNode>,
      list: csstree.List<csstree.CssNode>,
    ) {
      const color = nodeColor(node);
      if (color === null || !list || !item) return;
      const turned = darkColor(color, role, palette);
      if (cssColor(turned) === cssColor(color)) return;
      const replacement = csstree.parse(cssColor(turned), {
        context: "value",
      }) as csstree.Value;
      list.replace(item, list.createItem(replacement.children.first!));
      if (node.type === "Function") return csstree.walk.skip;
    },
  });
}

function turnDeclarations(ast: csstree.CssNode, palette: DarkPalette): void {
  csstree.walk(ast, {
    visit: "Declaration",
    enter(node) {
      const role = colorRole(node.property);
      if (role) turnColors(node.value, role, palette);
    },
  });
}

/**
 * Sanitized message HTML with its colors turned for dark mode, or as it is when it is already
 * dark or ships its own dark design (see `hasOwnDarkDesign`, `isDarkMail`).
 *
 * Every color the mail sets (`bgcolor` and `<font color>` attributes, inline styles, `<style>`
 * rules, `background` shorthands) is worked out per element as the cascade and inheritance give
 * it. Light backgrounds turn dark and dark text light, keeping hue (`darkBackground`,
 * `lightText`), and text is then made to reach `MIN_CONTRAST` on the background actually behind
 * it (`readableText`); both are written on the element as `!important` inline styles. Colors the
 * mail sets that this can't place (borders, shadows, gradients, `:hover` rules) are turned by
 * their role alone. Android's `darkenMessageHtml` does the same; both run
 * `shared/testdata/dark-mail-fixtures.json`.
 */
export function darkenMessageHtml(
  cleanHtml: string,
  options: DarkenOptions,
): string {
  const key = `${JSON.stringify([options.palette, options.plain ?? false, options.colorSchemes ?? []])}\n${cleanHtml}`;
  const known = darkened.get(key);
  if (known !== undefined) {
    // Read again, so it moves to the back of the queue of entries to drop.
    darkened.delete(key);
    darkened.set(key, known);
    return known;
  }
  const result = darken(cleanHtml, options);
  darkened.set(key, result);
  while (darkened.size > DARKENED_KEPT) {
    const oldest = darkened.keys().next().value;
    if (oldest === undefined) break;
    darkened.delete(oldest);
  }
  return result;
}

// Mail darkened lately, by palette, options and HTML: switching themes and opening threads again
// reuse it.
const darkened = new Map<string, string>();
const DARKENED_KEPT = 32;
// Elements past which a message isn't resolved element by element: it shows as it is.
const MAX_DARKENED_ELEMENTS = 20000;

function darken(cleanHtml: string, options: DarkenOptions): string {
  const { palette, plain = false } = options;
  const document = new globalThis.window.DOMParser().parseFromString(
    `<!doctype html><html><head></head><body>${cleanHtml}</body></html>`,
    "text/html",
  );
  const body = document.body;
  const sheets: Sheets = {
    rules: [],
    media: [],
    colorSchemes: [...(options.colorSchemes ?? [])],
  };
  const styleSheets: [HTMLStyleElement, csstree.CssNode][] = [];
  for (const style of body.querySelectorAll("style")) {
    const ast = csstree.parse(style.textContent ?? "");
    styleSheets.push([style, ast]);
    if (ast.type === "StyleSheet") collectRules(ast.children, true, sheets);
  }
  // A style sheet's text isn't text the mail shows.
  const elements = [body, ...body.querySelectorAll("*")].filter(
    (element) => element.tagName !== "STYLE",
  );
  if (elements.length > MAX_DARKENED_ELEMENTS) return cleanHtml;
  const inlineStyles = new Map<Element, csstree.CssNode>();
  const candidates = new Map<Element, Candidate[]>(
    elements.map((element) => [element, []]),
  );
  // One native query per rule, rather than every element against every rule.
  for (const rule of sheets.rules) {
    let matched: Iterable<Element>;
    try {
      matched = document.querySelectorAll(rule.selector);
    } catch {
      continue;
    }
    for (const element of matched) {
      const list = candidates.get(element);
      if (list === undefined) continue;
      for (const declaration of rule.declarations) {
        list.push({
          ...declaration,
          inline: false,
          specificity: rule.specificity,
          order: rule.order,
        });
      }
    }
  }
  for (const element of elements) {
    const list = candidates.get(element)!;
    const style = element.getAttribute("style");
    if (style) {
      const ast = csstree.parse(style, { context: "declarationList" });
      inlineStyles.set(element, ast);
      declarationsOf(ast, sheets.colorSchemes).forEach((declaration, index) => {
        list.push({
          ...declaration,
          inline: true,
          specificity: [0, 0, 0],
          order: index,
        });
      });
    }
  }
  if (hasOwnDarkDesign(sheets)) return cleanHtml;

  const page: Resolved = {
    text: { kind: "default" },
    ownBackground: null,
    behindOriginal: WHITE,
    behindFinal: palette.page,
  };
  const resolved = new Map<Element, Resolved>();
  const samples: TextSample[] = [];
  const writes = new Map<Element, { background?: Rgba; text?: Rgba }>();
  for (const element of elements) {
    const parent =
      element === body ? page : (resolved.get(element.parentElement!) ?? page);
    const list = candidates.get(element)!;
    const colorValue = winner(list.filter((each) => each.property === "color"));
    let text: TextValue;
    if (colorValue?.kind === "color") {
      text = { kind: "author", color: colorValue.color };
    } else if (colorValue?.kind === "initial") {
      text = { kind: "default" };
    } else if (colorValue) {
      text = parent.text;
    } else {
      const fontColor =
        element.tagName === "FONT"
          ? attributeColor(element.getAttribute("color") ?? "")
          : null;
      if (fontColor) text = { kind: "author", color: fontColor };
      else if (element.tagName === "A" && element.hasAttribute("href")) {
        text = { kind: "link" };
      } else {
        text = parent.text;
      }
    }
    const backgroundValue = winner(
      list.filter((each) => each.property === "background-color"),
    );
    let background: Rgba | null = null;
    if (backgroundValue?.kind === "color") background = backgroundValue.color;
    else if (backgroundValue?.kind === "current") {
      background = textOriginal(text);
    } else if (backgroundValue?.kind === "inherit") {
      background = parent.ownBackground;
    } else if (!backgroundValue && BGCOLOR_TAGS.has(element.tagName)) {
      background = attributeColor(element.getAttribute("bgcolor") ?? "");
    }
    const own = background && background.alpha > 0 ? background : null;
    const behindOriginal = own
      ? composite(own, parent.behindOriginal)
      : parent.behindOriginal;
    const behindFinal =
      own && !plain
        ? darkBackground(behindOriginal, palette)
        : parent.behindFinal;
    resolved.set(element, {
      text,
      ownBackground: own,
      behindOriginal,
      behindFinal,
    });
    const write: { background?: Rgba; text?: Rgba } = {};
    if (own && !plain) write.background = behindFinal;
    const weight = ownTextLength(element);
    if (weight > 0) {
      samples.push({ background: behindOriginal, weight });
      const base =
        text.kind === "default"
          ? palette.text
          : text.kind === "link"
            ? palette.link
            : lightText(composite(text.color, behindOriginal), palette);
      write.text = readableText(base, behindFinal);
    }
    if (write.background || write.text) writes.set(element, write);
  }
  if (isDarkMail(samples)) return cleanHtml;

  for (const [style, ast] of styleSheets) {
    turnDeclarations(ast, palette);
    style.textContent = csstree.generate(ast);
  }
  for (const element of elements) {
    const ast = inlineStyles.get(element);
    if (ast) turnDeclarations(ast, palette);
    const write = writes.get(element);
    const parts = [ast ? csstree.generate(ast) : ""];
    if (write?.background) {
      parts.push(`background-color:${cssColor(write.background)}!important`);
    }
    if (write?.text) parts.push(`color:${cssColor(write.text)}!important`);
    const style = parts.filter((part) => part !== "").join(";");
    if (style !== "") element.setAttribute("style", style);
  }
  return body.innerHTML;
}
