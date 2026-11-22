<script lang="ts">
  import "@fontsource-variable/geist/wght.css";
  import "@fontsource-variable/geist-mono/wght.css";
  import "../app.css";
  import type { Snippet } from "svelte";
  import { PwaState, setPwaState } from "../lib/pwa.svelte";
  import { setThemeState, ThemeState } from "../lib/theme.svelte";

  let { children }: { children: Snippet } = $props();

  setThemeState(new ThemeState());
  setPwaState(new PwaState());
</script>

{@render children()}
