<!-- @component Settings › Filters: the account's Gmail filters, each with Edit and Delete, and the editor inline for a new or edited one. -->
<script lang="ts">
  import type { FilterEditor } from "@inbox/shared/filters";
  import { filterToEditor } from "@inbox/shared/filters";
  import Funnel from "@lucide/svelte/icons/funnel";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Plus from "@lucide/svelte/icons/plus";
  import Trash from "@lucide/svelte/icons/trash";
  import { onMount } from "svelte";
  import { filterSummary, newFilterEditor } from "../../filters/filter-view";
  import { GmailError, isNetworkError } from "../../gmail/client";
  import { labelDot } from "../../labels";
  import { getMailState } from "../../mail-state.svelte";
  import FilterEditorCard from "./filter-editor.svelte";

  const mail = getMailState();

  // Gmail's filters with this device's waiting changes applied, or null before they load.
  const filters = $derived(mail.filters);
  let loadError = $state<string | null>(null);
  // The open editor: a new filter (id null) or an existing one.
  const editing = $derived(mail.filterEditing);
  let confirmingDelete = $state<string | null>(null);

  function failureText(failure: unknown): string {
    if (isNetworkError(failure)) return "You're offline.";
    if (failure instanceof GmailError) return `Gmail said: ${failure.message}`;
    return "Something went wrong.";
  }

  async function load() {
    loadError = null;
    try {
      await mail.loadFilters();
    } catch (failure) {
      loadError = failureText(failure);
    }
  }

  function open(id: string | null, editor: FilterEditor) {
    mail.openFilterEditor(editor, id);
    confirmingDelete = null;
  }

  onMount(() => {
    void load();
    // Leaving the page closes its editor, which keeps a change as it goes.
    return () => mail.closeFilterEditor();
  });

  function remove(id: string) {
    mail.deleteFilter(id);
    if (editing?.id === id) mail.closeFilterEditor();
    confirmingDelete = null;
  }

  const rowButton =
    "h-8 shrink-0 flex items-center gap-1.5 px-2.5 rounded-full text-[13px] font-medium hover:bg-surface-hover transition-colors disabled:opacity-60";
</script>

<div class="h-16 flex items-center gap-3">
  <h1 class="flex-1 text-xl leading-7 font-semibold tracking-[-0.01em]">
    Filters
  </h1>
  <button
    type="button"
    onclick={() => open(null, newFilterEditor())}
    class="h-9 flex items-center gap-2 pl-3 pr-3.5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:brightness-110 transition"
  >
    <Plus class="w-4 h-4" aria-hidden="true" />
    New filter
  </button>
</div>

<p class="mb-4 px-1 flex items-center gap-2 text-[13px] text-text-secondary">
  <Funnel class="w-4 h-4 shrink-0" aria-hidden="true" />
  These are your Gmail filters, so they sort mail everywhere you read it. Saving
  an edit replaces the filter in Gmail.
</p>

{#if editing && editing.id === null}
  {#key editing.opening}
    <FilterEditorCard
      initial={editing.editor}
      editingId={null}
      onDone={mail.closeFilterEditor}
      onSaved={mail.closeFilterEditor}
    />
  {/key}
{/if}

{#if loadError && filters !== null && mail.fetchedFilters === null}
  <p class="mb-3 px-1 text-[13px] text-text-secondary">
    {loadError}
    Only the changes waiting on this device show.
  </p>
{/if}

{#if filters === null}
  {#if loadError}
    <p class="pt-12 text-center text-text-secondary">{loadError}</p>
  {:else}
    <div class="flex flex-col gap-1" aria-busy="true">
      {#each [0, 1, 2] as placeholder (placeholder)}
        <div
          class="h-[72px] rounded-[22px] bg-surface-muted animate-shimmer"
        ></div>
      {/each}
    </div>
  {/if}
{:else if filters.length === 0 && editing === null}
  <p class="pt-12 text-center text-text-secondary">
    No filters yet. Make one to sort mail as it arrives.
  </p>
{:else}
  <div class="flex flex-col">
    {#each filters as filter, index (filter.id ?? index)}
      {@const id = filter.id ?? ""}
      {#if editing && editing.id === id}
        {#key editing.opening}
          <FilterEditorCard
            initial={editing.editor}
            editingId={id}
            onDone={mail.closeFilterEditor}
            onSaved={mail.closeFilterEditor}
          />
        {/key}
      {:else}
        {@const summary = filterSummary(filter, mail.labels)}
        <div
          class={[
            "h-[72px] flex items-center gap-4 pl-4 pr-2 bg-surface border-x border-b border-border",
            index === 0 && "border-t rounded-t-xl",
            index === filters.length - 1 && "rounded-b-xl",
          ]}
        >
          <span
            class="w-8 h-8 shrink-0 rounded-full bg-surface-muted text-text-secondary flex items-center justify-center"
          >
            <Funnel class="w-4 h-4" aria-hidden="true" />
          </span>
          <div class="flex-1 min-w-0">
            <div
              class="flex items-center gap-2 whitespace-nowrap overflow-hidden"
            >
              <span class="min-w-0 truncate">
                {#each summary.parts as part, partIndex (partIndex)}
                  {#if partIndex > 0}
                    {" "}
                  {/if}
                  <span
                    class={part.value ? "font-medium" : "text-text-secondary"}
                    >{part.text}</span
                  >
                {/each}
              </span>
              {#if summary.label}
                <span
                  class="h-6 shrink-0 inline-flex items-center gap-1.5 px-2 rounded-full bg-surface-muted text-xs font-medium"
                >
                  <span
                    class="w-2 h-2 rounded-full"
                    style:background-color={labelDot(summary.label)}
                  ></span>
                  {summary.label.name}
                </span>
              {/if}
              {#each summary.actions as action (action)}
                <span class="shrink-0 font-medium">{action}</span>
              {/each}
            </div>
            <div
              class="font-mono text-xs leading-[18px] text-text-secondary truncate"
            >
              {summary.search}
            </div>
          </div>
          {#if confirmingDelete === id}
            <span class="text-[13px] text-text-secondary shrink-0"
              >Delete this filter?</span
            >
            <button
              type="button"
              onclick={() => remove(id)}
              class={[rowButton, "text-danger"]}
            >
              Delete
            </button>
            <button
              type="button"
              onclick={() => {
                confirmingDelete = null;
              }}
              class={rowButton}
            >
              Keep
            </button>
          {:else}
            <button
              type="button"
              onclick={() => open(id, filterToEditor(filter))}
              class={rowButton}
            >
              <Pencil class="w-4 h-4" aria-hidden="true" />
              Edit
            </button>
            <button
              type="button"
              onclick={() => {
                confirmingDelete = id;
              }}
              class={rowButton}
            >
              <Trash class="w-4 h-4" aria-hidden="true" />
              Delete
            </button>
          {/if}
        </div>
      {/if}
    {/each}
  </div>
{/if}
