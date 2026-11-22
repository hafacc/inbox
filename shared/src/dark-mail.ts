import namedColors from "../themes/css-colors.json";

/** A color: sRGB channels 0–255 and alpha 0–1. */
export type Rgba = {
  /** red channel, 0–255 */
  readonly red: number;
  /** green channel, 0–255 */
  readonly green: number;
  /** blue channel, 0–255 */
  readonly blue: number;
  /** opacity, 0–1 */
  readonly alpha: number;
};

/** A number in a CSS color function, as the CSS parser read it. */
export type ColorArgument = {
  /** the number */
  readonly value: number;
  /** its unit: none, `%` or an angle unit */
  readonly unit: "" | "%" | "deg" | "turn" | "rad" | "grad";
};

/** The colors dark mail is drawn toward: the page behind it, and text and links it leaves unset. */
export type DarkPalette = {
  /** what the mail sits on */
  readonly page: Rgba;
  /** text color for text the mail leaves unset; also the lightest text the mail's own turns into */
  readonly text: Rgba;
  /** link color for links the mail leaves unset */
  readonly link: Rgba;
};

/** Which way a CSS property's colors are turned in dark mail. */
export type ColorRole = "text" | "background";

/** One stretch of text in mail, for `isDarkMail`: the background behind it and how many characters it has. */
export type TextSample = {
  /** the opaque background behind it, as the mail draws it */
  readonly background: Rgba;
  /** visible characters */
  readonly weight: number;
};

/** What mail says about its own dark design. */
export type ColorSchemeSignals = {
  /** media queries of its `@media` rules */
  readonly media: readonly string[];
  /** its `color-scheme` CSS values and `color-scheme`/`supported-color-schemes` meta contents */
  readonly colorSchemes: readonly string[];
};

/** Lowest contrast text may have against what is behind it (WCAG AA for body text). */
export const MIN_CONTRAST = 4.5;

// Luminance below which white text reads better than black: a dark color.
const DARK_LUMINANCE = 0.179;
// OKLab lightness of a gray at DARK_LUMINANCE; light colors flip to at most this, dark at least.
const MID_LIGHTNESS = Math.cbrt(DARK_LUMINANCE);
// Steps of halving when searching for a lightness or chroma.
const SEARCH_STEPS = 24;
// Gamut slack for float error.
const GAMUT_EPSILON = 1e-6;
// Channels above this read as white (as plain mail's boxes are judged).
const NEAR_WHITE = 235;
// Chroma below which a darkened color still reads as gray.
const GRAY_CHROMA = 0.02;
// Lightness slack when matching a darkened white.
const LIGHTNESS_SLACK = 0.01;

const TEXT_PROPERTIES = new Set([
  "color",
  "caret-color",
  "text-decoration",
  "text-decoration-color",
  "text-emphasis",
  "text-emphasis-color",
  "-webkit-text-fill-color",
  "-webkit-text-stroke",
  "-webkit-text-stroke-color",
]);
const BACKGROUND_PROPERTIES = new Set([
  "background",
  "background-color",
  "background-image",
  "box-shadow",
  "text-shadow",
  "column-rule",
  "column-rule-color",
]);
// Whitespace in HTML text, plus the no-break space mail pads with.
const BLANK = new Set([" ", "\t", "\n", "\r", "\f", " "]);

const NAMED = namedColors as unknown as Record<
  string,
  [number, number, number] | string
>;

/** An opaque color from channels. */
export function rgb(red: number, green: number, blue: number): Rgba {
  return { red, green, blue, alpha: 1 };
}

/** A CSS named color or `transparent`, or null for any other word. */
export function namedColor(name: string): Rgba | null {
  const key = name.toLowerCase();
  if (key === "transparent") return { red: 0, green: 0, blue: 0, alpha: 0 };
  const channels = key === "_doc" ? undefined : NAMED[key];
  if (!Array.isArray(channels)) return null;
  const [red, green, blue] = channels;
  return rgb(red, green, blue);
}

/** A hex color from its digits (3, 4, 6 or 8 of them, no `#`), or null. */
export function hexColor(digits: string): Rgba | null {
  if (!/^[0-9a-f]+$/i.test(digits)) return null;
  const full =
    digits.length === 3 || digits.length === 4
      ? [...digits].map((digit) => digit + digit).join("")
      : digits;
  if (full.length !== 6 && full.length !== 8) return null;
  const channel = (index: number): number =>
    Number.parseInt(full.slice(index * 2, index * 2 + 2), 16);
  return {
    red: channel(0),
    green: channel(1),
    blue: channel(2),
    // Alpha to 3 places, as CSS parsers keep it.
    alpha: full.length === 8 ? Math.round((channel(3) / 255) * 1000) / 1000 : 1,
  };
}

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function alphaFrom(argument: ColorArgument | undefined): number | null {
  if (argument === undefined) return 1;
  if (argument.unit === "%") return clamp(argument.value / 100, 0, 1);
  if (argument.unit === "") return clamp(argument.value, 0, 1);
  return null;
}

