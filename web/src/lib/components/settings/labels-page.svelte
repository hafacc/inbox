<!-- @component Settings › Labels: which labels show in the sidebar and fold into inbox bundles (and their icons), then the user's Gmail labels as a tree, with new, rename, move (nest) and color. -->
<script lang="ts">
  import {
    labelNameError,
    leafName,
    nestedName,
    parentName,
  } from "@inbox/shared/label-names";
  import { labelTone } from "@inbox/shared/themes";
  import Check from "@lucide/svelte/icons/check";
  import FolderInput from "@lucide/svelte/icons/folder-input";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Plus from "@lucide/svelte/icons/plus";
  import X from "@lucide/svelte/icons/x";
  import type { GmailLabel } from "../../gmail/types";
  import {
    LABEL_COLORS,
    type LabelColor,
    labelDot,
    labelTree,
  } from "../../labels";
  import { getMailState } from "../../mail-state.svelte";
  import LabelMark from "../label-mark.svelte";
  import LabelSettingsButton from "../label-settings-button.svelte";

  const mail = getMailState();
  const toggle =
    "w-10 h-6 shrink-0 appearance-none rounded-full bg-border-strong checked:bg-accent relative cursor-pointer transition-colors duration-200 before:content-[''] before:absolute before:top-0.5 before:left-0.5 before:w-5 before:h-5 before:rounded-full before:bg-surface-raised checked:before:bg-on-accent before:shadow before:transition-transform before:duration-200 checked:before:translate-x-4";

  const tree = $derived(labelTree(mail.labels));
  const userLabels = $derived(tree.map((node) => node.label));

  let error = $state<string | null>(null);
  // Which row is being edited, and how.
  let editing = $state<{
    id: string;
    mode: "rename" | "move" | "color";
  } | null>(null);
  let draftName = $state("");
  let draftParent = $state("");
  let creating = $state(false);
  let newName = $state("");
  let newParent = $state("");
  let newColor = $state.raw<LabelColor | null>(null);
  let newError = $state<string | null>(null);

  /** Labels a label may be nested under: not itself nor anything inside it. */
  function parentChoices(label: GmailLabel | null): GmailLabel[] {
    if (label === null) return userLabels;
    const prefix = `${label.name}/`;
    return userLabels.filter(
      (candidate) =>
        candidate.id !== label.id && !candidate.name.startsWith(prefix),
    );
  }

  function start(label: GmailLabel, mode: "rename" | "move" | "color") {
    error = null;
    editing = { id: label.id, mode };
    draftName = leafName(label.name);
    draftParent = parentName(label.name) ?? "";
  }

  function rename(label: GmailLabel, newFullName: string) {
    const problem = labelNameError(mail.labels, newFullName, label.id);
    if (problem) {
      error = problem;
      return;
    }
    error = null;
    mail.renameLabel(label.id, newFullName);
    editing = null;
  }

  function recolor(label: GmailLabel, color: LabelColor) {
    error = null;
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
    editing = null;
  }

  function create() {
    const name = nestedName(newParent === "" ? null : newParent, newName);
    newError = labelNameError(mail.labels, name);
    if (newError) return;
    mail.createLabel(
      name,
      newColor === null
        ? undefined
        : {
            backgroundColor: newColor.backgroundColor,
            textColor: newColor.textColor,
          },
    );
    creating = false;
    newName = "";
    newParent = "";
    newColor = null;
  }

  const select =
    "h-8 px-2.5 rounded-xl border border-border bg-field text-[13px] font-medium outline-none focus:border-focus";
  const textInput =
    "h-8 min-w-0 px-2.5 rounded-xl border border-border-strong bg-field text-sm outline-none focus:border-focus focus:ring-1 focus:ring-focus";
  const rowButton =
    "h-8 shrink-0 flex items-center gap-1.5 px-2.5 rounded-full text-[13px] font-medium hover:bg-surface-hover transition-colors disabled:opacity-60";
  const iconButton =
    "w-8 h-8 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text transition-colors";
</script>

