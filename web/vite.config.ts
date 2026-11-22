import adapter from "@sveltejs/adapter-static";
import { sveltekit } from "@sveltejs/kit/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";
import { openTimingRelay } from "./open-timing-relay.ts";

// Local Firebase emulators (auth, firestore, functions), reached only by the dev server on localhost;
// a built app never allows them.
const EMULATORS = [
  "http://localhost:9099",
  "http://localhost:8080",
  "ws://localhost:8080",
  "http://localhost:5001",
] as const;

export default defineConfig(({ command }) => ({
  // The chunk of Firebase (Auth, Firestore) and the signed-in app is past Vite's default 500 kB.
  build: { chunkSizeWarningLimit: 800 },
  // The shared package and docs (the privacy page) live beside web/ in the workspace.
  server: { fs: { allow: [".", "../shared", "../docs"] } },
  plugins: [
    tailwindcss(),
    openTimingRelay(),
    sveltekit({
      adapter: adapter({ pages: "out", fallback: "404.html" }),
      // Served by GitHub Pages at inbox.hafa.cc, the domain root; set BASE_PATH to serve from a subpath.
      // Absolute asset paths, since the service worker serves the app page at paths it has no page for.
      paths: {
        base: (process.env.BASE_PATH ?? "") as "" | `/${string}`,
        relative: false,
      },
      // Prerendered pages get this as a meta tag (GitHub Pages can't send headers), with a hash for the inline start script.
      csp: {
        mode: "hash",
        directives: {
          "default-src": ["self"],
          // hash-wasm's Argon2id (the recovery phrase) runs as WebAssembly.
          "script-src": [
            "self",
            "wasm-unsafe-eval",
            "https://accounts.google.com/gsi/client",
          ],
          "style-src": [
            "self",
            "unsafe-inline",
            "https://accounts.google.com/gsi/style",
          ],
          // Message frames (srcdoc) inherit this; each frame's own policy keeps remote mail images
          // out until the reader shows them, then they load straight from the sender.
          "img-src": ["self", "data:", "blob:", "https:", "http:"],
          "connect-src": [
            "self",
            "https://*.googleapis.com",
            "https://*.cloudfunctions.net",
            "https://accounts.google.com/gsi/",
            // Recipients' OpenPGP keys: WKD through the Key Worker, and keys.openpgp.org.
            "https://openpgpkey.hafa.cc",
            "https://keys.openpgp.org",
            ...(command === "serve" ? EMULATORS : []),
          ],
          // Gmail message bodies render in sandboxed srcdoc frames, which inherit this page's origin.
          "frame-src": ["self", "https://accounts.google.com/gsi/"],
          "object-src": ["none"],
          "base-uri": ["self"],
          "form-action": ["self"],
        },
      },
      version: { pollInterval: 0 },
    }),
  ],
}));
