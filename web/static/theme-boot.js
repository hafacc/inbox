// Runs before the app loads: puts on the light/dark mode and the account's color theme saved by
// src/lib/theme.svelte.ts, so the first paint isn't app.css's green and then a jump.
try {
  const mode = localStorage.getItem("inbox:theme");
  const dark =
    mode === "dark" ||
    (mode !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
  const root = document.documentElement;
  root.classList.toggle("dark", dark);
  const cached = JSON.parse(localStorage.getItem("inbox:theme-colors") ?? "null");
  const colors = cached?.[dark ? "dark" : "light"];
  if (colors) {
    for (const [name, value] of Object.entries(colors)) {
      root.style.setProperty(`--color-${name}`, value);
    }
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) {
      meta.setAttribute("content", colors.bg);
    }
  }
} catch {
  // Storage blocked: app.css's colors stand.
}