function degreesFrom(argument: ColorArgument): number | null {
  switch (argument.unit) {
    case "":
    case "deg":
      return argument.value;
    case "turn":
      return argument.value * 360;
    case "rad":
      return (argument.value * 180) / Math.PI;
    case "grad":
      return argument.value * 0.9;
    default:
      return null;
  }
}

function hslChannels(
  hue: number,
  saturation: number,
  lightness: number,
): [number, number, number] {
  const amount = saturation * Math.min(lightness, 1 - lightness);
  const part = (offset: number): number => {
    const position = (((offset + hue / 30) % 12) + 12) % 12;
    return (
      lightness - amount * Math.max(-1, Math.min(position - 3, 9 - position, 1))
    );
  };
  return [part(0), part(8), part(4)].map((value) =>
    Math.round(clamp(value * 255, 0, 255)),
  ) as [number, number, number];
}

/**
 * A color from a CSS color function (`rgb`, `rgba`, `hsl`, `hsla`) and its numbers in order,
 * or null for other functions and arguments that aren't valid.
 */
export function functionColor(
  name: string,
  args: readonly ColorArgument[],
): Rgba | null {
  const kind = name.toLowerCase();
  if (args.length !== 3 && args.length !== 4) return null;
  const [first, second, third, fourth] = args as [
    ColorArgument,
    ColorArgument,
    ColorArgument,
    ColorArgument?,
  ];
  const alpha = alphaFrom(fourth);
  if (alpha === null) return null;
  if (kind === "rgb" || kind === "rgba") {
    const channels = [first, second, third].map((argument) => {
      if (argument.unit === "%") return clamp(argument.value * 2.55, 0, 255);
      if (argument.unit === "") return clamp(argument.value, 0, 255);
      return null;
    });
    if (channels.some((channel) => channel === null)) return null;
    const [red, green, blue] = channels.map((channel) =>
      Math.round(channel!),
    ) as [number, number, number];
    return { red, green, blue, alpha };
  }
  if (kind === "hsl" || kind === "hsla") {
    const hue = degreesFrom(first);
    if (hue === null || second.unit !== "%" || third.unit !== "%") return null;
    const [red, green, blue] = hslChannels(
      hue,
      clamp(second.value / 100, 0, 1),
      clamp(third.value / 100, 0, 1),
    );
    return { red, green, blue, alpha };
  }
  return null;
}

/**
 * An HTML color attribute (`bgcolor`, `<font color>`): a named color, or 6 hex digits with or
 * without `#`, or 3 with it. Null for anything else, which browsers' own odd guesses don't
 * make worth following.
 */
export function attributeColor(value: string): Rgba | null {
  const trimmed = value.trim();
  if (trimmed === "" || trimmed.toLowerCase() === "transparent") return null;
  const named = namedColor(trimmed);
  if (named !== null) return named;
  const hashed = trimmed.startsWith("#");
  const digits = hashed ? trimmed.slice(1) : trimmed;
  if (digits.length === 6 || (hashed && digits.length === 3)) {
    return hexColor(digits);
  }
  return null;
}

function hex2(value: number): string {
  return Math.round(clamp(value, 0, 255))
    .toString(16)
    .padStart(2, "0");
}

/** A color as CSS: `#rrggbb` when opaque, else `rgba(…)`. */
export function cssColor(color: Rgba): string {
  if (color.alpha >= 1) {
    return `#${hex2(color.red)}${hex2(color.green)}${hex2(color.blue)}`;
  }
  const alpha = Math.round(color.alpha * 1000) / 1000;
  return `rgba(${Math.round(color.red)}, ${Math.round(color.green)}, ${Math.round(color.blue)}, ${alpha})`;
}

/** A color drawn over an opaque one: the opaque color that shows. */
export function composite(top: Rgba, bottom: Rgba): Rgba {
  if (top.alpha >= 1) return top;
  const mix = (over: number, under: number): number =>
    Math.round(over * top.alpha + under * (1 - top.alpha));
  return rgb(
    mix(top.red, bottom.red),
    mix(top.green, bottom.green),
    mix(top.blue, bottom.blue),
  );
}

