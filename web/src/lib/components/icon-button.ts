/** Visual circle of a round icon button: 32px, 36px or 48px across. */
export type IconButtonSize = "small" | "medium" | "large";

/** Gray icon that darkens on a light hover circle: the usual icon button colors. */
export const quietColors =
  "text-text-secondary hover:bg-surface-hover hover:text-text";

/** An action whose icon button lights up in its own color on hover. */
export type ActionTone = "done" | "snooze" | "pin" | "danger";

/** Gray icon that takes the action's color on a light tint of it on hover, by action. */
export const toneColors: Record<ActionTone, string> = {
  done: "text-text-secondary hover:bg-done-soft hover:text-done",
  snooze: "text-text-secondary hover:bg-snooze-soft hover:text-snooze",
  pin: "text-text-secondary hover:bg-accent-soft hover:text-accent",
  danger: "text-text-secondary hover:bg-danger-soft hover:text-danger",
};

/** An action's labeled button on touch, where nothing hovers: its own color at rest, as its swipe has it. */
export const touchToneColors: Record<"snooze" | "danger", string> = {
  snooze: "text-snooze hover:bg-snooze-soft",
  danger: "text-danger hover:bg-danger-soft",
};

/** Colors of a pressed Pin: the theme's accent, with its tint on hover. */
export const pinnedColors = "text-accent hover:bg-accent-soft";

/** Colors of the Snooze button while its menu is open. */
export const snoozeOpenColors = "bg-snooze-soft text-snooze";

/** Colors of an icon button whose menu is open. */
export const openColors = "bg-accent-soft text-accent-strong";

// The touch hit area reaches 48px around a smaller circle.
const circles: Record<IconButtonSize, string> = {
  small: "w-8 h-8 pointer-coarse:after:-inset-2",
  medium: "w-9 h-9 pointer-coarse:after:-inset-1.5",
  large: "w-12 h-12",
};

/** Classes of a round icon-only button: a circle of `size` that tints on hover, its icon centered, a 48px tap on touch. */
export function iconButton(
  size: IconButtonSize,
  colors: string = quietColors,
): string {
  return `relative ${circles[size]} shrink-0 flex items-center justify-center rounded-full transition-colors pointer-coarse:after:absolute pointer-coarse:after:content-[''] ${colors}`;
}
