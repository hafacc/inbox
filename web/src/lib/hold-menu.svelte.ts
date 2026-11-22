import type { HoldWedge } from "@inbox/shared/hold-menu";
import type { IconType } from "./components/icons";

/** How a wedge is colored: plain, destructive, Done's green, or a state that is on (Unpin). */
export type HoldTone = "plain" | "danger" | "done" | "on";

/** One action of a round menu, drawn in its wedge. */
export type HoldMenuItem = HoldWedge & {
  /** its name, shown in the pill and read out */
  label: string;
  /** its glyph */
  icon: IconType;
  /** its colors; plain when absent */
  tone?: HoldTone;
};

/** A round menu to show. */
export type HoldMenuRequest = {
  /** the disc's center, in window pixels */
  x: number;
  /** the disc's center, in window pixels */
  y: number;
  /** its actions */
  items: HoldMenuItem[];
  /** what the pill says while no wedge is under the finger */
  pill: string;
  /** what it says instead once the menu waits for a tap, when that differs */
  stickyPill?: string;
  /** its accessible name */
  label: string;
  /** the press still down that slides to a wedge, or null when it opens for taps and keys */
  pointerId: number | null;
  /** where that press started, in window pixels */
  press: { x: number; y: number };
  /** whether keyboard focus moves into it, and back where it was once it closes */
  focusFirst: boolean;
  /** run the action picked */
  onPick: (id: string) => void;
  /** it closed, picked or not */
  onClose?: () => void;
};

class HoldMenuState {
  /** the menu showing, or null */
  current = $state<HoldMenuRequest | null>(null);
  /** the index of the wedge under the finger or with focus, or -1 */
  active = $state(-1);
  /** a menu fading away after it closed, drawn but not taking input */
  fading = $state<HoldMenuRequest | null>(null);

  /** Show `request`, closing any other first. */
  open(request: HoldMenuRequest): void {
    this.close();
    this.fading = null;
    this.active = -1;
    this.current = request;
  }

  /** Close the menu showing, if any; with `fade`, it fades away rather than going at once. */
  close(fade = false): void {
    const shown = this.current;
    this.current = null;
    this.active = -1;
    if (fade && shown !== null) this.fading = shown;
    shown?.onClose?.();
  }
}

/** The one round menu of the page: a disc of actions that opens under a held press or a corner button. */
export const holdMenu = new HoldMenuState();