function linear(channel: number): number {
  const value = channel / 255;
  return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
}

function encoded(value: number): number {
  return value <= 0.0031308
    ? value * 12.92
    : 1.055 * value ** (1 / 2.4) - 0.055;
}

/** WCAG relative luminance. */
export function relativeLuminance(color: Rgba): number {
  return (
    0.2126 * linear(color.red) +
    0.7152 * linear(color.green) +
    0.0722 * linear(color.blue)
  );
}

/** WCAG contrast ratio between two opaque colors. */
export function contrast(first: Rgba, second: Rgba): number {
  const [lighter, darker] = [
    relativeLuminance(first),
    relativeLuminance(second),
  ].sort((left, right) => right - left) as [number, number];
  return (lighter + 0.05) / (darker + 0.05);
}

function isDark(color: Rgba): boolean {
  return relativeLuminance(color) < DARK_LUMINANCE;
}

function toOklab(color: Rgba): [number, number, number] {
  const red = linear(color.red);
  const green = linear(color.green);
  const blue = linear(color.blue);
  const long = Math.cbrt(
    0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue,
  );
  const medium = Math.cbrt(
    0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue,
  );
  const short = Math.cbrt(
    0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue,
  );
  return [
    0.2104542553 * long + 0.793617785 * medium - 0.0040720468 * short,
    1.9779984951 * long - 2.428592205 * medium + 0.4505937099 * short,
    0.0259040371 * long + 0.7827717662 * medium - 0.808675766 * short,
  ];
}

function linearFromOklab(
  lightness: number,
  greenRed: number,
  blueYellow: number,
): [number, number, number] {
  const long =
    (lightness + 0.3963377774 * greenRed + 0.2158037573 * blueYellow) ** 3;
  const medium =
    (lightness - 0.1055613458 * greenRed - 0.0638541728 * blueYellow) ** 3;
  const short =
    (lightness - 0.0894841775 * greenRed - 1.291485548 * blueYellow) ** 3;
  return [
    4.0767416621 * long - 3.3077115913 * medium + 0.2309699292 * short,
    -1.2684380046 * long + 2.6097574011 * medium - 0.3413193965 * short,
    -0.0041960863 * long - 0.7034186147 * medium + 1.707614701 * short,
  ];
}

function inGamut(channels: [number, number, number]): boolean {
  return channels.every(
    (channel) => channel >= -GAMUT_EPSILON && channel <= 1 + GAMUT_EPSILON,
  );
}

/** The sRGB color at an OKLab lightness and hue, with as much of the chroma as fits. */
function fit(lightness: number, greenRed: number, blueYellow: number): Rgba {
  let scale = 1;
  if (!inGamut(linearFromOklab(lightness, greenRed, blueYellow))) {
    let low = 0;
    let high = 1;
    for (let step = 0; step < SEARCH_STEPS; step++) {
      const middle = (low + high) / 2;
      if (
        inGamut(
          linearFromOklab(lightness, greenRed * middle, blueYellow * middle),
        )
      ) {
        low = middle;
      } else {
        high = middle;
      }
    }
    scale = low;
  }
  const [red, green, blue] = linearFromOklab(
    lightness,
    greenRed * scale,
    blueYellow * scale,
  ).map((channel) => Math.round(encoded(clamp(channel, 0, 1)) * 255)) as [
    number,
    number,
    number,
  ];
  return rgb(red, green, blue);
}

/**
 * A background color for dark mail: a light one flipped to dark, keeping its hue (white becomes
 * the palette's page color, the lightest grays stay just above it); a dark one as it is.
 */
export function darkBackground(color: Rgba, palette: DarkPalette): Rgba {
  if (color.alpha === 0 || isDark(color)) return color;
  const [lightness, greenRed, blueYellow] = toOklab(color);
  const floor = toOklab(palette.page)[0];
  const flipped = Math.min(
    lightness,
    floor + ((1 - lightness) * (MID_LIGHTNESS - floor)) / (1 - MID_LIGHTNESS),
  );
  const scale = lightness > 0 ? flipped / lightness : 0;
  return {
    ...fit(flipped, greenRed * scale, blueYellow * scale),
    alpha: color.alpha,
  };
}

/**
 * A text color for dark mail: a dark one flipped to light, keeping its hue (black becomes as light
 * as the palette's text); a light one as it is.
 */
export function lightText(color: Rgba, palette: DarkPalette): Rgba {
  if (color.alpha === 0 || !isDark(color)) return color;
  const [lightness, greenRed, blueYellow] = toOklab(color);
  const ceiling = toOklab(palette.text)[0];
  const flipped = Math.max(
    lightness,
    ceiling - (lightness * (ceiling - MID_LIGHTNESS)) / MID_LIGHTNESS,
  );
  return { ...fit(flipped, greenRed, blueYellow), alpha: color.alpha };
}

