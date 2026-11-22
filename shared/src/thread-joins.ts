// Gmail files an encrypted reply in a thread of its own, since its outer Subject is the hidden one
// ("...") rather than "Re: …". Its In-Reply-To and References stay readable, so the list joins it
// back to the thread it answers and shows them as one row. Gmail itself stays split. Web and Android
// (:core ThreadJoins.kt) both run shared/testdata/thread-join-fixtures.json.

// Imports with `.js` and only from `wake`: functions/ (NodeNext) compiles this in for Done from a
// notification (`answeredSearch`).
import { compareHistoryIds } from "./wake.js";

/** Most `Message-ID`s the search for the threads an encrypted reply answers names. */
export const MAX_ANSWERED_SEARCH_IDS = 20;

/**
 * The Gmail search for the threads holding the messages an encrypted reply answers, so Done and
 * Snooze from its notification reach the whole row even when those threads aren't loaded: the
 * first {@link MAX_ANSWERED_SEARCH_IDS} of a summary's `answers` ({@link JoinThread.answers}) as
 * `rfc822msgid:` terms; null for none.
 */
export function answeredSearch(answers: readonly string[]): string | null {
  const terms = answers
    .slice(0, MAX_ANSWERED_SEARCH_IDS)
    .map((id) => `rfc822msgid:${id.replace(/^<|>$/g, "")}`);
  return terms.length === 0 ? null : terms.join(" OR ");
}

/** What joining reads of one loaded Gmail thread. */
export type JoinThread = {
  /** Gmail thread id */
  id: string;
  /** whether its first message carries the hidden subject encrypted mail goes out with */
  hiddenSubject: boolean;
  /** the `Message-ID`s of its messages */
  messageIds: readonly string[];
  /** the ids its messages' `In-Reply-To` and `References` name; read only when `hiddenSubject` */
  answers: readonly string[];
  /** its first message's date, epoch ms */
  firstDate: number;
};

/**
 * The loaded threads that show as one row, each group oldest first (its first thread leads and
 * gives the row its id); threads joined to nothing are left out.
 *
 * A thread with the hidden subject joins the loaded thread holding a message it answers, and
 * joins chain, so a reply to a reply lands in the same row. A thread that isn't loaded joins
 * nothing: no extra reads go out to find it.
 */
export function joinThreads(threads: readonly JoinThread[]): string[][] {
  const holder = new Map<string, string>();
  for (const thread of threads) {
    for (const messageId of thread.messageIds) {
      if (!holder.has(messageId)) holder.set(messageId, thread.id);
    }
  }
  const parent = new Map<string, string>();
  const root = (id: string): string => {
    let at = id;
    while (parent.has(at)) at = parent.get(at)!;
    return at;
  };
  for (const thread of threads) {
    if (!thread.hiddenSubject) continue;
    for (const answered of thread.answers) {
      const other = holder.get(answered);
      if (other === undefined || other === thread.id) continue;
      const from = root(thread.id);
      const to = root(other);
      if (from !== to) parent.set(from, to);
    }
  }
  const order = new Map(threads.map((thread, index) => [thread.id, index]));
  const byId = new Map(threads.map((thread) => [thread.id, thread]));
  const groups = new Map<string, string[]>();
  for (const thread of threads) {
    const key = root(thread.id);
    const group = groups.get(key) ?? [];
    if (!group.includes(thread.id)) group.push(thread.id);
    groups.set(key, group);
  }
  const joined = [...groups.values()].filter((group) => group.length > 1);
  for (const group of joined) {
    group.sort(
      (left, right) =>
        byId.get(left)!.firstDate - byId.get(right)!.firstDate ||
        order.get(left)! - order.get(right)!,
    );
  }
  return joined.sort(
    (left, right) => order.get(left[0]!)! - order.get(right[0]!)!,
  );
}

/** A sender as a row shows it. */
export type JoinPerson = { name: string; email: string };

