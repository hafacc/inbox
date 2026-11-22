<!-- @component A message frame as an open thread draws it, under its own theme, for `open-bench`. -->
<script lang="ts">
  import { untrack } from "svelte";
  import HtmlFrame from "../src/lib/components/html-frame.svelte";
  import { setThemeState, ThemeState } from "../src/lib/theme.svelte";

  let {
    html,
    colorSchemes,
    dark,
  }: {
    /** cleaned message HTML */
    html: string;
    /** the mail's `color-scheme` meta contents */
    colorSchemes: readonly string[];
    /** whether the theme is dark */
    dark: boolean;
  } = $props();

  // The bench saves the same mode where the theme reads it, so loading it changes nothing.
  const theme = new ThemeState();
  theme.mode = untrack(() => (dark ? "dark" : "light"));
  setThemeState(theme);
</script>

<HtmlFrame {html} {colorSchemes} title="Bench message" />
