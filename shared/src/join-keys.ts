// What a thread's summary keeps for joining split encrypted replies (see ./thread-joins), read the
// same way from Gmail's copy and from a saved one. Web and Android (:core ThreadSummary.kt) both
// run shared/testdata/join-key-fixtures.json.

/** The headers and date of one message (draft or not) that joining reads. */
export type JoinKeyMessage = {
  /** its `Message-ID` header, or null */
  messageId: string | null;
  /** its `In-Reply-To` header, or null */
  inReplyTo: string | null;
  /** its `References` header, or null */
  references: string | null;
  /** its date, epoch ms */
  date: number;
};

/** What joining reads of a thread, as its summary keeps it. */
export type JoinKeys = {
  /** each message's `Message-ID`, in order, without repeats */
  messageIds: string[];
  /** the ids its messages' `In-Reply-To` and `References` name, in order, without repeats; empty unless `hiddenSubject` */
  answers: string[];
  /** its earliest message's date, epoch ms; 0 with no messages */
  firstDate: number;
};

// One `<id>` of a `Message-ID`, `In-Reply-To` or `References` header.
const MESSAGE_ID = /<[^<>\s]+>/g;

/** The `<…>` message ids a `Message-ID`, `In-Reply-To` or `References` header names, in order. */
export function messageIdsIn(header: string | null | undefined): string[] {
  return header?.match(MESSAGE_ID) ?? [];
}

/**
 * The join keys of a thread from all its messages, drafts included: each one's `Message-ID` (its
 * first `<…>`), and when the thread has the hidden subject the ids its replies name.
 */
export function joinKeys(
  messages: readonly JoinKeyMessage[],
  hiddenSubject: boolean,
): JoinKeys {
  const messageIds = [
    ...new Set(
      messages.flatMap((message) =>
        messageIdsIn(message.messageId).slice(0, 1),
      ),
    ),
  ];
  const answers = hiddenSubject
    ? [
        ...new Set(
          messages.flatMap((message) => [
            ...messageIdsIn(message.inReplyTo),
            ...messageIdsIn(message.references),
          ]),
        ),
      ]
    : [];
  const firstDate =
    messages.length === 0
      ? 0
      : Math.min(...messages.map((message) => message.date));
  return { messageIds, answers, firstDate };
}
