// The open path of a saved thread, run in a real browser by `open-bench.ts` through the dev
// server: a large designed email and a long reply thread are stored in IndexedDB among filler
// threads, read back, cleaned, and drawn in a message frame, each phase timed.

import { splitQuotedHtml } from "@inbox/shared/compose";
import { mount, unmount } from "svelte";
import { Idb } from "../src/lib/idb";
import { DEBUG_TIMING_KEY, openStarted } from "../src/lib/open-timing";
import { cleanMessageHtml } from "../src/lib/sanitize";
import type { MessageView, ThreadDetail } from "../src/lib/thread-detail";
import OpenBenchFrame from "./open-bench-frame.svelte";

/** One timed open: each phase's length in ms. */
export type BenchResult = { name: string; phases: Record<string, number> };

// Filler threads around the two measured ones, so the store has a realistic size.
const FILLER_THREADS = 800;
// Messages in the long reply thread, each quoting all before it.
const THREAD_LENGTH = 40;

/** A newsletter laid out in nested tables with a style sheet, inline styles and remote images. */
export function designedEmail(sections = 40): string {
  const rules: string[] = [];
  for (let index = 0; index < 120; index++) {
    rules.push(
      `.c${index}{color:#${(0x203040 + index * 997).toString(16).slice(-6)};background-color:#f${(index % 10).toString()}f${(index % 10).toString()}f${(index % 10).toString()};padding:${index % 12}px}`,
    );
  }
  rules.push(
    "@media only screen and (max-width:600px){.wrap{width:100%!important}.col{display:block!important;width:100%!important}}",
    "a{color:#1a73e8;text-decoration:none}td.hero h1{font-size:28px;line-height:34px;color:#ffffff}",
  );
  const body: string[] = [];
  for (let section = 0; section < sections; section++) {
    const cells: string[] = [];
    for (let column = 0; column < 2; column++) {
      cells.push(
        `<td class="col c${(section * 2 + column) % 120}" width="290" valign="top" style="padding:12px;font-family:Helvetica,Arial,sans-serif;font-size:15px;line-height:22px;color:#333333;background-color:#ffffff"><a href="https://click.example.com/track?u=${section}-${column}&amp;r=https%3A%2F%2Fshop.example.com%2Fitem%2F${section}"><img src="https://img.example.com/p/${section}-${column}.jpg" width="266" height="180" alt="Item ${section}" style="display:block;border:0;width:266px;height:180px"></a><h3 style="margin:12px 0 4px;font-size:18px;color:#111111">Item ${section}-${column}</h3><p style="margin:0 0 8px">Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.</p><table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#e8453c" style="border-radius:4px;padding:10px 18px"><a href="https://click.example.com/btn/${section}" style="color:#ffffff;font-weight:bold">Shop now</a></td></tr></table></td>`,
      );
    }
    body.push(
      `<tr><td><table role="presentation" class="wrap" width="600" cellpadding="0" cellspacing="0" style="width:600px;background-color:#fafafa"><tr>${cells.join("")}</tr></table></td></tr>`,
    );
  }
  return `<html><head><meta name="color-scheme" content="light"><style>${rules.join("")}</style></head><body style="margin:0;background-color:#eeeeee"><center><table role="presentation" width="100%" bgcolor="#eeeeee" cellpadding="0" cellspacing="0"><tr><td class="hero" align="center" bgcolor="#1a1a2e" style="padding:32px"><h1>Big autumn sale</h1></td></tr>${body.join("")}<tr><td style="padding:24px;font-size:12px;color:#888888">You are receiving this because you signed up. <a href="https://click.example.com/unsub">Unsubscribe</a></td></tr></table></center></body></html>`;
}

/** A conversation of `length` replies, each quoting the whole thread before it as Gmail does. */
export function replyChain(length = THREAD_LENGTH): string[] {
  const bodies: string[] = [];
  let previous = "";
  for (let index = 0; index < length; index++) {
    const own = `<div dir="ltr"><div>Reply ${index}: thanks, that works for me. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco.</div><div><br></div><div>Best,<br>Person ${index % 3}</div></div>`;
    const html =
      previous === ""
        ? own
        : `${own}<br><div class="gmail_quote"><div dir="ltr" class="gmail_attr">On Mon, Nov 16, 2026 at 10:${String(index % 60).padStart(2, "0")} Person ${(index + 1) % 3} &lt;person${(index + 1) % 3}@example.com&gt; wrote:<br></div><blockquote class="gmail_quote" style="margin:0px 0px 0px 0.8ex;border-left:1px solid rgb(204,204,204);padding-left:1ex">${previous}</blockquote></div>`;
    bodies.push(html);
    previous = html;
  }
  return bodies;
}

