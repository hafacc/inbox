/** A message of an opened thread as the opening rule sees it. */
export type OpeningMessage = {
  /** message id */
  id: string;
  /** whether it is unread */
  unread: boolean;
};

/** How a thread first shows when it opens. */
export type ThreadOpenState = {
  /** ids of the messages that start expanded, in thread order */
  expanded: string[];
  /** id of the message the view scrolls to, or null for an empty thread */
  scrollTo: string | null;
};

/**
 * How a thread opens: which messages start expanded and where the view scrolls.
 *
 * Every unread message and the last message start expanded. The view scrolls to the first unread
 * message, or to the last message when none is unread. `messages` are oldest first.
 */
export function threadOpenState(
  messages: readonly OpeningMessage[],
): ThreadOpenState {
  const last = messages.at(-1);
  const expanded = messages
    .filter((message) => message.unread || message === last)
    .map((message) => message.id);
  const firstUnread = messages.find((message) => message.unread);
  return { expanded, scrollTo: (firstUnread ?? last)?.id ?? null };
}
