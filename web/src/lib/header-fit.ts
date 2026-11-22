/** Room between an open thread's header buttons, in px. */
export const HEADER_GAP = 12;
/** Width an open thread's subject keeps beside its header buttons, in px. */
export const SUBJECT_MIN = 160;
/** Shortest a label chip in an open thread's header gets, in px. */
export const CHIP_MIN = 40;
/** Width of the header's More button, in px. */
export const MORE_WIDTH = 36;

/**
 * Which of a row of buttons move into its More menu so the rest, and More, fit `room` px.
 *
 * `actions` are in row order and `widths` gives each one's width (36 when missing). Buttons go
 * from the end of the row first, except those in `lastToGo`, which go only once every other one
 * has, again from the end. The result keeps row order.
 */
export function collapsedActions<Action>(
  actions: readonly Action[],
  widths: ReadonlyMap<Action, number>,
  room: number,
  lastToGo: readonly Action[],
): Action[] {
  const width = (action: Action) => widths.get(action) ?? MORE_WIDTH;
  const order = [
    ...actions.filter((action) => !lastToGo.includes(action)).reverse(),
    ...actions.filter((action) => lastToGo.includes(action)).reverse(),
  ];
  const gone = new Set<Action>();
  let shown = actions.length;
  let used =
    actions.reduce((sum, action) => sum + width(action), 0) +
    HEADER_GAP * Math.max(0, shown - 1);
  for (const action of order) {
    const more = gone.size > 0 ? MORE_WIDTH + (shown > 0 ? HEADER_GAP : 0) : 0;
    if (used + more <= room) break;
    gone.add(action);
    used -= width(action) + (shown > 1 ? HEADER_GAP : 0);
    shown -= 1;
  }
  return actions.filter((action) => gone.has(action));
}
