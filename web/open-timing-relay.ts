// The dev server's end of open timing (src/lib/open-timing.ts): the app posts each finished
// open's line here, and this prints it to the dev server's log. Only the dev server has it.

import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";

// The path the app posts to; RELAY_PATH in src/lib/open-timing.ts.
const RELAY_PATH = "/__open-timing";
// A post larger than this is dropped.
const MAX_BODY_BYTES = 64 * 1024;

/** Lines of a relayed post, cut to printable text; none when it isn't a relay post. */
export function relayedLines(body: string): string[] {
  try {
    const parsed = JSON.parse(body) as { lines?: unknown };
    return Array.isArray(parsed.lines)
      ? parsed.lines
          .filter((line): line is string => typeof line === "string")
          .map((line) => line.replace(/\p{Cc}/gu, " "))
      : [];
  } catch {
    return [];
  }
}

/** Vite plugin that prints relayed open timing lines in the dev server's log. */
export function openTimingRelay(): Plugin {
  return {
    name: "inbox-open-timing-relay",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use(RELAY_PATH, (request, response) => {
        if (request.method !== "POST") {
          response.statusCode = 405;
          response.end();
        } else {
          readLines(request, response, (line) =>
            server.config.logger.info(`[open-timing] ${line}`, {
              timestamp: true,
            }),
          );
        }
      });
    },
  };
}

function readLines(
  request: IncomingMessage,
  response: ServerResponse,
  print: (line: string) => void,
): void {
  const chunks: Buffer[] = [];
  let size = 0;
  request.on("data", (chunk: Buffer) => {
    size += chunk.length;
    if (size <= MAX_BODY_BYTES) chunks.push(chunk);
  });
  request.on("end", () => {
    if (size <= MAX_BODY_BYTES) {
      for (const line of relayedLines(Buffer.concat(chunks).toString("utf8"))) {
        print(line);
      }
    }
    response.statusCode = 204;
    response.end();
  });
}