/**
 * Opaque text made readable on an opaque background: as it is when it has `MIN_CONTRAST`,
 * else the least lightness change, keeping its hue, that reaches it (lighter on a dark
 * background, darker on a light one).
 */
export function readableText(text: Rgba, background: Rgba): Rgba {
  if (contrast(text, background) >= MIN_CONTRAST) return text;
  const [lightness, greenRed, blueYellow] = toOklab(text);
  let near = lightness;
  let far = isDark(background) ? 1 : 0;
  for (let step = 0; step < SEARCH_STEPS; step++) {
    const middle = (near + far) / 2;
    if (
      contrast(fit(middle, greenRed, blueYellow), background) >= MIN_CONTRAST
    ) {
      far = middle;
    } else {
      near = middle;
    }
  }
  return fit(far, greenRed, blueYellow);
}

/** Whether a CSS property's colors are text (lightened) or background (darkened), or neither. */
export function colorRole(property: string): ColorRole | null {
  const name = property.toLowerCase();
  if (TEXT_PROPERTIES.has(name)) return "text";
  if (
    BACKGROUND_PROPERTIES.has(name) ||
    name.startsWith("border") ||
    name.startsWith("outline")
  ) {
    return "background";
  }
  return null;
}

/** A color turned for dark mail by its role, without looking at what is behind it. */
export function darkColor(
  color: Rgba,
  role: ColorRole,
  palette: DarkPalette,
): Rgba {
  return role === "text"
    ? lightText(color, palette)
    : darkBackground(color, palette);
}

function squashed(text: string): string {
  return text.toLowerCase().split(/\s+/).join("");
}

/**
 * Whether a media query (one of a rule's comma-separated list) counts when working out mail's
 * colors: screen and size queries do, so mobile layouts' colors are covered; print, speech,
 * negated and color-scheme queries don't.
 */
export function mediaApplies(query: string): boolean {
  const words = query.trim().toLowerCase();
  if (words.startsWith("not ") || words.startsWith("not(")) return false;
  const flat = squashed(words);
  return !(
    flat.includes("print") ||
    flat.includes("speech") ||
    flat.includes("prefers-color-scheme")
  );
}

/**
 * Whether mail ships its own dark design: a `(prefers-color-scheme: dark)` media query, or a
 * color scheme that names `dark`. Such mail is drawn as its sender designed it.
 */
export function hasOwnDarkDesign(signals: ColorSchemeSignals): boolean {
  const namesDark = (value: string): boolean =>
    value
      .toLowerCase()
      .split(/[\s,]+/)
      .includes("dark");
  return (
    signals.colorSchemes.some(namesDark) ||
    signals.media.some((query) =>
      squashed(query).includes("(prefers-color-scheme:dark)"),
    )
  );
}

/**
 * Whether mail is already dark: more of its text sits on dark backgrounds than on light ones.
 * Such mail is drawn as sent.
 */
export function isDarkMail(samples: readonly TextSample[]): boolean {
  let dark = 0;
  let light = 0;
  for (const { background, weight } of samples) {
    if (isDark(background)) dark += weight;
    else light += weight;
  }
  return dark > light;
}

/** How many characters of a text node show: all but whitespace. */
export function visibleLength(text: string): number {
  let count = 0;
  for (const character of text) if (!BLANK.has(character)) count++;
  return count;
}

/** Whether a background is white or nearly (channels over 235), as plain mail's are. */
export function isNearWhite(color: Rgba): boolean {
  return (
    color.alpha === 0 ||
    (color.red > NEAR_WHITE &&
      color.green > NEAR_WHITE &&
      color.blue > NEAR_WHITE)
  );
}

/** Whether a background is what `darkBackground` makes of a near-white one. */
export function isDarkenedWhite(color: Rgba, palette: DarkPalette): boolean {
  if (color.alpha === 0) return true;
  const [lightness, greenRed, blueYellow] = toOklab(color);
  const lowest = toOklab(darkBackground(rgb(255, 255, 255), palette))[0];
  const edge = NEAR_WHITE + 1;
  const highest = toOklab(darkBackground(rgb(edge, edge, edge), palette))[0];
  return (
    Math.hypot(greenRed, blueYellow) < GRAY_CHROMA &&
    lightness >= lowest - LIGHTNESS_SLACK &&
    lightness <= highest + LIGHTNESS_SLACK
  );
}
