/** The shared OpenPGP module. */
export type PgpModule = typeof import("@inbox/shared/pgp");

let pending: Promise<PgpModule> | null = null;

/** Load the OpenPGP code on first use; openpgp.js is too large for the first page load. */
export function loadPgp(): Promise<PgpModule> {
  pending ??= import("@inbox/shared/pgp");
  return pending;
}
