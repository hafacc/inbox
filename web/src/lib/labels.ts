// User labels as the label menu and the label settings show them: nesting by `/` in the name,
// renames that carry nested labels along, and colors from Gmail's palette.
import {
  CATEGORY_CHOICE_ICONS,
  CATEGORY_CHOICE_TONES,
  CATEGORY_IDS,
  threadCategory,
} from "@inbox/shared/categories";
import {
  labelNameError,
  leafName,
  parentName,
  sortLabels,
} from "@inbox/shared/label-names";
import { type LabelTone, labelTone } from "@inbox/shared/themes";
import type { GmailLabel } from "./gmail/types";
import { CATEGORY_NAMES } from "./thread-summary";

/** A label color Gmail accepts: both values must come from its fixed palette. */
export type LabelColor = { backgroundColor: string; textColor: string };

/** The colors offered, one per theme tone, each saved as a color from Gmail's label palette. */
export const LABEL_COLORS: readonly (LabelColor & { tone: LabelTone })[] = [
  { tone: "red", backgroundColor: "#fb4c2f", textColor: "#ffffff" },
  { tone: "orange", backgroundColor: "#ffad47", textColor: "#ffffff" },
  { tone: "yellow", backgroundColor: "#fad165", textColor: "#000000" },
  { tone: "green", backgroundColor: "#16a766", textColor: "#ffffff" },
  { tone: "teal", backgroundColor: "#2da2bb", textColor: "#ffffff" },
  { tone: "blue", backgroundColor: "#4a86e8", textColor: "#ffffff" },
  { tone: "purple", backgroundColor: "#a479e2", textColor: "#ffffff" },
  { tone: "pink", backgroundColor: "#f691b3", textColor: "#ffffff" },
  { tone: "gray", backgroundColor: "#999999", textColor: "#ffffff" },
];

/** A label's color as the theme shows it: Gmail's color folded into the nearest theme tone. */
export function labelDot(
  label: Pick<GmailLabel, "color"> | null | undefined,
): string {
  return `var(--color-label-${labelTone(label?.color?.backgroundColor)})`;
}

/** A user label placed in the tree. */
export type LabelNode = {
  label: GmailLabel;
  /** the last part of the name */
  leaf: string;
  /** the name of the parent, or null at the top */
  parent: string | null;
  /** how deep it is nested, 0 at the top */
  depth: number;
};

/** User labels in tree order: each parent followed by its children (`compareLabelNames`). */
export function labelTree(labels: readonly GmailLabel[]): LabelNode[] {
  return sortLabels(labels.filter((label) => label.type === "user")).map(
    (label) => ({
      label,
      leaf: leafName(label.name),
      parent: parentName(label.name),
      depth: label.name.split("/").length - 1,
    }),
  );
}

/** One line of the label menu. */
export type LabelMenuItem = {
  label: GmailLabel;
  /** whether the thread has it */
  checked: boolean;
};

/** The label menu for a thread, filtered by what's typed (any part of the name). */
export function labelMenuItems(
  labels: readonly GmailLabel[],
  query: string,
  threadLabelIds: readonly string[],
): LabelMenuItem[] {
  const folded = query.trim().toLowerCase();
  return labelTree(labels)
    .filter(
      (node) => folded === "" || node.label.name.toLowerCase().includes(folded),
    )
    .map((node) => ({
      label: node.label,
      checked: threadLabelIds.includes(node.label.id),
    }));
}

/** Whether the menu should offer to create what's typed: nothing has that exact name. */
export function offersCreate(
  labels: readonly GmailLabel[],
  query: string,
): boolean {
  return labelNameError(labels, query) === null;
}

/** One choice of the label menu's Category group. */
export type CategoryMenuItem = {
  /** category label id */
  id: string;
  /** name shown */
  name: string;
  /** icon id */
  icon: string;
  /** color tone */
  tone: LabelTone;
  /** whether the thread is in it */
  checked: boolean;
};

/** The label menu's Category group for a thread: Gmail's categories, Primary first, its own checked. */
export function categoryMenuItems(
  threadLabelIds: readonly string[],
): CategoryMenuItem[] {
  const current = threadCategory(threadLabelIds);
  return CATEGORY_IDS.map((id) => ({
    id,
    name: CATEGORY_NAMES[id] ?? "Primary",
    icon: CATEGORY_CHOICE_ICONS[id] ?? "inbox",
    tone: CATEGORY_CHOICE_TONES[id] ?? "gray",
    checked: id === current,
  }));
}
