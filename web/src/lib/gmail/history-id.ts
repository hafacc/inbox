// A Gmail history id is a decimal count, larger as the mailbox changes.
function historyValue(historyId: string): bigint | null {
  return /^\d+$/.test(historyId) ? BigInt(historyId) : null;
}

/**
 * Whether history id `candidate` is past `current`. A readable id is past an empty or unreadable
 * one; an unreadable one is past nothing.
 */
export function isLaterHistory(candidate: string, current: string): boolean {
  const candidateValue = historyValue(candidate);
  if (candidateValue === null) return false;
  const currentValue = historyValue(current);
  return currentValue === null || candidateValue > currentValue;
}
