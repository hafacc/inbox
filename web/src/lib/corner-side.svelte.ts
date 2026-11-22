import type { CornerSide } from "@inbox/shared/hold-menu";
import { DeviceSetting } from "./device-setting.svelte";

/** Which bottom corner the round buttons sit in. Saved on this device, for every account; right by default. */
class CornerSideSetting extends DeviceSetting<CornerSide> {
  constructor() {
    super({
      key: "inbox:corner-side",
      parse: (raw) => (raw === "left" ? "left" : "right"),
      store: (side) => (side === "left" ? "left" : null),
    });
  }

  /** The corner the round buttons sit in. */
  get side(): CornerSide {
    return this.value;
  }
}

/** The one corner setting every round button follows. */
export const cornerSide = new CornerSideSetting();
