// Writes out/sw.js after `vite build`: scripts/sw.ts bundled with what it imports, plus the list of built files, and a version that changes whenever any of them does.
import { createHash } from "node:crypto";
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join, relative } from "node:path";

const OUT = "out";

const bundle = await Bun.build({
  entrypoints: [join(import.meta.dir, "sw.ts")],
  target: "browser",
  format: "iife",
  minify: true,
});
if (!bundle.success || !bundle.outputs[0]) {
  throw new AggregateError(bundle.logs, "service worker bundle failed");
}
const worker = await bundle.outputs[0].text();
const entries = await readdir(OUT, { recursive: true, withFileTypes: true });
const files = entries
  .filter((entry) => entry.isFile())
  .map((entry) => relative(OUT, join(entry.parentPath, entry.name)))
  // One missing file fails the whole install, so skip what Pages may not serve.
  .filter(
    (file) =>
      file !== "sw.js" &&
      !file.endsWith(".txt") &&
      !file.startsWith(".") &&
      !file.startsWith("404"),
  )
  .sort();

const hash = createHash("sha256").update(worker);
for (const file of files) {
  hash.update(file).update(await readFile(join(OUT, file)));
}
const version = hash.digest("hex").slice(0, 16);
// Pages are cached under their directory URL, which is what navigations look up.
const precache = files.map((file) =>
  file === "index.html"
    ? "./"
    : file.endsWith("/index.html")
      ? file.slice(0, -"index.html".length)
      : file,
);

await writeFile(
  join(OUT, "sw.js"),
  `self.__VERSION = ${JSON.stringify(version)};\nself.__FILES = ${JSON.stringify(precache)};\n${worker}`,
);
console.log(`sw.js ${version}: ${precache.length} files`);
