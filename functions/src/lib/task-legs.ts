import { createHash } from "node:crypto";

/**
 * Longest delay given to one task. Cloud Tasks refuses schedule times more than 30 days out, so a
 * task due later runs as a chain of legs, each re-checking its doc.
 */
export const MAX_TASK_DELAY_MS = 29 * 24 * 60 * 60 * 1000;

/** When leg `hop` (from 1) of a task due at `dueAt`, scheduled at `writtenAt`, fires. */
export function legTime(dueAt: number, writtenAt: number, hop: number): number {
  return Math.min(dueAt, writtenAt + hop * MAX_TASK_DELAY_MS);
}

/** The legs (from 1) of a task due at `dueAt`, scheduled at `writtenAt`, that may be queued at `now`. */
export function pendingHops(
  dueAt: number,
  writtenAt: number,
  now: number,
): number[] {
  const last = Math.max(1, Math.ceil((dueAt - writtenAt) / MAX_TASK_DELAY_MS));
  const current = Math.min(
    last,
    Math.max(1, Math.ceil((now - writtenAt) / MAX_TASK_DELAY_MS)),
  );
  const hops: number[] = [];
  for (
    let hop = Math.max(1, current - 1);
    hop <= Math.min(last, current + 1);
    hop++
  ) {
    hops.push(hop);
  }
  return hops;
}

/** A stable Cloud Tasks id from a task's identifying parts. */
export function taskIdOf(parts: readonly unknown[]): string {
  return createHash("sha256")
    .update(JSON.stringify(parts))
    .digest("hex")
    .slice(0, 40);
}

/** Whether a value is a positive integer leg number. */
export function isHop(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1;
}
