import type { ThreadAction } from "@inbox/shared/view-filter";
import { editorForSender } from "../filters/filter-view";
import type { MailState } from "../mail-state.svelte";

/**
 * Run thread action `action` on thread `id`, wherever it was picked: an open thread's header,
 * menu or corner button, a row's round menu or swipe, or a row's screen-reader buttons. `sender`
 * is whom "Filter mail like this" filters by; without one that action does nothing.
 */
export function runThreadAction(
  mail: MailState,
  id: string,
  action: ThreadAction,
  sender: string | null = null,
): void {
  switch (action) {
    case "done":
      mail.done([id]);
      break;
    case "inbox":
      mail.moveToInbox([id]);
      break;
    case "snooze":
      mail.openSnoozeMenu(id);
      break;
    case "wake":
      mail.wakeNow(id);
      break;
    case "pin":
    case "unpin":
      mail.togglePin(id);
      break;
    case "addTodo":
    case "editTodo":
      mail.editThreadTodo(id);
      break;
    case "label":
      mail.toggleLabelMenu(id);
      break;
    case "important":
    case "unimportant":
      mail.setImportant(id, action === "important");
      break;
    case "read":
    case "unread":
      mail.setUnread([id], action === "unread");
      break;
    case "spam":
      mail.markSpam([id]);
      break;
    case "notSpam":
      mail.notSpam([id]);
      break;
    case "delete":
      mail.trash([id]);
      break;
    case "untrash":
      mail.untrash([id]);
      break;
    case "filter":
      if (sender !== null) mail.editFilter(editorForSender(sender));
      break;
  }
}
