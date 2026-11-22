/**
 * Run a type checker (tsc, or svelte-check with `--output machine`) and drop its diagnostics in
 * the files copied from latr, which are kept byte-for-byte in step with latr and so can't take
 * `noUncheckedIndexedAccess` fixes. Every other diagnostic is printed and fails the run.
 *
 * Usage: `bun ../scripts/typecheck.ts tsc --noEmit -p .`
 */
import { resolve } from "node:path";

const EXEMPT = new Set(
  ["snooze-suggest.ts", "snooze-suggest.spec.ts"].map((name) =>
    resolve(import.meta.dir, "../shared/src", name),
  ),
);

// tsc --pretty false: `path(line,col): error TS…`
const TSC_HEAD = /^(?<path>[^\s(][^(]*)\(\d+,\d+\): (?:error|warning)/;
// svelte-check --output machine: `<time> ERROR "path" line:col "message"`
const SVELTE_HEAD = /^\d+ (?:ERROR|WARNING) "(?<path>[^"]+)"/;
const SVELTE_SUMMARY = /^\d+ (?:START|COMPLETED)/;

const [command, ...args] = Bun.argv.slice(2);
if (command === undefined) {
  console.error("usage: typecheck.ts <command> [args...]");
  process.exit(2);
}
const isTsc = command === "tsc";
const child = Bun.spawn(
  [command, ...args, ...(isTsc ? ["--pretty", "false"] : [])],
  {
    stdout: "pipe",
    stderr: "inherit",
  },
);
const output = await new Response(child.stdout).text();
const exitCode = await child.exited;

let kept = 0;
let dropped = 0;
let dropping = false;
const lines: string[] = [];
for (const line of output.split("\n")) {
  const head = TSC_HEAD.exec(line) ?? SVELTE_HEAD.exec(line);
  const path = head?.groups?.path;
  if (path !== undefined) {
    dropping = EXEMPT.has(resolve(path));
    if (dropping) {
      dropped += 1;
    } else {
      kept += 1;
    }
  } else if (SVELTE_SUMMARY.test(line)) {
    continue;
  } else if (!/^\s/.test(line)) {
    dropping = false;
  }
  if (!dropping && line !== "") lines.push(line);
}
for (const line of lines) console.log(line);
if (dropped > 0) {
  console.log(
    `${kept} problems (${dropped} more in files copied from latr ignored)`,
  );
}
// a failure that printed no diagnostics (a crash, a bad flag) still fails
process.exit(kept > 0 || (exitCode !== 0 && dropped === 0) ? 1 : 0);
