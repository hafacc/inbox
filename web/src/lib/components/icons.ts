import type { RowIcon } from "@inbox/shared/snooze-suggest";
import Calendar from "@lucide/svelte/icons/calendar";
import CalendarArrowUp from "@lucide/svelte/icons/calendar-arrow-up";
import CalendarDays from "@lucide/svelte/icons/calendar-days";
import type Inbox from "@lucide/svelte/icons/inbox";
import Moon from "@lucide/svelte/icons/moon";
import Sun from "@lucide/svelte/icons/sun";
import Sunrise from "@lucide/svelte/icons/sunrise";
import Timer from "@lucide/svelte/icons/timer";

/** A Lucide icon component. */
export type IconType = typeof Inbox;

/** The icon of each kind of time a snooze or send-later menu suggests. */
export const TIME_ROW_ICONS: Readonly<Record<RowIcon, IconType>> = {
  offset: Timer,
  todayDay: Sun,
  todayNight: Moon,
  tomorrow: Sunrise,
  days: CalendarDays,
  weekday: CalendarArrowUp,
  monthly: Calendar,
};
