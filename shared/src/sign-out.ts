function counted(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? "" : "s"}`;
}

/**
 * What signing out would lose, for the question asked first: changes (to threads' labels, and to
 * labels and filters) and drafts (saves, sends and deletes) on this device that haven't reached Gmail. Null when nothing is waiting, so sign-out goes ahead at once.
 */
export function signOutWarning(changes: number, drafts: number): string | null {
  const parts: string[] = [];
  if (changes > 0) parts.push(counted(changes, "change"));
  if (drafts > 0) parts.push(counted(drafts, "draft"));
  if (parts.length === 0) return null;
  else {
    const single = parts.length === 1 && changes + drafts === 1;
    return `${parts.join(" and ")} ${single ? "hasn't" : "haven't"} synced yet. Signing out deletes ${single ? "it" : "them"} from this device.`;
  }
}
