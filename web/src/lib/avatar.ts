import { avatarIndex } from "@inbox/shared/themes";

/** An address's avatar colors from the theme: a muted fill and its initials' color, the same on every device. */
export function avatarColors(email: string): { fill: string; ink: string } {
  const index = avatarIndex(email);
  return {
    fill: `var(--color-avatar-${index})`,
    ink: `var(--color-on-avatar-${index})`,
  };
}
