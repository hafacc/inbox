/** A message as a thread's dates read it. */
export type DatedMessage = {
  /** Gmail's `internalDate`, epoch milliseconds */
  date: number;
  /** its Gmail labels */
  labelIds: readonly string[];
};

/** The dates a list can place a thread by, epoch milliseconds. */
export type ThreadDates = {
  /** its latest received message (not sent by the user), else its latest message */
  received: number;
  /** its latest message sent by the user, else its latest message */
  sent: number;
};

const SENT = "SENT";
const DRAFT = "DRAFT";

function latest(messages: readonly DatedMessage[]): number {
  return messages.reduce(
    (newest, message) => Math.max(newest, message.date),
    0,
  );
}

/**
 * The dates a thread is placed, sorted and dated by in lists.
 *
 * Every list but Sent uses `received`: the newest message the user didn't send (Gmail's SENT label
 * marks the ones they did), so a reply of theirs doesn't move the thread. A thread of only their
 * own messages uses its newest message. Sent uses `sent`: the newest message the user sent. Drafts
 * count only when the thread has nothing else. Gmail's message order isn't trusted: the newest date
 * wins wherever it is.
 */
export function threadDates(messages: readonly DatedMessage[]): ThreadDates {
  const real = messages.filter((message) => !message.labelIds.includes(DRAFT));
  const counted = real.length > 0 ? real : messages;
  const own = counted.filter((message) => message.labelIds.includes(SENT));
  const received = counted.filter(
    (message) => !message.labelIds.includes(SENT),
  );
  const newest = latest(counted);
  return {
    received: received.length > 0 ? latest(received) : newest,
    sent: own.length > 0 ? latest(own) : newest,
  };
}

/**
 * Threads as a list orders them: each thread once, newest first. Of a thread listed more than once,
 * the entry with the newest date stays (the first of equals); equal dates keep list order.
 */
export function newestThreadsFirst<Thread>(
  threads: readonly Thread[],
  idOf: (thread: Thread) => string,
  dateOf: (thread: Thread) => number,
): Thread[] {
  const kept = new Map<string, { thread: Thread; date: number }>();
  for (const thread of threads) {
    const id = idOf(thread);
    const date = dateOf(thread);
    const known = kept.get(id);
    if (known === undefined || date > known.date)
      kept.set(id, { thread, date });
  }
  return [...kept.values()]
    .sort((left, right) => right.date - left.date)
    .map(({ thread }) => thread);
}
