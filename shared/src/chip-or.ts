// A chip that forms as the user types, and then sees `OR` typed after it, goes back into the text,
// since `from:alice OR world` only means what it says as text.
import {
  type Chip,
  formatChip,
  type ParsedQuery,
  parseQuery,
  tokenChip,
  tokenize,
} from "./search";

/** Where a chip left the free text: the chip, its place among the chips, and the free text before it. */
export type ChipSpot = {
  /** the chip that formed */
  chip: Chip;
  /** its index in the chips */
  index: number;
  /** free text before where the chip was */
  before: string;
};

/**
 * Spot of the one chip that `parseQuery(text, cursor, tags)` turned into `formed`, leaving `free`;
 * `index` is where the chip lands among the chips.
 */
export function chipSpot(
  text: string,
  cursor: number,
  formed: readonly Chip[],
  free: string,
  index: number,
  tags = false,
): ChipSpot | null {
  const [chip] = formed;
  if (chip === undefined || formed.length !== 1) return null;
  const chipText = formatChip(chip);
  const token = tokenize(text).find((candidate) => {
    if (cursor >= candidate.start && cursor <= candidate.end) return false;
    const made = tokenChip(candidate, tags);
    return made !== null && formatChip(made) === chipText;
  });
  if (token === undefined) return null;
  else {
    return {
      chip,
      index,
      before: free.slice(0, Math.min(token.start, free.length)),
    };
  }
}

/** Free text with the chip put back where it was when the next word is a top-level `OR`, else null. */
export function unchipBeforeOr(
  spot: ChipSpot,
  text: string,
  cursor: number,
): { text: string; cursor: number } | null {
  if (!text.startsWith(spot.before)) return null;
  const rest = text.slice(spot.before.length);
  if (tokenize(rest)[0]?.text !== "OR") return null;
  const lead = spot.before === "" || /\s$/.test(spot.before) ? "" : " ";
  const trail = /^\s/.test(rest) ? "" : " ";
  const inserted = `${lead}${formatChip(spot.chip)}${trail}`;
  return {
    text: `${spot.before}${inserted}${rest}`,
    cursor: cursor >= spot.before.length ? cursor + inserted.length : cursor,
  };
}

/** The search field after its free text changes. */
export type TypedField = {
  /** every chip, the ones just formed last */
  chips: Chip[];
  /** the free text parsed after the chips */
  parsed: ParsedQuery;
  /** where the last chip formed, while it can still go back before an `OR` */
  spot: ChipSpot | null;
};

function sameChip(left: Chip | undefined, right: Chip): boolean {
  return (
    left !== undefined &&
    left.operator === right.operator &&
    left.value === right.value &&
    left.negated === right.negated
  );
}

/**
 * The search field after the free text becomes `text`, with the cursor at `cursor` (null when the
 * field isn't focused): finished operators (and, with `tags`, `#tags`) become chips, and the last
 * chip goes back into the text when `OR` is typed after it.
 */
export function typeFree(
  chips: readonly Chip[],
  spot: ChipSpot | null,
  text: string,
  cursor: number | null,
  tags = false,
): TypedField {
  const back =
    spot !== null &&
    spot.index === chips.length - 1 &&
    sameChip(chips[spot.index], spot.chip)
      ? unchipBeforeOr(spot, text, cursor ?? text.length)
      : null;
  const kept = back === null ? [...chips] : chips.slice(0, -1);
  const typed = back?.text ?? text;
  const at = back?.cursor ?? cursor;
  const parsed = parseQuery(typed, at, tags);
  if (parsed.chips.length === 0) {
    return { chips: kept, parsed, spot: back === null ? spot : null };
  } else {
    return {
      chips: [...kept, ...parsed.chips],
      parsed,
      spot: chipSpot(
        typed,
        at ?? typed.length,
        parsed.chips,
        parsed.free,
        kept.length,
        tags,
      ),
    };
  }
}
