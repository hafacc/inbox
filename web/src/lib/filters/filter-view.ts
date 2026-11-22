// The filter list's one-line summaries, and the editor's starting points.
import {
  type ConditionGroup,
  criteriaToConditions,
  criteriaToSearch,
  type FilterActions,
  type FilterEditor,
  type FilterField,
  type FilterRow,
  type GmailFilter,
  NO_ACTIONS,
  type RowOperator,
} from "@inbox/shared/filters";
import type { GmailLabel } from "../gmail/types";

/** A run of a summary line: muted words or the matched value. */
export type SummaryPart = { text: string; value: boolean };

/** What the list shows for one filter. */
export type FilterSummary = {
  /** the conditions as words */
  parts: SummaryPart[];
  /** the label it adds, if one of the user's */
  label: GmailLabel | null;
  /** the other actions as words */
  actions: string[];
  /** the whole search, as Gmail runs it */
  search: string;
};

/** What each field reads as in a summary and in the editor's field menu. */
export const FIELD_TEXT: Record<FilterField, string> = {
  from: "From",
  to: "To",
  subject: "Subject has",
  hasWords: "Has the words",
  doesNotHave: "Doesn't have",
  size: "Size",
  hasAttachment: "Has attachment",
};

/** The operators each field offers in the editor. */
export const FIELD_OPERATORS: Record<FilterField, RowOperator[]> = {
  from: ["contains"],
  to: ["contains"],
  subject: ["contains"],
  hasWords: ["contains"],
  doesNotHave: ["contains"],
  size: ["larger", "smaller"],
  hasAttachment: ["is"],
};

/** What each operator reads as. */
export const OPERATOR_TEXT: Record<RowOperator, string> = {
  contains: "contains",
  larger: "larger than",
  smaller: "smaller than",
  is: "yes",
};

/** What each group kind reads as in the editor. */
export const MATCH_TEXT: Record<ConditionGroup["match"], string> = {
  all: "All of these",
  any: "Any of these",
  not: "None of these",
};

function rowParts(row: FilterRow): SummaryPart[] {
  switch (row.field) {
    case "size":
      return [
        {
          text: row.operator === "smaller" ? "Smaller than" : "Larger than",
          value: false,
        },
        { text: row.value, value: true },
      ];
    case "hasAttachment":
      return [{ text: "Has attachment", value: false }];
    default:
      return [
        { text: FIELD_TEXT[row.field], value: false },
        { text: row.value, value: true },
      ];
  }
}

function groupParts(group: ConditionGroup): SummaryPart[] {
  const parts: SummaryPart[] = [];
  if (group.match === "not") parts.push({ text: "Not", value: false });
  const sameField =
    group.match === "any" &&
    group.rows.length > 1 &&
    group.rows.every((row) => row.field === group.rows[0]!.field);
  if (sameField) {
    parts.push(...rowParts(group.rows[0]!).slice(0, 1));
    group.rows.forEach((row, index) => {
      if (index > 0) parts.push({ text: "OR", value: false });
      parts.push({ text: row.value, value: true });
    });
    return parts;
  }
  group.rows.forEach((row, index) => {
    if (index > 0) {
      parts.push({ text: group.match === "any" ? "or" : "and", value: false });
    }
    parts.push(...rowParts(row));
  });
  return parts;
}

/** The list line for a filter: its first conditions, its label and its other actions. */
export function filterSummary(
  filter: GmailFilter,
  labels: readonly GmailLabel[],
): FilterSummary {
  const criteria = filter.criteria ?? {};
  const conditions = criteriaToConditions(criteria);
  const search = criteriaToSearch(criteria);
  const parts =
    conditions.kind === "raw" || conditions.groups.length === 0
      ? [
          { text: "Matches", value: false },
          { text: search, value: true },
        ]
      : groupParts(conditions.groups[0]!);
  const action = filter.action ?? {};
  const labelId = (action.addLabelIds ?? []).find((id) =>
    id.startsWith("Label_"),
  );
  const actions: string[] = [];
  const add = action.addLabelIds ?? [];
  const remove = action.removeLabelIds ?? [];
  if (remove.includes("INBOX")) actions.push("Skip inbox");
  if (remove.includes("UNREAD")) actions.push("Mark as read");
  if (add.includes("STARRED")) actions.push("Pin");
  if (remove.includes("SPAM")) actions.push("Never spam");
  if (add.includes("IMPORTANT")) actions.push("Mark important");
  if (remove.includes("IMPORTANT")) actions.push("Never important");
  if (add.includes("TRASH")) actions.push("Delete");
  if (action.forward) actions.push(`Forward to ${action.forward}`);
  return {
    parts,
    label: labels.find((label) => label.id === labelId) ?? null,
    actions,
    search,
  };
}

/** A new empty condition. */
export function blankRow(field: FilterField = "from"): FilterRow {
  return { field, operator: FIELD_OPERATORS[field][0]!, value: "" };
}

/** The editor for a new filter: one empty "all" row. */
export function newFilterEditor(): FilterEditor {
  return {
    conditions: {
      kind: "rows",
      groups: [{ match: "all", rows: [blankRow()] }],
    },
    actions: { ...NO_ACTIONS, otherAddLabelIds: [], otherRemoveLabelIds: [] },
  };
}

/** The editor for a filter made from a search (rows when the search parses into them). */
export function editorFromSearch(query: string): FilterEditor {
  return {
    conditions: criteriaToConditions({ query }),
    actions: { ...NO_ACTIONS, otherAddLabelIds: [], otherRemoveLabelIds: [] },
  };
}

/** The editor for future mail from a sender, skipping the inbox. */
export function editorForSender(email: string): FilterEditor {
  const actions: FilterActions = {
    ...NO_ACTIONS,
    archive: true,
    otherAddLabelIds: [],
    otherRemoveLabelIds: [],
  };
  return {
    conditions: {
      kind: "rows",
      groups: [
        {
          match: "all",
          rows: [{ field: "from", operator: "contains", value: email }],
        },
      ],
    },
    actions,
  };
}

/** Change a row's field, keeping its value when the new field takes text. */
export function withField(row: FilterRow, field: FilterField): FilterRow {
  const operators = FIELD_OPERATORS[field];
  const operator = operators.includes(row.operator)
    ? row.operator
    : operators[0]!;
  const keepsText =
    field !== "hasAttachment" && field !== "size" && row.field !== "size";
  return { field, operator, value: keepsText ? row.value : "" };
}

/** Whether two filters match the same mail and do the same, whatever order Gmail lists fields in. */
export function sameFilter(
  left: Pick<GmailFilter, "criteria" | "action">,
  right: Pick<GmailFilter, "criteria" | "action">,
): boolean {
  const canonical = (value: unknown): unknown =>
    Array.isArray(value)
      ? value.map(canonical).sort()
      : typeof value === "object" && value !== null
        ? Object.fromEntries(
            Object.entries(value)
              .filter(([, field]) => field !== undefined)
              .sort(([leftKey], [rightKey]) => leftKey.localeCompare(rightKey))
              .map(([key, field]) => [key, canonical(field)]),
          )
        : value;
  const key = (filter: Pick<GmailFilter, "criteria" | "action">) =>
    JSON.stringify({
      criteria: canonical(filter.criteria ?? {}),
      action: canonical(filter.action ?? {}),
    });
  return key(left) === key(right);
}
