import { asset, resolve } from "$app/paths";

/** Prerender the manifest into the static export. */
export const prerender = true;

/** Serves the web app manifest. */
export function GET(): Response {
  return Response.json({
    name: "Inbox",
    short_name: "Inbox",
    description: "Your Gmail, with snooze, pins and an @hafa.cc address.",
    id: resolve("/"),
    start_url: resolve("/"),
    scope: resolve("/"),
    display: "standalone",
    background_color: "#f1eee8",
    theme_color: "#f1eee8",
    icons: [
      { src: asset("icon.svg"), sizes: "any", type: "image/svg+xml" },
      { src: asset("icon-192.png"), sizes: "192x192", type: "image/png" },
      { src: asset("icon-512.png"), sizes: "512x512", type: "image/png" },
      {
        src: asset("icon-maskable-512.png"),
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  });
}