{#snippet palette(chosen: string | null, onPick: (color: LabelColor) => void)}
  {@const chosenTone = chosen === null ? null : labelTone(chosen)}
  <fieldset aria-label="Color" class="flex flex-wrap gap-1.5">
    {#each LABEL_COLORS as color (color.tone)}
      <button
        type="button"
        aria-pressed={chosenTone === color.tone}
        aria-label={`Color ${color.tone}`}
        onclick={() => onPick(color)}
        class="w-6 h-6 rounded-full flex items-center justify-center text-surface ring-offset-2 ring-offset-surface aria-pressed:ring-2 aria-pressed:ring-focus"
        style:background-color={`var(--color-label-${color.tone})`}
      >
        {#if chosenTone === color.tone}
          <Check class="w-3.5 h-3.5" aria-hidden="true" />
        {/if}
      </button>
    {/each}
  </fieldset>
{/snippet}

<div class="h-16 flex items-center gap-3">
  <h1 class="flex-1 text-xl leading-7 font-semibold tracking-[-0.01em]">
    Labels
  </h1>
  <button
    type="button"
    onclick={() => {
      creating = true;
      newError = null;
    }}
    class="h-9 flex items-center gap-2 pl-3 pr-3.5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:brightness-110 transition"
  >
    <Plus class="w-4 h-4" aria-hidden="true" />
    New label
  </button>
</div>

{#if creating}
  <form
    aria-label="New label"
    onsubmit={(event) => {
      event.preventDefault();
      create();
    }}
    class="mb-4 rounded-[22px] bg-surface-raised shadow-pop p-4 flex flex-col gap-3"
  >
    <div class="flex items-center gap-3">
      <label for="new-label-name" class="w-24 text-sm font-medium">Name</label>
      <input
        id="new-label-name"
        type="text"
        {@attach (input: HTMLInputElement) => input.focus()}
        bind:value={newName}
        class={[textInput, "flex-1"]}
      >
    </div>
    <div class="flex items-center gap-3">
      <label for="new-label-parent" class="w-24 text-sm font-medium"
        >Nest under</label
      >
      <select id="new-label-parent" bind:value={newParent} class={select}>
        <option value="">Top level</option>
        {#each userLabels as label (label.id)}
          <option value={label.name}>{label.name}</option>
        {/each}
      </select>
    </div>
    <div class="flex items-center gap-3">
      <span class="w-24 text-sm font-medium">Color</span>
      {@render palette(newColor?.backgroundColor ?? null, (color) => {
        newColor = color;
      })}
    </div>
    {#if newError}
      <p role="alert" class="text-[13px] text-danger">{newError}</p>
    {/if}
    <div class="flex justify-end gap-2">
      <button
        type="button"
        onclick={() => {
          creating = false;
        }}
        class="h-9 px-3.5 rounded-full text-sm font-medium hover:bg-surface-hover"
      >
        Cancel
      </button>
      <button
        type="submit"
        class="h-9 px-3.5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:brightness-110 disabled:opacity-60"
      >
        Make label
      </button>
    </div>
  </form>
{/if}

{#if mail.userLabels.length > 0}
  <section
    aria-labelledby="labels-shown"
    class="mb-6 rounded-[22px] bg-surface shadow-card p-5"
  >
    <h2 id="labels-shown" class="text-[15px] font-semibold">
      Sidebar and bundles
    </h2>
    <p class="mt-1 text-[13px] text-text-secondary">
      Choose which labels the sidebar lists and which fold into bundles in your
      inbox. The gear picks an icon.
    </p>
    <div
      class="mt-3 grid grid-cols-[1fr_auto_auto_auto] items-center gap-x-4 text-sm"
    >
      <span></span>
      <span class="text-xs font-semibold text-text-secondary text-center"
        >Sidebar</span
      >
      <span class="text-xs font-semibold text-text-secondary text-center"
        >Bundle</span
      >
      <span></span>
      {#each mail.userLabels as label (label.id)}
        {@const prefs = mail.labelPrefs.get(label.id)}
        <span
          class="min-h-11 flex items-center gap-3 min-w-0 border-t border-separator"
        >
          <LabelMark {label} size={28} />
          <span class="truncate">{label.name}</span>
        </span>
        <span
          class="min-h-11 flex items-center justify-center border-t border-separator"
        >
          <input
            type="checkbox"
            role="switch"
            aria-label={`Show ${label.name} in the sidebar`}
            aria-checked={prefs?.sidebar ?? true}
            checked={prefs?.sidebar ?? true}
            onchange={(event) =>
              void mail.setLabelPrefs(label.id, {
                sidebar: event.currentTarget.checked,
              })}
            class={toggle}
          >
        </span>
        <span
          class="min-h-11 flex items-center justify-center border-t border-separator"
        >
          <input
            type="checkbox"
            role="switch"
            aria-label={`Bundle ${label.name} in the inbox`}
            aria-checked={prefs?.bundle ?? true}
            checked={prefs?.bundle ?? true}
            onchange={(event) =>
              void mail.setLabelPrefs(label.id, {
                bundle: event.currentTarget.checked,
              })}
            class={toggle}
          >
        </span>
        <span class="min-h-11 flex items-center border-t border-separator">
          <LabelSettingsButton {label} name={label.name} />
        </span>
      {/each}
    </div>
  </section>
{/if}

{#if error}
  <p role="alert" class="mb-3 px-1 text-[13px] text-danger">{error}</p>
{/if}

{#if tree.length === 0}
  <p class="pt-12 text-center text-text-secondary">
    No labels yet. Make one to sort mail into it.
  </p>
{:else}
  <ul class="flex flex-col rounded-[22px] bg-surface shadow-card">
    {#each tree as node, index (node.label.id)}
      {@const label = node.label}
      {@const mode = editing?.id === label.id ? editing.mode : null}
      <li
        class={[
          "px-2 py-1.5 flex flex-col gap-2",
          index > 0 && "border-t border-surface-muted",
        ]}
      >
        <div
          class="min-h-10 flex items-center gap-3"
          style:padding-left={`${8 + node.depth * 24}px`}
        >
          <button
            type="button"
            aria-label={`Color of ${label.name}`}
            aria-expanded={mode === "color"}
            onclick={() =>
              mode === "color" ? (editing = null) : start(label, "color")}
            class="w-6 h-6 shrink-0 flex items-center justify-center rounded-full hover:bg-surface-hover"
          >
            <span
              class="w-2.5 h-2.5 rounded-full"
              style:background-color={labelDot(label)}
            ></span>
          </button>
          {#if mode === "rename"}
            <form
              class="flex-1 flex items-center gap-2"
              onsubmit={(event) => {
                event.preventDefault();
                rename(label, nestedName(node.parent, draftName));
              }}
            >
              <label class="sr-only" for={`rename-${label.id}`}
                >New name for {label.name}</label
              >
              <input
                id={`rename-${label.id}`}
                type="text"
                {@attach (input: HTMLInputElement) => input.focus()}
                bind:value={draftName}
                onkeydown={(event) => {
                  // Escape keeps a changed name, as Android's Back does; the cross drops it.
                  if (event.key === "Escape") {
                    event.preventDefault();
                    if (draftName.trim() !== "" && draftName !== node.leaf) {
                      rename(label, nestedName(node.parent, draftName));
                    } else editing = null;
                  }
                }}
                class={[textInput, "flex-1"]}
              >
              <button type="submit" class={rowButton}>Save</button>
              <button
                type="button"
                aria-label="Cancel rename"
                onclick={() => {
                  editing = null;
                }}
                class={iconButton}
              >
                <X class="w-4 h-4" />
              </button>
            </form>
          {:else if mode === "move"}
            <span class="flex-1 min-w-0 truncate">{node.leaf}</span>
            <label
              class="text-[13px] text-text-secondary"
              for={`move-${label.id}`}
              >Nest under</label
            >
            <select
              id={`move-${label.id}`}
              bind:value={draftParent}
              class={select}
            >
              <option value="">Top level</option>
              {#each parentChoices(label) as candidate (candidate.id)}
                <option value={candidate.name}>{candidate.name}</option>
              {/each}
            </select>
            <button
              type="button"
              onclick={() =>
                rename(
                  label,
                  nestedName(
                    draftParent === "" ? null : draftParent,
                    node.leaf,
                  ),
                )}
              class={rowButton}
            >
              Move
            </button>
            <button
              type="button"
              aria-label="Cancel move"
              onclick={() => {
                editing = null;
              }}
              class={iconButton}
            >
              <X class="w-4 h-4" />
            </button>
          {:else}
            <span class="flex-1 min-w-0 truncate">{node.leaf}</span>
            <button
              type="button"
              onclick={() => start(label, "rename")}
              class={rowButton}
            >
              <Pencil class="w-4 h-4" aria-hidden="true" />
              Rename
            </button>
            <button
              type="button"
              onclick={() => start(label, "move")}
              class={rowButton}
            >
              <FolderInput class="w-4 h-4" aria-hidden="true" />
              Move
            </button>
          {/if}
        </div>
        {#if mode === "color"}
          <div style:padding-left={`${44 + node.depth * 24}px`} class="pb-1">
            {@render palette(label.color?.backgroundColor ?? null, (color) =>
              recolor(label, color),
            )}
          </div>
        {/if}
      </li>
    {/each}
  </ul>
{/if}
