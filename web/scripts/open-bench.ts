// Time opening a saved thread in headless Chrome against the running dev server:
// `bun scripts/open-bench.ts [http://localhost:5173]`. Prints each phase in ms.

import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { BenchResult } from "./open-bench-page";

const CHROME =
  process.env.CHROME ??
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const origin = process.argv[2] ?? "http://localhost:5173";

type CdpReply = {
  id?: number;
  result?: { result?: { value?: unknown }; exceptionDetails?: unknown };
  error?: unknown;
};

async function devtoolsPort(profile: string): Promise<number> {
  const until = Date.now() + 15_000;
  while (Date.now() < until) {
    try {
      const [port] = readFileSync(
        join(profile, "DevToolsActivePort"),
        "utf8",
      ).split("\n");
      if (port) return Number(port);
    } catch {
      // Not written yet.
    }
    await Bun.sleep(100);
  }
  throw new Error("Chrome didn't start");
}

async function main(): Promise<void> {
  const profile = mkdtempSync(join(tmpdir(), "open-bench-"));
  const chrome = Bun.spawn(
    [
      CHROME,
      "--headless=new",
      "--remote-debugging-port=0",
      `--user-data-dir=${profile}`,
      "--no-first-run",
      "--window-size=1280,900",
      // A JavaScript module of the dev server, so the page has its origin without booting the app.
      `${origin}/src/lib/open-timing.ts`,
    ],
    { stdout: "ignore", stderr: "ignore" },
  );
  try {
    const port = await devtoolsPort(profile);
    const targets = (await (
      await fetch(`http://127.0.0.1:${port}/json/list`)
    ).json()) as { type: string; webSocketDebuggerUrl: string }[];
    const page = targets.find((target) => target.type === "page");
    if (page === undefined) throw new Error("no page");
    const socket = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve) => socket.addEventListener("open", resolve));
    const reply = await new Promise<CdpReply>((resolve) => {
      socket.addEventListener("message", (event) => {
        const message = JSON.parse(String(event.data)) as CdpReply;
        if (message.id === 1) resolve(message);
      });
      socket.send(
        JSON.stringify({
          id: 1,
          method: "Runtime.evaluate",
          params: {
            expression:
              'import("/scripts/open-bench-page.ts").then((bench) => bench.runOpenBench())',
            awaitPromise: true,
            returnByValue: true,
          },
        }),
      );
    });
    socket.close();
    if (reply.result?.exceptionDetails !== undefined || reply.error) {
      throw new Error(
        JSON.stringify(reply.result?.exceptionDetails ?? reply.error),
      );
    }
    const results = reply.result?.result?.value as BenchResult[];
    for (const { name, phases } of results) {
      const line = Object.entries(phases)
        .map(([phase, took]) => `${phase} ${took.toFixed(1)}`)
        .join(" · ");
      console.log(`${name}: ${line}`);
    }
  } finally {
    chrome.kill();
    await chrome.exited;
    rmSync(profile, { recursive: true, force: true });
  }
}

await main();
