/** One way to answer an open thread from under its quick reply. */
export type AnswerButton = "reply" | "replyAll" | "forward";

/**
 * The answer buttons under a thread's quick reply, in order, given how its field answers
 * (`mode`). Reply all shows only when it reaches more than one other person. At phone width there
 * is no field, so Reply shows too; wider, the field is already the answer `mode` names, so only
 * the other one shows, and none when that would be Reply all.
 */
export function answerButtons(
  mode: "reply" | "replyAll",
  phone: boolean,
): AnswerButton[] {
  if (phone) {
    return mode === "replyAll"
      ? ["reply", "replyAll", "forward"]
      : ["reply", "forward"];
  } else return mode === "replyAll" ? ["reply", "forward"] : ["forward"];
}
