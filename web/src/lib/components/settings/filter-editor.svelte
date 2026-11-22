<!-- @component The filter editor: conditions as rows in all / any / none groups (or raw search text), the actions, a live preview of what matches, and save as a new Gmail filter. Escape, its close button or going elsewhere keeps a change, as Save does (one that matches nothing, does nothing or is missing a picked label or address is dropped); Cancel drops it. -->
<script lang="ts">
  import {
    type ConditionGroup,
    conditionsToSearch,
    criteriaToConditions,
    editorToFilter,
    FILTER_FIELDS,
    type FilterEditor,
    type FilterField,
    filterIsWhole,
    labelChanges,
    type RowOperator,
    rowError,
  } from "@inbox/shared/filters";
  import { formatListDate } from "@inbox/shared/time-format";
  import Copy from "@lucide/svelte/icons/copy";
  import Funnel from "@lucide/svelte/icons/funnel";
  import Plus from "@lucide/svelte/icons/plus";
  import X from "@lucide/svelte/icons/x";
  import { untrack } from "svelte";
  import {
    blankRow,
    FIELD_OPERATORS,
    FIELD_TEXT,
    MATCH_TEXT,
    OPERATOR_TEXT,
    withField,
  } from "../../filters/filter-view";
  import { isNetworkError } from "../../gmail/client";
  import { isCommandChord, isMacPlatform } from "../../keyboard";
  import { getMailState } from "../../mail-state.svelte";
  import { chordTitle } from "../../shortcuts";
  import {
    sendersLabel,
    summarizeThread,
    type ThreadSummary,
  } from "../../thread-summary";

  let {
    initial,
    editingId,
    onDone,
    onSaved,
  }: {
    /** the filter to start from */
    initial: FilterEditor;
    /** the Gmail filter this replaces on save, or null for a new one */
    editingId: string | null;
    /** close without saving (Cancel, or leaving with nothing changed) */
    onDone: () => void;
    /** called once the filter is saved */
    onSaved: () => void;
  } = $props();

  const mail = getMailState();
  const PREVIEW_DELAY_MS = 400;
  const FORWARDING_URL = "https://mail.google.com/mail/u/0/#settings/fwdandpop";
  const NEW_LABEL = "__new__";

  // The parent remounts the editor for each filter, so only the first value counts.
  let editor = $state<FilterEditor>(
    structuredClone($state.snapshot(untrack(() => initial))),
  );
  let touched = $state(false);
  let error = $state<string | null>(null);
  let applyExisting = $state(true);
  let copied = $state(false);
  let labelOn = $state(editor.actions.labelId !== null);
  let forwardOn = $state(editor.actions.forward !== null);
  let newLabelName = $state<string | null>(null);
  let labelError = $state<string | null>(null);
  let forwardAddresses = $state<string[] | null>(null);
  // What the search matches: for which search, and how it went.
  let preview = $state.raw<
    | { query: string; kind: "loading" }
    | { query: string; kind: "error"; text: string }
    | {
        query: string;
        kind: "shown";
        count: number;
        more: boolean;
        rows: ThreadSummary[];
      }
  >({ query: "", kind: "loading" });
  let copiedTimer: ReturnType<typeof setTimeout> | undefined;

  const search = $derived(conditionsToSearch(editor.conditions));
  // Each preview is the latest search's only; one for an older search isn't shown.
  const shownPreview = $derived(preview.query === search ? preview : null);

  $effect(() => {
    void mail.gmail
      .listForwardingAddresses()
      .then((addresses) => {
        forwardAddresses = addresses
          .filter((address) => address.verificationStatus === "accepted")
          .map((address) => address.forwardingEmail);
      })
      .catch(() => {
        forwardAddresses = [];
      });
  });

  $effect(() => {
    const query = search;
    if (query.trim() === "") return;
    let canceled = false;
    const timer = setTimeout(async () => {
      try {
        const page = await mail.gmail.listThreads({ q: query, maxResults: 3 });
        const stubs = page.threads ?? [];
        const threads = await Promise.all(
          stubs.map((stub) => mail.gmail.getThread(stub.id, "metadata")),
        );
        if (canceled) return;
        preview = {
          query,
          kind: "shown",
          count: page.resultSizeEstimate ?? stubs.length,
          more: page.nextPageToken !== undefined,
          rows: threads.map(summarizeThread),
        };
      } catch (failure) {
        if (canceled) return;
        preview = {
          query,
          kind: "error",
          text: isNetworkError(failure)
            ? "You're offline, so there's no preview."
            : "Couldn't run this search.",
        };
      }
    }, PREVIEW_DELAY_MS);
    return () => {
      canceled = true;
      clearTimeout(timer);
    };
  });

  const matchesText = $derived.by(() => {
    if (shownPreview?.kind !== "shown") return null;
    const { count, more } = shownPreview;
    if (count === 0) return "Matches no conversations";
    else
      return `Matches ${more ? "about " : ""}${count} conversation${count === 1 ? "" : "s"}`;
  });
  const previewRows = $derived(
    shownPreview?.kind === "shown" ? shownPreview.rows : [],
  );

  function groupsOf(): ConditionGroup[] {
    return editor.conditions.kind === "rows" ? editor.conditions.groups : [];
  }

  function addCondition() {
    const groups = groupsOf();
    const first = groups[0];
    if (first) first.rows.push(blankRow());
    else groups.push({ match: "all", rows: [blankRow()] });
  }

  function addGroup() {
    groupsOf().push({ match: "not", rows: [blankRow()] });
  }

  function removeRow(group: ConditionGroup, index: number) {
    group.rows.splice(index, 1);
    if (group.rows.length === 0) {
      const groups = groupsOf();
      groups.splice(groups.indexOf(group), 1);
    }
  }

  function changeField(
    group: ConditionGroup,
    index: number,
    field: FilterField,
  ) {
    group.rows[index] = withField(group.rows[index]!, field);
  }

  function editAsText() {
    editor.conditions = { kind: "raw", query: search };
  }

  function editAsRows() {
    if (editor.conditions.kind !== "raw") return;
    const parsed = criteriaToConditions({ query: editor.conditions.query });
    if (parsed.kind === "rows") editor.conditions = parsed;
    else error = "This search doesn't fit into rows; keep editing it as text.";
  }

  async function copySearch() {
    try {
      await navigator.clipboard.writeText(search);
      copied = true;
      clearTimeout(copiedTimer);
      copiedTimer = setTimeout(() => {
        copied = false;
      }, 1500);
    } catch {
      copied = false;
    }
  }

  function createLabel() {
    const name = (newLabelName ?? "").trim();
    if (name === "") {
      labelError = "Enter a name";
      return;
    }
    labelError = null;
    editor.actions.labelId = mail.createLabel(name).id;
    labelOn = true;
    newLabelName = null;
  }

  function current(): FilterEditor {
    return {
      conditions: $state.snapshot(editor.conditions),
      actions: {
        ...$state.snapshot(editor.actions),
        labelId: labelOn ? editor.actions.labelId : null,
        forward: forwardOn ? editor.actions.forward : null,
      },
    };
  }

  // The filter as it opened, to tell whether leaving has anything to keep.
  const opened = JSON.stringify(untrack(current));
  // Set once saved or closed, so leaving doesn't act again as the editor goes.
  let finished = false;

  // Whether leaving has a change to keep: one that is whole (one that isn't is dropped).
  function keepsChange(): boolean {
    const snapshot = current();
    return JSON.stringify(snapshot) !== opened && filterIsWhole(snapshot);
  }

  // Leave the editor (Escape or its close button): keep a whole change, as Save does, else drop it.
  function leave() {
    if (keepsChange() && write()) {
      finished = true;
      onSaved();
    } else {
      close();
    }
  }

  function close() {
    finished = true;
    onDone();
  }

  // Going elsewhere (another settings section, another filter) keeps a change too.
  $effect(() => () => {
    clearTimeout(copiedTimer);
    if (!finished && keepsChange()) write();
  });

  function save() {
    touched = true;
    if (write()) {
      finished = true;
      onSaved();
    }
  }

  // Save the filter if it is whole, else say what's missing; returns whether it saved.
  function write(): boolean {
    error = null;
    const snapshot = current();
    const { actions } = snapshot;
    const filter = editorToFilter(snapshot);
    if (Object.keys(filter.criteria).length === 0) {
      error = "Add a condition to match first.";
      return false;
    }
    if (labelOn && actions.labelId === null) {
      error = "Pick a label, or turn Label off.";
      return false;
    }
    if (forwardOn && !actions.forward) {
      error = "Pick an address to forward to, or turn Forward off.";
      return false;
    }
    if (Object.keys(filter.action).length === 0) {
      error = "Pick something for the filter to do.";
      return false;
    }
    mail.saveFilter(
      { criteria: filter.criteria, action: filter.action },
      editingId,
    );
    if (applyExisting) {
      const { add, remove } = labelChanges(actions);
      mail.applyToMatching(
        conditionsToSearch(snapshot.conditions),
        add,
        remove,
      );
    }
    return true;
  }

  function onKey(event: KeyboardEvent) {
    if (isCommandChord(event) && event.key === "Enter") {
      event.preventDefault();
      save();
    } else if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      leave();
    }
  }

  const select =
    "h-8 px-2.5 rounded-xl border border-border bg-field text-[13px] font-medium outline-none focus:border-focus";
  const textInput =
    "flex-1 min-w-0 h-8 px-2.5 rounded-xl border border-border bg-field text-[13px] outline-none focus:border-focus focus:ring-1 focus:ring-focus";
  const iconButton =
    "w-8 h-8 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text transition-colors";
  const linkButton =
    "h-7 flex items-center gap-1.5 px-2 rounded-full text-[13px] font-medium text-accent-strong hover:bg-surface-hover transition-colors";
  const sectionTitle = "text-[13px] font-semibold text-text-secondary";
  const check = "w-4 h-4 m-0 shrink-0 accent-accent";