function messageOf(threadId: string, index: number, html: string): MessageView {
  const person = {
    name: `Person ${index % 3}`,
    email: `person${index % 3}@example.com`,
  };
  return {
    id: `${threadId}-m${index}`,
    threadId,
    from: person,
    to: [{ name: "Me", email: "me@example.com" }],
    date: Date.UTC(2026, 10, 16, 10, index),
    snippet: "Reply snippet",
    unread: false,
    body: {
      html,
      text: html.replace(/<[^>]+>/g, " "),
      attachments: [],
      inline: [],
      calendar: null,
    },
  };
}

function threadOf(id: string, bodies: readonly string[]): ThreadDetail {
  return {
    id,
    historyId: "1",
    subject: `Thread ${id}`,
    messages: bodies.map((html, index) => messageOf(id, index, html)),
    drafts: [],
  };
}

function nextFrame(): Promise<void> {
  return new Promise((resolve) =>
    requestAnimationFrame(() => setTimeout(resolve, 0)),
  );
}

// The marks `open-timing` left for the open labeled `label`, in ms since its start.
function marksOf(label: string): Record<string, number> {
  const marks = performance.getEntriesByType("mark");
  const start = marks.find((mark) => mark.name === `${label} start`);
  const phases: Record<string, number> = {};
  if (start === undefined) return phases;
  for (const mark of marks) {
    if (mark.name.startsWith(`${label} `) && mark !== start) {
      phases[mark.name.slice(label.length + 1)] =
        mark.startTime - start.startTime;
    }
  }
  return phases;
}

async function waitForMark(label: string, phase: string): Promise<void> {
  const until = performance.now() + 10_000;
  while (marksOf(label)[phase] === undefined && performance.now() < until) {
    await nextFrame();
  }
}

// One open of a stored thread: read, clean and draw its last message, as an open thread does.
async function benchOpen(
  cache: Idb,
  name: string,
  threadId: string,
  dark: boolean,
): Promise<BenchResult> {
  localStorage.setItem("inbox:theme", dark ? "dark" : "light");
  const label = `open ${threadId.slice(-6)}`;
  performance.clearMarks();
  openStarted(threadId);
  const started = performance.now();
  const [stored] = await cache.getMany("threads", [threadId]);
  const read = performance.now();
  const last = stored!.messages.at(-1)!;
  const split = splitQuotedHtml(last.body.html!);
  const splitDone = performance.now();
  const clean = cleanMessageHtml(split.body, new Map());
  const cleaned = performance.now();
  const target = document.createElement("div");
  target.style.width = "900px";
  document.body.append(target);
  const frame = mount(OpenBenchFrame, {
    target,
    props: { html: clean.html, colorSchemes: clean.colorSchemes, dark },
  });
  const mounted = performance.now();
  await waitForMark(label, "frame fitted and sized");
  await nextFrame();
  const drawn = performance.now();
  const marks = marksOf(label);
  unmount(frame);
  target.remove();
  return {
    name,
    phases: {
      "IndexedDB read": read - started,
      "quote split": splitDone - read,
      sanitize: cleaned - splitDone,
      "mount (darken, srcdoc)": mounted - cleaned,
      "frame parsed":
        (marks["frame parsed"] ?? Number.NaN) - (mounted - started),
      "frame fit and size":
        (marks["frame fitted and sized"] ?? Number.NaN) -
        (marks["frame parsed"] ?? Number.NaN),
      total: drawn - started,
    },
  };
}

/** Store the bench threads, then time opening each, cold and again. */
export async function runOpenBench(): Promise<BenchResult[]> {
  localStorage.setItem(DEBUG_TIMING_KEY, "1");
  const filler = threadOf("filler", replyChain(8));
  const designed = threadOf("bench-designed", [designedEmail()]);
  const chain = threadOf("bench-chain", replyChain());
  let cache = new Idb("open-bench");
  await cache.putMany(
    "threads",
    Array.from({ length: FILLER_THREADS }, (_, index) => [
      `filler-${index}`,
      { ...filler, id: `filler-${index}` },
    ]),
  );
  await cache.put("threads", designed.id, designed);
  await cache.put("threads", chain.id, chain);
  await cache.close();
  cache = new Idb("open-bench");
  const results: BenchResult[] = [];
  for (const dark of [false, true]) {
    for (const [thread, kind] of [
      [designed, "designed email"],
      [chain, `${THREAD_LENGTH}-message thread`],
    ] as const) {
      const shade = dark ? "dark" : "light";
      results.push(
        await benchOpen(cache, `${kind}, ${shade}, first`, thread.id, dark),
      );
      results.push(
        await benchOpen(cache, `${kind}, ${shade}, again`, thread.id, dark),
      );
    }
  }
  results.push({
    name: "stored sizes (KB)",
    phases: {
      "designed email": JSON.stringify(designed).length / 1024,
      [`${THREAD_LENGTH}-message thread`]: JSON.stringify(chain).length / 1024,
    },
  });
  await cache.close();
  return results;
}
