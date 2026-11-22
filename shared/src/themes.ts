import palette from "../themes/themes.json";
import type { DarkPalette, Rgba } from "./dark-mail";

/** Light or dark. */
export type ColorMode = "light" | "dark";

/** A color theme's id, as stored in `users/{uid}.settings.theme`. */
export type ThemeId = "green" | "blue" | "plum" | "clay" | "slate";

/** A Gmail label color folded into a theme. */
export type LabelTone =
  | "red"
  | "orange"
  | "yellow"
  | "green"
  | "teal"
  | "blue"
  | "purple"
  | "pink"
  | "gray";

/** A color theme as the settings list it. */
export type ThemeInfo = {
  /** stored id */
  id: ThemeId;
  /** name shown */
  name: string;
};

type Palette = {
  default: string;
  neutral: Record<ColorMode, Record<string, string>>;
  themes: {
    id: string;
    name: string;
    emailLink: Record<ColorMode, string>;
    light: Record<string, string>;
    dark: Record<string, string>;
  }[];
  avatars: Record<ColorMode, [string, string][]>;
  email: {
    dim: string;
  } & Record<ColorMode, { background: string; text: string; quote: string }>;
  labelTones: {
    order: LabelTone[];
    light: Record<LabelTone, string>;
    dark: Record<LabelTone, string>;
  };
};

const PALETTE = palette as unknown as Palette;

/** The theme used until one is chosen. */
export const DEFAULT_THEME = PALETTE.default as ThemeId;

/** Every theme, in the order the picker shows them. */
export const THEMES: readonly ThemeInfo[] = PALETTE.themes.map(
  ({ id, name }) => ({ id: id as ThemeId, name }),
);

/** A short hash of the palette, so a copy of its colors saved elsewhere can tell it is out of date. */
export const PALETTE_VERSION = (() => {
  let hash = 0;
  for (const char of JSON.stringify(palette)) {
    hash = (Math.imul(hash, 31) + char.charCodeAt(0)) | 0;
  }
  return (hash >>> 0).toString(36);
})();

/** How many avatar color pairs a theme has. */
export const AVATAR_COUNT = PALETTE.avatars.light.length;

/** A stored theme id, or the default when it is missing or unknown. */
export function themeIdFrom(value: unknown): ThemeId {
  return THEMES.some((theme) => theme.id === value)
    ? (value as ThemeId)
    : DEFAULT_THEME;
}

/** Every color of a theme in a mode, by token name (`bg`, `accent`, `avatar-0`, `label-red`, …). */
export function themeColors(
  id: ThemeId,
  mode: ColorMode,
): Record<string, string> {
  const theme =
    PALETTE.themes.find((entry) => entry.id === id) ??
    PALETTE.themes.find((entry) => entry.id === DEFAULT_THEME)!;
  const colors: Record<string, string> = {
    ...PALETTE.neutral[mode],
    ...theme[mode],
  };
  PALETTE.avatars[mode].forEach(([background, foreground], index) => {
    colors[`avatar-${index}`] = background;
    colors[`on-avatar-${index}`] = foreground;
  });
  for (const tone of PALETTE.labelTones.order) {
    colors[`label-${tone}`] = PALETTE.labelTones[mode][tone];
  }
  return colors;
}

/**
 * The colors of a message body as CSS for its document: the page, and text, link and quote colors
 * for whatever the mail leaves unset. Light draws the mail as sent on white. Dark goes with mail
 * whose own colors `darkPalette` and shared/src/dark-mail.ts have turned dark, and dims pictures a
 * little.
 */
export function emailStyle(id: ThemeId, mode: ColorMode): string {
  const theme =
    PALETTE.themes.find((entry) => entry.id === id) ??
    PALETTE.themes.find((entry) => entry.id === DEFAULT_THEME)!;
  const { email } = PALETTE;
  const colors = email[mode];
  const base = `html{background:${colors.background}}body{color:${colors.text}}a{color:${theme.emailLink[mode]}}blockquote{border-left-color:${colors.quote}}`;
  if (mode === "light") return base;
  return `${base}img,video{filter:${email.dim}}`;
}

/** The colors dark mail is turned toward for a theme: its page, unset text and unset links. */
export function darkPalette(id: ThemeId): DarkPalette {
  const theme =
    PALETTE.themes.find((entry) => entry.id === id) ??
    PALETTE.themes.find((entry) => entry.id === DEFAULT_THEME)!;
  const { dark } = PALETTE.email;
  const color = (hex: string): Rgba => {
    const [red, green, blue] = hexChannels(hex)!;
    return { red, green, blue, alpha: 1 };
  };
  return {
    page: color(dark.background),
    text: color(dark.text),
    link: color(theme.emailLink.dark),
  };
}

function hexChannels(hex: string): [number, number, number] | null {
  const match = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (match === null) return null;
  const value = Number.parseInt(match[1]!, 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

/** The theme tone a Gmail label color folds into: by hue, or gray when it is nearly gray. */
export function labelTone(hex: string | null | undefined): LabelTone {
  const channels = hex ? hexChannels(hex) : null;
  if (channels === null) return "gray";
  const [red, green, blue] = channels.map((channel) => channel / 255) as [
    number,
    number,
    number,
  ];
  const max = Math.max(red, green, blue);
  const min = Math.min(red, green, blue);
  const lightness = (max + min) / 2;
  const chroma = max - min;
  const saturation =
    chroma === 0 ? 0 : chroma / (1 - Math.abs(2 * lightness - 1));
  if (saturation < 0.15) return "gray";
  let hue: number;
  if (max === red) hue = ((green - blue) / chroma) % 6;
  else if (max === green) hue = (blue - red) / chroma + 2;
  else hue = (red - green) / chroma + 4;
  hue = (hue * 60 + 360) % 360;
  if (hue < 15) return "red";
  if (hue < 40) return "orange";
  if (hue < 70) return "yellow";
  if (hue < 160) return "green";
  if (hue < 195) return "teal";
  if (hue < 255) return "blue";
  if (hue < 290) return "purple";
  if (hue < 345) return "pink";
  return "red";
}

/** Which avatar color pair an address gets: stable, the same on every device. */
export function avatarIndex(email: string, count = AVATAR_COUNT): number {
  let hash = 0;
  for (const character of email.toLowerCase()) {
    hash = (hash * 31 + (character.codePointAt(0) ?? 0)) >>> 0;
  }
  return hash % count;
}

function luminance(hex: string): number {
  const channels = hexChannels(hex);
  if (channels === null) throw new Error(`not a color: ${hex}`);
  const [red, green, blue] = channels.map((channel) => {
    const value = channel / 255;
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** WCAG contrast ratio between two `#rrggbb` colors. */
export function contrastRatio(first: string, second: string): number {
  const [lighter, darker] = [luminance(first), luminance(second)].sort(
    (left, right) => right - left,
  ) as [number, number];
  return (lighter + 0.05) / (darker + 0.05);
}