</script>

{#snippet rowFields(group: ConditionGroup, groupIndex: number)}
  {#each group.rows as row, index (index)}
    {@const problem = touched ? rowError(row) : null}
    <div
      class={[
        "min-h-11 py-1.5 flex flex-col justify-center",
        index > 0 && "border-t border-surface-muted",
      ]}
    >
      <div class="flex items-center gap-2">
        <select
          aria-label="Field"
          value={row.field}
          onchange={(event) =>
            changeField(group, index, event.currentTarget.value as FilterField)}
          class={[select, "w-36"]}
        >
          {#each FILTER_FIELDS as field (field)}
            <option value={field}>{FIELD_TEXT[field]}</option>
          {/each}
        </select>
        {#if row.field !== "hasAttachment"}
          {#if FIELD_OPERATORS[row.field].length > 1}
            <select
              aria-label="Operator"
              bind:value={row.operator}
              class={[select, "w-32"]}
            >
              {#each FIELD_OPERATORS[row.field] as operator (operator)}
                <option value={operator}>
                  {OPERATOR_TEXT[operator as RowOperator]}
                </option>
              {/each}
            </select>
          {:else}
            <span class="w-20 text-[13px] text-text-secondary">
              {OPERATOR_TEXT[row.operator]}
            </span>
          {/if}
          <label class="sr-only" for={`filter-value-${groupIndex}-${index}`}
            >Value</label
          >
          <input
            id={`filter-value-${groupIndex}-${index}`}
            type="text"
            bind:value={row.value}
            onblur={() => {
              if (row.value.trim() !== "") touched = true;
            }}
            placeholder={row.field === "size" ? "5MB" : ""}
            aria-invalid={problem !== null}
            class={[textInput, problem && "border-danger"]}
          >
        {:else}
          <span class="flex-1"></span>
        {/if}
        <button
          type="button"
          aria-label="Remove condition"
          onclick={() => removeRow(group, index)}
          class={iconButton}
        >
          <X class="w-4 h-4" />
        </button>
      </div>
      {#if problem}
        <p class="mt-1 text-xs text-danger">{problem}</p>
      {/if}
    </div>
  {/each}
{/snippet}

<section
  aria-label={editingId === null ? "New filter" : "Edit filter"}
  {@attach (element: HTMLElement) => {
    element.addEventListener("keydown", onKey);
    return () => element.removeEventListener("keydown", onKey);
  }}
  class="my-3 rounded-[22px] bg-surface-raised shadow-pop"
>
  <div class="h-14 px-4 flex items-center gap-3 border-b border-separator">
    <span
      class="w-8 h-8 shrink-0 rounded-full bg-accent-soft text-accent-strong flex items-center justify-center"
    >
      <Funnel class="w-4 h-4" aria-hidden="true" />
    </span>
    <h2 class="flex-1 text-base font-semibold">
      {editingId === null ? "New filter" : "Edit filter"}
    </h2>
    <button
      type="button"
      aria-label="Close editor"
      onclick={leave}
      class={iconButton}
    >
      <X class="w-[18px] h-[18px]" />
    </button>
  </div>

  <div class="px-4 pt-3">
    {#if editor.conditions.kind === "rows"}
      {@const groups = editor.conditions.groups}
      {#each groups as group, groupIndex (groupIndex)}
        {#if groupIndex === 0}
          <div class="h-9 flex items-center gap-3">
            <h3 class={sectionTitle}>Match</h3>
            {#if group.match === "not"}
              <select
                aria-label="Group type"
                bind:value={group.match}
                class={[select, "w-36"]}
              >
                {#each ["all", "any", "not"] as const as match (match)}
                  <option value={match}>{MATCH_TEXT[match]}</option>
                {/each}
              </select>
            {:else}
              <fieldset
                aria-label="How conditions combine"
                class="flex rounded-full border border-border-strong overflow-hidden bg-field"
              >
                {#each ["any", "all"] as const as match, index (match)}
                  <button
                    type="button"
                    aria-pressed={group.match === match}
                    onclick={() => {
                      group.match = match;
                    }}
                    class={[
                      "h-7 px-3 text-[13px] transition-colors",
                      index > 0 && "border-l border-border",
                      group.match === match
                        ? "bg-accent-soft text-accent-strong font-semibold"
                        : "font-medium hover:bg-surface-hover",
                    ]}
                  >
                    {MATCH_TEXT[match]}
                  </button>
                {/each}
              </fieldset>
            {/if}
            <span class="flex-1"></span>
            <button type="button" onclick={editAsText} class={linkButton}>
              Edit as text
            </button>
          </div>
          <div class="mt-1 pl-3 border-l-2 border-border">
            {@render rowFields(group, groupIndex)}
          </div>
        {:else}
          <div
            class="my-1 ml-3 pl-3 pr-2 py-1 rounded-[10px] border border-border bg-bg"
          >
            <div class="h-9 flex items-center gap-2">
              <select
                aria-label="Group type"
                bind:value={group.match}
                class={[select, "w-36"]}
              >
                {#each ["all", "any", "not"] as const as match (match)}
                  <option value={match}>{MATCH_TEXT[match]}</option>
                {/each}
              </select>
              <span class="flex-1"></span>
              <button
                type="button"
                aria-label="Remove group"
                onclick={() => groups.splice(groupIndex, 1)}
                class={iconButton}
              >
                <X class="w-4 h-4" />
              </button>
            </div>
            {@render rowFields(group, groupIndex)}
          </div>
        {/if}
      {:else}
        <div class="h-9 flex items-center gap-3">
          <h3 class={sectionTitle}>Match</h3>
          <span class="text-[13px] text-text-secondary">No conditions yet</span>
        </div>
      {/each}
      <div class="h-10 flex items-center gap-1 pl-3">
        <button
          type="button"
          onclick={addCondition}
          class={[linkButton, "-ml-2"]}
        >
          <Plus class="w-4 h-4" aria-hidden="true" />
          Add condition
        </button>
        <button type="button" onclick={addGroup} class={linkButton}>
          <Plus class="w-4 h-4" aria-hidden="true" />
          Add group
        </button>
      </div>
    {:else}
      {@const raw = editor.conditions}
      <div class="h-9 flex items-center gap-3">
        <h3 class={sectionTitle}>
          <label for="filter-raw">Matches this search</label>
        </h3>
        <span class="flex-1"></span>
        <button type="button" onclick={editAsRows} class={linkButton}>
          Edit as rows
        </button>
      </div>
      <textarea
        id="filter-raw"
        rows="2"
        bind:value={raw.query}
        spellcheck="false"
        class="w-full mt-1 mb-2 resize-y rounded-xl border border-border-strong bg-field px-3 py-2 font-mono text-[13px] leading-5 outline-none focus:border-focus focus:ring-1 focus:ring-focus"
      ></textarea>
    {/if}

    <div
      class="mt-1 h-10 flex items-center gap-3 pl-3 pr-1 rounded-full bg-surface-muted"
    >
      <span class="shrink-0 text-xs font-semibold text-text-secondary"
        >Gmail search</span
      >
      <output
        aria-label="Gmail search text"
        class="flex-1 min-w-0 truncate font-mono text-[13px]"
        >{search}</output
      >
      <button
        type="button"
        aria-label={copied ? "Copied" : "Copy search text"}
        title={copied ? "Copied" : "Copy"}
        onclick={copySearch}
        class={iconButton}
      >
        <Copy class="w-4 h-4" />
      </button>
    </div>
  </div>

  <div class="grid grid-cols-2 gap-6 px-4 pt-4 pb-3">
    <div>
      <div class="h-7 flex items-center">
        <h3 class={sectionTitle}>Then</h3>
      </div>
      <div class="flex flex-col">
        <div class="min-h-9 flex items-center gap-2.5 flex-wrap">
          <input
            id="filter-label"
            type="checkbox"
            bind:checked={labelOn}
            class={check}
          >
          <label for="filter-label" class="text-sm">Label</label>
          {#if newLabelName === null}
            <select
              aria-label="Label"
              value={editor.actions.labelId ?? ""}
              onchange={(event) => {
                const value = event.currentTarget.value;
                if (value === NEW_LABEL) {
                  newLabelName = "";
                  event.currentTarget.value = editor.actions.labelId ?? "";
                } else {
                  editor.actions.labelId = value === "" ? null : value;
                  labelOn = value !== "";
                }
              }}
              class={[select, "max-w-44"]}
            >
              <option value="">Pick a label</option>
              {#each mail.userLabels as label (label.id)}
                <option value={label.id}>{label.name}</option>
              {/each}
              <option value={NEW_LABEL}>New label…</option>
            </select>
          {:else}
            <span class="flex items-center gap-1.5">
              <label class="sr-only" for="filter-new-label"
                >New label name</label
              >
              <input
                id="filter-new-label"
                type="text"
                bind:value={newLabelName}
                placeholder="Label name"
                onkeydown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    createLabel();
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    event.stopPropagation();
                    newLabelName = null;
                  }
                }}
                class={[textInput, "w-32"]}
              >
              <button type="button" onclick={createLabel} class={linkButton}>
                Add
              </button>
            </span>
          {/if}
        </div>
        {#if labelError}
          <p class="pl-6 text-xs text-danger">{labelError}</p>
        {/if}
        <div class="h-9 flex items-center gap-2.5">
          <input
            id="filter-skip"
            type="checkbox"
            bind:checked={editor.actions.archive}
            class={check}
          >
          <label for="filter-skip" class="text-sm">Skip inbox</label>
        </div>
        <div class="h-9 flex items-center gap-2.5">
          <input
            id="filter-read"
            type="checkbox"
            bind:checked={editor.actions.markRead}
            class={check}
          >
          <label for="filter-read" class="text-sm">Mark as read</label>
        </div>
        <div class="h-9 flex items-center gap-2.5">
          <input
            id="filter-pin"
            type="checkbox"
            bind:checked={editor.actions.star}
            class={check}
          >
          <label for="filter-pin" class="text-sm">Pin</label>
        </div>
        <div class="h-9 flex items-center gap-2.5">
          <input
            id="filter-spam"
            type="checkbox"
            bind:checked={editor.actions.neverSpam}
            class={check}
          >
          <label for="filter-spam" class="text-sm">Never send to spam</label>
        </div>
        <div class="h-9 flex items-center gap-2.5">
          <label for="filter-important" class="text-sm pl-[26px]"
            >Important</label
          >
          <select
            id="filter-important"
            value={editor.actions.important ?? ""}
            onchange={(event) => {
              const value = event.currentTarget.value;
              editor.actions.important =
                value === "always" || value === "never" ? value : null;
            }}
            class={select}
          >
            <option value="">Leave to Gmail</option>
            <option value="always">Always</option>
            <option value="never">Never</option>
          </select>
        </div>
        <div class="h-9 flex items-center gap-2.5">
          <input
            id="filter-delete"
            type="checkbox"
            bind:checked={editor.actions.trash}
            class={check}
          >
          <label for="filter-delete" class="text-sm">Delete</label>
        </div>
        <div class="min-h-9 flex items-center gap-2.5 flex-wrap">
          <input
            id="filter-forward"
            type="checkbox"
            bind:checked={forwardOn}
            disabled={forwardAddresses !== null &&
              forwardAddresses.length === 0 &&
              !editor.actions.forward}
            class={check}
          >
          <label for="filter-forward" class="text-sm">Forward to…</label>
          {#if forwardAddresses !== null &&
            (forwardAddresses.length > 0 || editor.actions.forward)}
            <select
              aria-label="Forward address"
              value={editor.actions.forward ?? ""}
              onchange={(event) => {
                const value = event.currentTarget.value;
                editor.actions.forward = value === "" ? null : value;
                forwardOn = value !== "";
              }}
              class={[select, "max-w-48"]}
            >
              <option value="">Pick an address</option>
              {#if editor.actions.forward &&
                !forwardAddresses.includes(editor.actions.forward)}
                <option value={editor.actions.forward}>
                  {editor.actions.forward}
                </option>
              {/if}
              {#each forwardAddresses as address (address)}
                <option value={address}>{address}</option>
              {/each}
            </select>
          {/if}
        </div>
        {#if forwardAddresses !== null && forwardAddresses.length === 0}
          <p class="pl-[26px] text-xs text-text-secondary">
            Add a forwarding address in
            <a
              href={FORWARDING_URL}
              target="_blank"
              rel="noopener noreferrer"
              class="text-accent-strong underline"
              >Gmail's settings</a
            >
            first.
          </p>
        {/if}
      </div>
    </div>

    <div>
      <div class="h-7 flex items-center gap-2">
        <span role="status" class={sectionTitle}>
          {#if search.trim() === ""}
            Add a condition to see what matches
          {:else if shownPreview?.kind === "error"}
            {shownPreview.text}
          {:else if matchesText}
            {matchesText}
          {:else}
            Searching…
          {/if}
        </span>
      </div>
      {#if previewRows.length > 0}
        <ul class="mt-1 rounded-xl border border-border overflow-hidden">
          {#each previewRows as thread (thread.id)}
            <li
              class="h-10 flex items-center gap-3 px-3 border-t border-surface-muted first:border-t-0 text-[13px]"
            >
              <span class="w-24 shrink-0 truncate font-medium">
                {sendersLabel(thread.senders, mail.email)}
              </span>
              <span class="flex-1 min-w-0 truncate">
                {thread.subject || "(no subject)"}
              </span>
              <span class="shrink-0 text-text-secondary tabular-nums">
                {formatListDate(thread.lastDate, mail.now)}
              </span>
            </li>
          {/each}
        </ul>
      {/if}
    </div>
  </div>

  <div class="px-4 py-3 flex items-center gap-3 border-t border-separator">
    <input
      id="filter-apply"
      type="checkbox"
      bind:checked={applyExisting}
      class={check}
    >
    <label for="filter-apply" class="flex-1 text-sm">
      {shownPreview?.kind === "shown" && shownPreview.count > 0
        ? `Also apply to the ${shownPreview.more ? "about " : ""}${shownPreview.count} existing conversation${shownPreview.count === 1 ? "" : "s"}`
        : "Also apply to existing conversations"}
    </label>
    <button
      type="button"
      onclick={close}
      class="h-9 px-3.5 rounded-full text-sm font-medium hover:bg-surface-hover transition-colors"
    >
      Cancel
    </button>
    <button
      type="button"
      onclick={save}
      title={chordTitle("Save filter", isMacPlatform() ? "⌘↵" : "Ctrl+Enter")}
      aria-keyshortcuts={isMacPlatform() ? "Meta+Enter" : "Control+Enter"}
      class="h-9 flex items-center gap-2 px-3.5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:brightness-110 disabled:opacity-60 transition"
    >
      Save filter
    </button>
  </div>
  {#if error}
    <p role="alert" class="px-4 pb-3 -mt-1 text-[13px] text-danger">{error}</p>
  {/if}
</section>
