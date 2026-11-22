<!-- @component The label menu by its button (a bottom sheet at phone width): type to filter, click or Enter to add or remove a label on the threads (a label only some carry shows a dash and adds to the rest), create one from what's typed, or move the threads to another of Gmail's categories; Esc closes and returns to the button. -->
<script lang="ts">
  import {
    type LabelChoice,
    labelChoice,
    type SetAction,
    selectionCategory,
  } from "@inbox/shared/selection";
  import Check from "@lucide/svelte/icons/check";
  import Minus from "@lucide/svelte/icons/minus";
  import Plus from "@lucide/svelte/icons/plus";
  import Search from "@lucide/svelte/icons/search";
  import Settings from "@lucide/svelte/icons/settings";
  import { tick } from "svelte";
  import {
    categoryMenuItems,
    labelDot,
    labelMenuItems,
    offersCreate,
  } from "../labels";
  import { getMailState } from "../mail-state.svelte";
  import { LABEL_ICON_COMPONENTS } from "./label-icons";
  import MenuSheet from "./menu-sheet.svelte";
  import { phoneWidth } from "./phone";

  let {
    threadIds,
    onAction = () => {},
  }: {
    /** the threads whose labels it changes */
    threadIds: readonly string[];
    /** called after a change is applied, with what it was */
    onAction?: (action: SetAction) => void;
  } = $props();

  const mail = getMailState();
  const listId = $props.id();
  const categoryListId = `${listId}-categories`;
  let field: HTMLInputElement;
  let query = $state("");
  let highlight = $state(0);

  const threads = $derived(mail.selectedThreads(threadIds));
  const items = $derived(
    labelMenuItems(mail.labels, query, []).map((item) => {
      const choice = labelChoice(threads, item.label.id);
      return { ...item, checked: choice.state === "all", choice };
    }),
  );
  const canCreate = $derived(
    query.trim() !== "" && offersCreate(mail.labels, query),
  );
  // Threads in different categories show none checked.
  const category = $derived(selectionCategory(threads));
  // Typing looks for labels, so the categories show only while the field is empty.
  const categories = $derived(
    query.trim() === ""
      ? categoryMenuItems(category === null ? [] : [category]).map((item) => ({
          ...item,
          checked: item.checked && category !== null,
        }))
      : [],
  );
  const firstCategory = $derived(items.length + (canCreate ? 1 : 0));
  const choices = $derived(firstCategory + categories.length);

  const optionId = (index: number) => `${listId}-option-${index}`;

  // At phone width the keyboard would cover the sheet; the field is a tap away.
  $effect(() => {
    if (!phoneWidth.current) field.focus();
  });

  function toggle(labelId: string, choice: LabelChoice) {
    mail.changeLabelsAll(choice.remove, [], [labelId]);
    mail.changeLabelsAll(choice.add, [labelId], []);
    onAction("label");
  }

  function moveTo(category: string) {
    mail.changeCategoryAll(threadIds, category);
    mail.toggleLabelMenu(null);
    onAction("category");
  }

  function create() {
    const name = query.trim();
    if (name === "") return;
    const label = mail.createLabel(name);
    mail.changeLabelsAll(threadIds, [label.id], []);
    onAction("label");
    query = "";
  }

  // The highlighted choice stays in view as the arrow keys move it through a long list.
  function showHighlight() {
    void tick().then(() =>
      document
        .getElementById(optionId(highlight))
        ?.scrollIntoView({ block: "nearest" }),
    );
  }

  function onKeydown(event: KeyboardEvent) {
    let handled = true;
    if (event.key === "ArrowDown") {
      highlight = choices === 0 ? 0 : (highlight + 1) % choices;
      showHighlight();
    } else if (event.key === "ArrowUp") {
      highlight = choices === 0 ? 0 : (highlight - 1 + choices) % choices;
      showHighlight();
    } else if (event.key === "Enter") {
      const item = items[highlight];
      const category = categories[highlight - firstCategory];
      if (item) toggle(item.label.id, item.choice);
      else if (category) moveTo(category.id);
      else if (canCreate) create();
    } else handled = false;
    if (handled) event.preventDefault();
    // The page's own single-key shortcuts must not see keys typed here.
    event.stopPropagation();
  }
</script>

<MenuSheet
  label="Label as"
  role="dialog"
  onClose={() => mail.toggleLabelMenu(null)}
  onkeydown={onKeydown}
  padded
  fit
  anchored={(above) => [
    "absolute z-30 right-0 w-[264px] max-w-[calc(100vw-2rem)] p-2 flex flex-col rounded-[20px] bg-surface-raised shadow-pop animate-rise",
    above ? "bottom-full mb-1" : "top-full mt-1",
  ]}
