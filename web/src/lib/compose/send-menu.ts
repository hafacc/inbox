import {
  type SendRow,
  seededRandom,
  unvariedSendTimes,
  type VarySetting,
  varySendTimes,
} from "@inbox/shared/send-suggest";
import type { RowIcon } from "@inbox/shared/snooze-suggest";

/** One suggestion of the schedule-send menu as shown. */
export type SendMenuItem = {
  /** label without its time */
  text: string;
  /** icon, as for snooze rows */
  icon: RowIcon;
  /** engine key, for `commit` */
  keyId: string;
  /** the suggestion as learned; feeds the stats */
  chosenAt: number;
  /** what the menu shows and the outbox gets */
  sendAt: number;
};

/** A fresh seed for one opening of the menu. */
export function freshSeed(): number {
  const seed = new Uint32Array(1);
  crypto.getRandomValues(seed);
  return seed[0]!;
}

/**
 * The menu's suggestions for one opening: each row's time shifted by "Vary scheduled send times"
 * (drawn from `seed`, so the times hold still while the menu is open), or as is when it's off.
 */
export function sendMenuItems(
  rows: readonly SendRow[],
  now: number,
  vary: VarySetting,
  seed: number,
): SendMenuItem[] {
  const times = rows.map((row) => row.time);
  const shifted = vary.enabled
    ? varySendTimes(times, now, seededRandom(seed), vary.maxMinutes)
    : unvariedSendTimes(times);
  return rows.map((row, index) => ({
    text: row.text,
    icon: row.icon,
    keyId: row.keyId,
    chosenAt: shifted[index]!.chosenAt,
    sendAt: shifted[index]!.sendAt,
  }));
}
