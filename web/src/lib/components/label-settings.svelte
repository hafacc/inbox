<!-- @component One label's settings: its name, color and icon (user labels only; Gmail's categories keep theirs), shown in the sidebar, and bundled in the inbox. Used in a popover from the bundle, the label view and the Labels settings page. -->
<script lang="ts">
  import {
    labelNameError,
    leafName,
    nestedName,
    parentName,
  } from "@inbox/shared/label-names";
  import { isCategory, LABEL_ICONS, NO_ICON } from "@inbox/shared/label-prefs";
  import { labelTone } from "@inbox/shared/themes";
  import Ban from "@lucide/svelte/icons/ban";
  import Check from "@lucide/svelte/icons/check";
  import type { GmailLabel } from "../gmail/types";
  import { LABEL_COLORS, type LabelColor } from "../labels";
  import { getMailState } from "../mail-state.svelte";
  import type { IconType } from "./icons";
  import { LABEL_ICON_COMPONENTS } from "./label-icons";
  import Switch from "./switch.svelte";

  let {
    label,
    showIcons = true,
  }: {
    /** the label */
    label: GmailLabel;
    /** whether the icon picker shows */
    showIcons?: boolean;
  } = $props();

  const mail = getMailState();
  const prefs = $derived(
    mail.labelPrefs.get(label.id) ?? {
      sidebar: true,
      bundle: true,
      icon: null,
    },
  );
  const groupId = $props.id();
  const editable = $derived(!isCategory(label.id));
  const tone = $derived(labelTone(label.color?.backgroundColor));
  let draftName = $state("");
  let error = $state<string | null>(null);

  $effect.pre(() => {
    draftName = leafName(label.name);
  });

  function rename() {
    const newName = nestedName(parentName(label.name), draftName);
    if (newName === label.name) return;
    const problem = labelNameError(mail.labels, newName, label.id);
    if (problem) {
      error = problem;
      return;
    }
    error = null;
    mail.renameLabel(label.id, newName);
  }

  function recolor(color: LabelColor) {
    mail.patchLabels([
      {
        id: label.id,
        patch: {
          color: {
            backgroundColor: color.backgroundColor,
            textColor: color.textColor,
          },
        },
      },
    ]);
  }

  function set(patch: { sidebar?: boolean; bundle?: boolean; icon?: string }) {
    void mail
      .setLabelPrefs(label.id, patch)
      .catch((error) => console.error("label settings save failed", error));
  }
</script>

{#snippet choice(id: string | null, name: string, Icon: IconType | undefined)}
  <label
    title={name}
    class={[
      "w-8 h-8 rounded-full flex items-center justify-center cursor-pointer transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-accent",
      prefs.icon === id
        ? "bg-accent text-on-accent"
        : "text-text-secondary hover:bg-surface-hover hover:text-text",
    ]}
  >
    <input
      type="radio"
      name={`${groupId}-icon`}
      class="sr-only"
      aria-label={name}
      checked={prefs.icon === id}
      onchange={() => set({ icon: id ?? NO_ICON })}
    >
    {#if Icon}
      <Icon class="w-4 h-4" aria-hidden="true" />
    {/if}
  </label>
{/snippet}

<div class="flex flex-col gap-1">
  {#if editable}
    <form
      class="flex items-center gap-2 px-1 pb-2"
      onsubmit={(event) => {
        event.preventDefault();
        rename();
      }}
    >
      <input
        aria-label="Label name"
        bind:value={draftName}
        onblur={rename}
        class="h-9 min-w-0 flex-1 px-3 rounded-xl border border-border-strong bg-field text-sm outline-none focus:border-focus focus:ring-1 focus:ring-focus"
      >
    </form>
    <fieldset aria-label="Color" class="flex flex-wrap gap-1.5 px-1 pb-2">
      {#each LABEL_COLORS as color (color.tone)}
        <button
          type="button"
          aria-pressed={tone === color.tone}
          aria-label={`Color ${color.tone}`}
          onclick={() => recolor(color)}
          class="w-7 h-7 rounded-full flex items-center justify-center text-surface ring-offset-2 ring-offset-surface-raised aria-pressed:ring-2 aria-pressed:ring-focus"
          style:background-color={`var(--color-label-${color.tone})`}
        >
          {#if tone === color.tone}
            <Check class="w-3.5 h-3.5" aria-hidden="true" />
          {/if}
        </button>
      {/each}
    </fieldset>
    {#if error}
      <p role="alert" class="px-1 pb-1 text-xs text-danger">{error}</p>
    {/if}
  {/if}
  <Switch
    label="Show in sidebar"
    checked={prefs.sidebar}
    onchange={(checked) => set({ sidebar: checked })}
  />
  <Switch
    label="Bundle in inbox"
    hint="Three or more threads fold into one row, listed with the newest."
    checked={prefs.bundle}
    onchange={(checked) => set({ bundle: checked })}
  />
  {#if showIcons && editable}
    <div
      id={`${groupId}-icons`}
      class="mt-2 px-1 text-[13px] font-semibold text-text-secondary"
    >
      Icon
    </div>
    <div
      role="radiogroup"
      aria-labelledby={`${groupId}-icons`}
      class="mt-1.5 grid grid-cols-8 gap-1"
    >
      {@render choice(null, "No icon", Ban)}
      {#each LABEL_ICONS as icon (icon.id)}
        {@render choice(icon.id, icon.name, LABEL_ICON_COMPONENTS[icon.id])}
      {/each}
    </div>
  {/if}
</div>
