import data from "../themes/label-icons.json";
import type { LabelTone } from "./themes";

/** An icon a label or bundle can show in place of its colored circle. */
export type LabelIcon = {
  /** stored id */
  id: string;
  /** name shown in the picker */
  name: string;
  /** the lucide icon web draws */
  lucide: string;
  /** the Material Icons Outlined icon Android draws */
  material: string;
};

/** Every icon the picker offers, in picker order. */
export const LABEL_ICONS: readonly LabelIcon[] = data.icons;

const ICON_IDS = new Set(LABEL_ICONS.map((icon) => icon.id));

/** The icon each Gmail category starts with. */
export const CATEGORY_ICONS: Readonly<Record<string, string>> = data.categories;

/** Stored in place of an icon id to show the colored circle instead. */
export const NO_ICON = "none";

/** The fixed color tone of each Gmail category, which Gmail doesn't let anyone color. */
export const CATEGORY_TONES: Readonly<Record<string, LabelTone>> =
  data.categoryTones as Record<string, LabelTone>;

/** Whether a label is one of Gmail's categories, whose name, color and icon are fixed. */
export function isCategory(labelId: string): boolean {
  return labelId in CATEGORY_ICONS;
}

/** One label's settings, `users/{uid}/labelPrefs/{labelId}`, with defaults filled in. */
export type LabelPrefs = {
  /** listed in the sidebar */
  sidebar: boolean;
  /** folded into inbox bundles */
  bundle: boolean;
  /** icon id, or null for the colored circle */
  icon: string | null;
};

/** The parts of a Gmail label its defaults come from. */
export type LabelBasis = {
  /** label id */
  id: string;
  /** Gmail's own "show in label list" choice */
  labelListVisibility?: string;
};

/** A label's settings from its stored doc (anything, possibly missing), with defaults for the rest; a category's icon is fixed. */
export function labelPrefsFrom(stored: unknown, label: LabelBasis): LabelPrefs {
  const doc =
    typeof stored === "object" && stored !== null
      ? (stored as Record<string, unknown>)
      : {};
  const icon = doc.icon;
  const categoryIcon = CATEGORY_ICONS[label.id];
  return {
    sidebar:
      typeof doc.sidebar === "boolean"
        ? doc.sidebar
        : label.labelListVisibility !== "labelHide",
    bundle: typeof doc.bundle === "boolean" ? doc.bundle : true,
    icon:
      categoryIcon !== undefined
        ? categoryIcon
        : typeof icon === "string" && ICON_IDS.has(icon)
          ? icon
          : null,
  };
}