/** The fields of a thread's summary that a joined row combines. */
export type JoinSummary = {
  /** Gmail thread id */
  id: string;
  /** its latest Gmail history id */
  historyId: string;
  /** its subject */
  subject: string;
  /** its latest message's snippet */
  snippet: string;
  /** who wrote in it, in order */
  senders: readonly JoinPerson[];
  /** how many messages it has */
  messageCount: number;
  /** its latest received message's date, epoch ms */
  lastDate: number;
  /** its latest sent message's date, epoch ms */
  sentDate: number;
  /** its labels, sorted */
  labelIds: readonly string[];
  /** whether any of its messages is unread */
  unread: boolean;
};

/**
 * One row's summary for a joined group, `members` lead first: the lead's id and subject, the
 * newest member's snippet, senders in order without repeats, the counts added up, the latest dates
 * and history id, every label, and unread when any member is.
 */
export function joinSummaries<Summary extends JoinSummary>(
  members: readonly Summary[],
): Summary {
  const lead = members[0]!;
  const newest = members.reduce((best, member) =>
    member.lastDate > best.lastDate ? member : best,
  );
  const senders: JoinPerson[] = [];
  for (const member of members) {
    for (const person of member.senders) {
      const known = senders.find((entry) => entry.email === person.email);
      if (known === undefined) senders.push({ ...person });
      else if (known.name === "" && person.name !== "")
        known.name = person.name;
    }
  }
  return {
    ...lead,
    historyId: members.reduce(
      (best, member) =>
        compareHistoryIds(member.historyId, best) > 0 ? member.historyId : best,
      lead.historyId,
    ),
    snippet: newest.snippet,
    senders,
    messageCount: members.reduce((sum, member) => sum + member.messageCount, 0),
    lastDate: Math.max(...members.map((member) => member.lastDate)),
    sentDate: Math.max(...members.map((member) => member.sentDate)),
    labelIds: [...new Set(members.flatMap((member) => member.labelIds))].sort(),
    unread: members.some((member) => member.unread),
  };
}

/** Where one thread stands with its snooze (see the web's `snoozeStatus`). */
export type JoinSnoozeStatus = "none" | "snoozed" | "lapsed" | "woken";

/** What a joined row's snooze reads of one member. */
export type JoinSnoozeMember = {
  /** its own snooze status */
  status: JoinSnoozeStatus;
  /** its snooze's end, epoch ms, or null */
  snoozeUntil: number | null;
  /** its Gmail history id when snoozed, or null */
  watermark: string | null;
  /** its latest Gmail history id */
  historyId: string;
  /** whether it is in the inbox */
  inInbox: boolean;
};

/**
 * A joined row's snooze. Snoozing the row snoozes each member, and any new reply ends it: a member
 * woken by a new message, or a member with no snooze (a reply Gmail split off after the snooze)
 * that is in the inbox with history newer than the snooze. Otherwise the row is snoozed while any
 * member is, lapsed once they all have, and not snoozed when none ever was.
 */
export function joinedSnooze(members: readonly JoinSnoozeMember[]): {
  status: JoinSnoozeStatus;
  snoozeUntil: number | null;
} {
  const snoozed = members.filter((member) => member.status !== "none");
  if (snoozed.length === 0) return { status: "none", snoozeUntil: null };
  const until = Math.max(...snoozed.map((member) => member.snoozeUntil ?? 0));
  const watermarks = snoozed
    .map((member) => member.watermark)
    .filter((watermark): watermark is string => watermark !== null);
  const newerReply = members.some(
    (member) =>
      member.status === "none" &&
      member.inInbox &&
      watermarks.some(
        (watermark) => compareHistoryIds(member.historyId, watermark) > 0,
      ),
  );
  if (newerReply || snoozed.some((member) => member.status === "woken")) {
    return { status: "woken", snoozeUntil: until };
  }
  if (snoozed.some((member) => member.status === "snoozed")) {
    return { status: "snoozed", snoozeUntil: until };
  }
  return { status: "lapsed", snoozeUntil: until };
}
