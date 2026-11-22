<!-- @component The account's color theme, as a row of swatches; saved to the account so every device follows. -->
<script lang="ts">
  import { THEMES, type ThemeId, themeColors } from "@inbox/shared/themes";
  import Check from "@lucide/svelte/icons/check";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getThemeState } from "../theme.svelte";

  let { onError }: { onError?: (message: string) => void } = $props();

  const hub = getComposeHub();
  const theme = getThemeState();

  function choose(id: ThemeId) {
    theme.setColorTheme(id);
    hub.settings.setColorTheme(id).catch((failure: unknown) => {
      console.error("theme write failed", failure);
      onError?.("Couldn't save the theme. Try again.");
    });
  }
</script>

<fieldset class="flex flex-wrap gap-2 border-0 p-0 m-0">
  <legend class="sr-only">Color theme</legend>
  {#each THEMES as option (option.id)}
    {@const colors = themeColors(option.id, theme.resolved)}
    {@const chosen = theme.colorTheme === option.id}
    <button
      type="button"
      aria-pressed={chosen}
      onclick={() => choose(option.id)}
      class={[
        "h-9 flex items-center gap-2 pl-1.5 pr-3 rounded-full text-[13px] font-medium transition-[background-color,box-shadow,color] duration-150",
        chosen
          ? "bg-surface-muted text-text shadow-pill"
          : "text-text-secondary hover:bg-surface-hover hover:text-text",
      ]}
    >
      <span
        class="w-6 h-6 rounded-full flex items-center justify-center"
        style:background-color={colors.accent}
        style:color={colors["on-accent"]}
        aria-hidden="true"
      >
        {#if chosen}
          <Check class="w-3.5 h-3.5" />
        {/if}
      </span>
      {option.name}
    </button>
  {/each}
</fieldset>