>
  <div
    class="h-9 mb-1 shrink-0 flex items-center gap-2 px-3.5 rounded-full border border-accent ring-[3px] ring-accent-soft"
  >
    <Search class="w-4 h-4 shrink-0 text-text-secondary" aria-hidden="true" />
    <label for="label-search" class="sr-only">Label as</label>
    <input
      id="label-search"
      bind:this={field}
      bind:value={query}
      oninput={() => {
        highlight = 0;
      }}
      role="combobox"
      aria-expanded="true"
      aria-controls={listId}
      aria-autocomplete="list"
      aria-activedescendant={choices === 0 ? undefined : optionId(highlight)}
      type="search"
      placeholder="Label as…"
      autocomplete="off"
      spellcheck="false"
      class="flex-1 min-w-0 bg-transparent outline-none text-sm [&::-webkit-search-cancel-button]:hidden"
    >
  </div>
  <div
    id={listId}
    role="listbox"
    aria-label="Label as"
    aria-multiselectable="true"
    class="min-h-0 shrink overflow-y-auto overscroll-contain sm:max-h-96"
  >
    <!-- biome-ignore lint/a11y/useSemanticElements: an option group inside a listbox; a fieldset is no group of options -->
    <div role="group" aria-label="Labels">
      {#each items as item, index (item.label.id)}
        <button
          type="button"
          id={optionId(index)}
          role="option"
          tabindex="-1"
          aria-selected={item.checked}
          onclick={() => toggle(item.label.id, item.choice)}
          onmouseenter={() => {
            highlight = index;
          }}
          class={[
            "w-full h-10 max-sm:h-12 flex items-center gap-3 px-3 rounded-full text-sm text-left",
            index === highlight && "bg-surface-muted",
          ]}
        >
          {#if item.checked}
            <span
              class="w-[18px] h-[18px] shrink-0 rounded-[5px] bg-accent text-on-accent flex items-center justify-center"
            >
              <Check class="w-3 h-3" strokeWidth={3} />
            </span>
          {:else if item.choice.state === "some"}
            <span
              class="w-[18px] h-[18px] shrink-0 rounded-[5px] bg-accent text-on-accent flex items-center justify-center"
            >
              <Minus class="w-3 h-3" strokeWidth={3} />
            </span>
          {:else}
            <span
              class="w-[18px] h-[18px] shrink-0 rounded-[5px] border-[1.5px] border-border-strong"
            ></span>
          {/if}
          <span
            class="w-2.5 h-2.5 shrink-0 rounded-full"
            style:background-color={labelDot(item.label)}
          ></span>
          <span class="flex-1 min-w-0 truncate">{item.label.name}</span>
        </button>
      {/each}
      {#if canCreate}
        <button
          type="button"
          id={optionId(items.length)}
          role="option"
          tabindex="-1"
          aria-selected="false"
          onclick={create}
          onmouseenter={() => {
            highlight = items.length;
          }}
          class={[
            "w-full h-10 max-sm:h-12 flex items-center gap-3 px-3 rounded-full text-sm text-left",
            highlight === items.length && "bg-surface-muted",
          ]}
        >
          <Plus class="w-[18px] h-[18px] shrink-0 text-text-secondary" />
          <span class="flex-1 min-w-0 truncate"
            >{`Create “${query.trim()}”`}</span
          >
        </button>
      {/if}
    </div>
    {#if categories.length > 0}
      <hr class="h-px my-1 border-0 bg-separator" aria-hidden="true">
      <!-- biome-ignore lint/a11y/useSemanticElements: an option group inside a listbox; a fieldset is no group of options -->
      <div role="group" aria-labelledby={`${categoryListId}-heading`}>
        <p
          id={`${categoryListId}-heading`}
          role="presentation"
          class="px-2 pt-1 pb-0.5 text-xs font-medium text-text-secondary"
        >
          Category
        </p>
        {#each categories as category, offset (category.id)}
          {@const index = firstCategory + offset}
          {@const Icon = LABEL_ICON_COMPONENTS[category.icon]}
          <button
            type="button"
            id={optionId(index)}
            role="option"
            tabindex="-1"
            aria-selected={category.checked}
            onclick={() => moveTo(category.id)}
            onmouseenter={() => {
              highlight = index;
            }}
            class={[
              "w-full h-10 max-sm:h-12 flex items-center gap-3 px-3 rounded-full text-sm text-left",
              index === highlight && "bg-surface-muted",
            ]}
          >
            <span
              class={[
                "w-[18px] h-[18px] shrink-0 rounded-full flex items-center justify-center",
                category.checked
                  ? "bg-accent"
                  : "border-[1.5px] border-border-strong",
              ]}
            >
              {#if category.checked}
                <span class="w-1.5 h-1.5 rounded-full bg-on-accent"></span>
              {/if}
            </span>
            <span
              class="w-2.5 shrink-0 flex items-center justify-center"
              style:color={`var(--color-label-${category.tone})`}
              aria-hidden="true"
            >
              {#if Icon}
                <Icon class="w-4 h-4 shrink-0" />
              {/if}
            </span>
            <span class="flex-1 min-w-0 truncate">{category.name}</span>
          </button>
        {/each}
      </div>
    {/if}
  </div>
  <hr class="h-px my-1 shrink-0 border-0 bg-separator">
  <button
    type="button"
    onclick={() => {
      mail.toggleLabelMenu(null);
      mail.setView({ kind: "settings", section: "labels" });
    }}
    class="w-full h-10 max-sm:h-12 shrink-0 flex items-center gap-3 px-2 rounded-full text-sm text-left hover:bg-surface-muted"
  >
    <Settings class="w-[18px] h-[18px] shrink-0 text-text-secondary" />
    Manage labels
  </button>
</MenuSheet>
