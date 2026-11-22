// How a thread's actions look wherever they are drawn as icons or wedges: their icons, names and colors.
import type { ThreadAction } from "@inbox/shared/view-filter";
import AlarmClockOff from "@lucide/svelte/icons/alarm-clock-off";
import Check from "@lucide/svelte/icons/check";
import Clock from "@lucide/svelte/icons/clock";
import Funnel from "@lucide/svelte/icons/funnel";
import Inbox from "@lucide/svelte/icons/inbox";
import ListPlus from "@lucide/svelte/icons/list-plus";
import Mail from "@lucide/svelte/icons/mail";
import MailCheck from "@lucide/svelte/icons/mail-check";
import MailOpen from "@lucide/svelte/icons/mail-open";
import OctagonAlert from "@lucide/svelte/icons/octagon-alert";
import Pencil from "@lucide/svelte/icons/pencil";
import Pin from "@lucide/svelte/icons/pin";
import PinOff from "@lucide/svelte/icons/pin-off";
import Tag from "@lucide/svelte/icons/tag";
import Trash2 from "@lucide/svelte/icons/trash-2";
import type { IconType } from "./components/icons";
import type { HoldTone } from "./hold-menu.svelte";

/** One action as a round menu's wedge, before it is placed. */
export type HoldMenuItemLook = {
  /** the action */
  id: string;
  /** its name */
  label: string;
  /** its glyph */
  icon: IconType;
  /** its colors */
  tone?: HoldTone;
};

/** Each thread action's icon; the importance marker draws its own. */
export const ACTION_ICONS: Record<ThreadAction, IconType | null> = {
  done: Check,
  inbox: Inbox,
  snooze: Clock,
  wake: AlarmClockOff,
  pin: Pin,
  unpin: PinOff,
  addTodo: ListPlus,
  editTodo: Pencil,
  label: Tag,
  important: null,
  unimportant: null,
  read: MailOpen,
  unread: Mail,
  spam: OctagonAlert,
  notSpam: MailCheck,
  delete: Trash2,
  untrash: Inbox,
  filter: Funnel,
};

/** A thread action's name; Snooze on a `snoozed` thread changes its time. */
export function threadActionWords(
  action: ThreadAction,
  snoozed: boolean,
): string {
  switch (action) {
    case "done":
      return "Done";
    case "inbox":
      return "Move to inbox";
    case "snooze":
      return snoozed ? "Change snooze time" : "Snooze";
    case "wake":
      return "Wake now";
    case "pin":
      return "Pin";
    case "unpin":
      return "Unpin";
    case "addTodo":
      return "Add todo";
    case "editTodo":
      return "Edit todo";
    case "label":
      return "Label";
    case "important":
      return "Mark important";
    case "unimportant":
      return "Mark not important";
    case "read":
      return "Mark read";
    case "unread":
      return "Mark unread";
    case "spam":
      return "Report spam";
    case "notSpam":
      return "Not spam";
    case "delete":
      return "Delete";
    case "untrash":
      return "Move to inbox";
    case "filter":
      return "Filter mail like this";
  }
}

/** A thread action's colors as a wedge: Done's green, the danger color for spam and delete, the accent for Unpin. */
export function threadActionTone(action: ThreadAction): HoldTone {
  if (action === "done") return "done";
  else if (action === "spam" || action === "delete") return "danger";
  else if (action === "unpin") return "on";
  else return "plain";
}
