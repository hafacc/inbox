<!-- @component The search field: finished operators (and, while todos show, finished `#tags`, a tag naming a label in its color) turn into chips as you type, a `#name` naming a label searches as its `label:` term (`searchTagTerms`) but stays as typed here, in the address and in recent searches, Tab completes the operator, value or todo tag under the cursor, Backspace turns the last chip back into text, Enter completes the suggestion the arrow keys are on, or the first one on an operator with no value yet (`in:`), and otherwise searches the text as typed, and Esc leaves search for the view it started from. Its cross empties the field and keeps search open. Its suggestions show once typing pauses briefly, never from selecting or clicking in the text. Opened empty, it offers this device's recent searches. -->
<script lang="ts">
  import { type ChipSpot, typeFree } from "@inbox/shared/chip-or";
  import type { Mailbox } from "@inbox/shared/compose";
  import {
    addRecentSearch,
    removeRecentSearch,
  } from "@inbox/shared/recent-searches";
  import {
    applyCompletion,
    awaitsValue,
    type Candidate,
    type Chip,
    type CompletionContext,
    type CompletionOptions,
    completions,
    formatChip,
    parseQuery,
    queryText,
    TAG_CHIP,
    unchip,
    withoutOperator,
  } from "@inbox/shared/search";
  import {
    completeTag,
    suggestTags,
    type TypedTag,
    typingTag,
  } from "@inbox/shared/todos";
  import History from "@lucide/svelte/icons/history";
  import Search from "@lucide/svelte/icons/search";
  import X from "@lucide/svelte/icons/x";
  import { onMount } from "svelte";
  import { backToPreviousPlace } from "../back-stack.svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";
  import { searchPlan } from "../search/search-plan";
  import { closeMenu } from "./menu-motion";

  let {
    onSearch,
  }: {
    /** called after a search runs, from the field or a recent search */
    onSearch?: () => void;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const HEADINGS: Record<string, string> = {
    in: "Mailbox",
    is: "State",
    has: "Has",
    label: "Labels",
    category: "Category",
    after: "Dates",
    before: "Dates",
    older_than: "Age",
    newer_than: "Age",
    larger: "Size",
    smaller: "Size",
    size: "Size",
    from: "People",
    to: "People",
    cc: "People",
    bcc: "People",
    deliveredto: "People",
  };

  let input: HTMLInputElement;
  let chipRow = $state<HTMLDivElement>();
  // Whether finished `#tags` turn into chips: while todos show, which have them.
  const tagChips = $derived(mail.todos.enabled);
  // The shown search's query, which the field starts over from whenever the view changes.
  const shownQuery = $derived(
    mail.view.kind === "search"
      ? parseQuery(mail.view.query, null, tagChips)
      : null,
  );
  // What the field holds: the finished chips, the text being typed, where its caret goes (null to
  // leave it where it is), and what Tab completes there: an operator or its value, else a todo tag
  // being typed after `#` while todos show. It starts over from the shown search whenever the view
  // changes; every edit replaces it whole.
  type Field = {
    chips: Chip[];
    free: string;
    caret: number | null;
    context: CompletionContext | null;
    typed: { tag: TypedTag; caret: number } | null;
  };
  let field = $derived<Field>({
    chips: shownQuery?.chips ?? [],
    free: shownQuery?.free ?? "",
    caret: null,
    context: null,
    typed: null,
  });
  const chips = $derived(field.chips);
  const free = $derived(field.free);
  const context = $derived(field.context);
  const typed = $derived(field.typed);
  // The suggestion the arrow keys are on, or -1 for none.
  let highlight = $state(-1);
  // This device's recent searches, newest first; cleared with everything else on sign-out.
  const RECENT_KEY = "inbox:recent-searches";
  let recent = $state<string[]>([]);
  // The recent search the arrow keys are on, or -1 for none.
  let recentHighlight = $state(-1);
  let focused = $state(false);
  let dismissed = $state(false);
  // Whether the suggestions show: once typing pauses, until the field loses focus.
  let open = $state(false);
  let openTimer: ReturnType<typeof setTimeout> | undefined;
  // How long typing must pause before the suggestions show, so one letter doesn't flash them.
  const OPEN_DELAY_MS = 300;
  // The last chip formed while typing, to put back as text if `OR` follows it.
  let spot: ChipSpot | null = null;

  // Contacts, then recent senders not among them, for from:/to: completion.
  const people = $derived.by((): Mailbox[] => {
    const seen = new Set<string>();
    const out: Mailbox[] = [];
    for (const contact of hub.contacts.contacts) {
      const key = contact.email.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ name: contact.name, email: contact.email });
    }
    const byLatest = [...mail.summaries.values()].sort(
      (left, right) => right.lastDate - left.lastDate,
    );
    for (const thread of byLatest) {
      for (const sender of thread.senders) {
        const key = sender.email.toLowerCase();
        if (key === "" || seen.has(key)) continue;
        seen.add(key);
        out.push({ name: sender.name, email: sender.email });
      }
    }
    return out;
  });

  const completionOptions = $derived<CompletionOptions>({
    now: mail.now,
    timeZone,
    labels: mail.userLabels
      .filter((label) => label.type === "user")
      .map((label) => label.name),
    contacts: people,
  });

  const candidates = $derived.by<Candidate[]>(() => {
    if (!focused || dismissed) return [];
    // An empty field offers the recent searches instead, when there are any.
    if (chips.length === 0 && free === "" && recent.length > 0) return [];
    if (typed !== null) {
      return suggestTags(
        mail.todos.tagCounts,
        "",
        typed.tag.prefix,
        undefined,
        mail.labelTagNames,
      ).map((name) => ({ label: `#${name}`, value: name, detail: null }));
    }
    return context === null ? [] : completions(context, completionOptions);
  });
  // The suggestions on screen; Tab completes from `candidates` before they show, too.
  const listed = $derived(open ? candidates : []);
  const recentShown = $derived(
    focused &&
      !dismissed &&
      chips.length === 0 &&
      free === "" &&
      candidates.length === 0 &&
      recent.length > 0,
  );

  onMount(() => {
    try {
      const saved: unknown = JSON.parse(
        localStorage.getItem(RECENT_KEY) ?? "[]",
      );
      if (Array.isArray(saved)) {
        recent = saved.filter((entry) => typeof entry === "string");
      }
    } catch {
      // Storage can be blocked or hold something else; there are just no recent searches.
    }
  });

  $effect(() => () => clearTimeout(openTimer));

  function keepRecent(next: string[]): void {
    recent = next;
    recentHighlight = -1;
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(next));
    } catch {
      // Not remembered past this page, but still listed for now.
    }
  }

  const heading = $derived(
    typed !== null
      ? "Todo tags"
      : context === null
        ? ""
        : context.kind === "operator"
          ? "Operators"
          : (HEADINGS[context.operator] ?? "Values"),
  );

  function chipValueText(chip: Chip): string {
    if (["from", "to", "cc", "bcc"].includes(chip.operator)) {
      const known = people.find(
        (person) => person.email.toLowerCase() === chip.value.toLowerCase(),
      );
      if (known?.name) return known.name;
    }
    return chip.value;
  }

  function setFree(text: string, cursor: number) {
    const typedField = typeFree(chips, spot, text, cursor, tagChips);
    const { parsed } = typedField;
    spot = typedField.spot;
    const tag = mail.todos.enabled
      ? typingTag(parsed.free, parsed.cursor)
      : null;
    field = {
      chips: typedField.chips,
      free: parsed.free,
      caret: parsed.cursor,
      context: tag ? null : parsed.context,
      typed: tag && { tag, caret: parsed.cursor },
    };
    highlight = -1;
  }

  // The one writer of the field's text: it shows `field.free` once an edit changes it (a chip
  // forming, Backspace, a completion, the view changing), with the caret where the edit put it.
  function showFree(node: HTMLInputElement) {
    const { free: text, caret } = field;
    if (node.value !== text) node.value = text;
    const moved = node.selectionStart !== caret || node.selectionEnd !== caret;
    if (caret !== null && moved && document.activeElement === node) {
      node.setSelectionRange(caret, caret);
    }
  }

  // Show the suggestions once typing pauses; each keystroke starts the wait over.
  function openSoon() {
    if (open) return;
    clearTimeout(openTimer);
    openTimer = setTimeout(() => {
      openTimer = undefined;
      open = true;
    }, OPEN_DELAY_MS);
  }

  function closeSuggestions() {
    clearTimeout(openTimer);
    openTimer = undefined;
    open = false;
  }

  function onInput() {
    dismissed = false;
    openSoon();
    setFree(input.value, input.selectionStart ?? input.value.length);
  }

  // A click moves the caret: what Tab completes follows it, but the text, the selection and
  // whether the suggestions show stay as they are.
  function onClick() {
    const cursor = input.selectionStart ?? input.value.length;
    const parsed = parseQuery(input.value, cursor, tagChips);
    const tag =
      mail.todos.enabled && parsed.chips.length === 0
        ? typingTag(parsed.free, parsed.cursor)
        : null;
    field = {
      ...field,
      caret: null,
      context: tag || parsed.chips.length > 0 ? null : parsed.context,
      typed: tag && { tag, caret: parsed.cursor },
    };
    highlight = -1;
  }

  function complete(candidate: Candidate) {
    if (typed !== null) {
      const edit = completeTag(free, typed.tag, typed.caret, candidate.value);
      setFree(edit.text, edit.caret);
      openSoon();
      input.focus();
      return;
    }
    if (context === null) return;
    const edit = applyCompletion(free, context, candidate);
    setFree(edit.text, edit.cursor);
    openSoon();
    input.focus();
  }

  function removeChip(index: number) {
    field = {
      ...field,
      chips: chips.filter((_, other) => other !== index),
      caret: null,
    };
    input.focus();
  }

  function run(query: string) {
    closeSuggestions();
    keepRecent(addRecentSearch(recent, query));
    input.blur();
    if (searchPlan(query).outbox) mail.setView({ kind: "outbox" });
    else mail.setView({ kind: "search", query });
    onSearch?.();
  }

  function submit(text = free) {
    const query = queryText(chips, text);
    if (query === "") leave();
    else run(query);
  }

  function empty() {
    field = { chips: [], free: "", caret: null, context: null, typed: null };
    spot = null;
    highlight = -1;
  }

  // The cross: an empty field, still in search and focused, ready to type again.
  function clear() {
    empty();
    input.focus();
  }

  // Escape, or searching for nothing: back to the view the search started from.
  function leave() {
    empty();
    input.blur();
    // The entry before is the view search started from, which a Back to a search can't know.
    if (mail.view.kind === "search" && !backToPreviousPlace()) {
      mail.closeSearch();
    }
  }

  function onKeydown(event: KeyboardEvent) {
    if (recentShown && ["ArrowDown", "ArrowUp"].includes(event.key)) {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      // The field itself is a stop too, at -1.
      const stops = recent.length + 1;
      recentHighlight = ((recentHighlight + 1 + step + stops) % stops) - 1;
    } else if (recentShown && event.key === "Enter" && recentHighlight >= 0) {
      event.preventDefault();
      run(recent[recentHighlight]!);
    } else if (event.key === "Enter" && highlight >= 0 && listed[highlight]) {
      event.preventDefault();
      complete(listed[highlight]!);
    } else if (
      event.key === "Enter" &&
      typed === null &&
      awaitsValue(context)
    ) {
      // An operator with no value yet isn't a query to search: finish it with the first suggestion,
      // else search without it.
      event.preventDefault();
      const first = completions(context, completionOptions)[0];
      if (first !== undefined) complete(first);
      else submit(withoutOperator(free, context));
    } else if (
      event.key === "Tab" &&
      candidates.length > 0 &&
      !event.shiftKey
    ) {
      event.preventDefault();
      complete(candidates[highlight] ?? candidates[0]!);
    } else if (
      (event.key === "ArrowDown" || event.key === "ArrowUp") &&
      candidates.length > 0 &&
      !open
    ) {
      // The arrows show the suggestions at once, on the first.
      event.preventDefault();
      closeSuggestions();
      open = true;
      highlight = 0;
    } else if (
      (event.key === "ArrowDown" || event.key === "ArrowUp") &&
      listed.length > 0
    ) {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      // The field itself is a stop too, at -1.
      const stops = listed.length + 1;
      highlight = ((highlight + 1 + step + stops) % stops) - 1;
    } else if (
      event.key === "Backspace" &&
      chips.length > 0 &&
      input.selectionStart === 0 &&
      input.selectionEnd === 0
    ) {
      event.preventDefault();
      const back = unchip(chips, chips.length - 1, free);
      field = {
        chips: back.chips,
        free: back.free,
        caret: back.cursor,
        context: parseQuery(back.free, back.cursor, tagChips).context,
        typed: null,
      };
    } else if (event.key === "Escape") {
      event.preventDefault();
      if (listed.length > 0 || recentShown || openTimer !== undefined) {
        closeSuggestions();
        dismissed = true;
      } else leave();
    }
  }

  // A click on the box outside the field (the magnifier, the padding, a chip or the gaps) puts the
  // caret at the end of the field, as a click past its text does.
  function focusAtEnd(event: MouseEvent) {
    if (event.target === input || (event.target as Element).closest("button")) {
      return;
    }
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    chipRow?.scrollTo({ left: chipRow.scrollWidth });
  }

  /** Put the caret in the field. */
  export function focus(): void {
    input.focus();
    input.select();
  }
</script>

<search class="relative flex-1 min-w-0 flex">
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
  <!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click around the field puts the caret in it; the keyboard reaches the field itself -->
  <form
    class="flex-1 min-w-0 h-[42px] flex items-center gap-2 pl-4 pr-2 rounded-full bg-field border border-border focus-within:border-focus focus-within:shadow-[0_0_0_1px_var(--color-focus)] transition-[border-color,box-shadow] duration-150 cursor-text"
    onsubmit={(event) => {
      event.preventDefault();
      submit();
    }}
    onclick={focusAtEnd}
  >
    <Search
      class="w-[18px] h-[18px] shrink-0 text-text-secondary mr-1"
      aria-hidden="true"
    />
    <div
      bind:this={chipRow}
      class="flex-1 min-w-0 self-stretch flex items-center gap-2 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      {#each chips as chip, index (`${index}:${formatChip(chip)}`)}
        {@const tagColor =
          chip.operator === TAG_CHIP ? mail.tagColor(chip.value) : null}
        <span
          class={[
            "h-6 shrink-0 max-w-56 inline-flex items-center gap-1 pl-2 pr-0.5 rounded-full text-[13px]",
            chip.operator !== TAG_CHIP
              ? "bg-surface-muted"
              : tagColor === null
                ? "bg-accent-soft text-accent-strong"
                : "text-(--tag-color) bg-[color-mix(in_srgb,var(--tag-color)_14%,transparent)]",
          ]}
          style:--tag-color={tagColor}
        >
          {#if chip.operator === TAG_CHIP}
            <span class="font-medium truncate"
              >{chip.negated ? "-" : ""}#{chip.value}</span
            >
          {:else}
            <span class="text-text-secondary"
              >{chip.negated ? "-" : ""}{chip.operator}:</span
            >
            <span class="font-medium truncate">{chipValueText(chip)}</span>
          {/if}
          <button
            type="button"
            aria-label={`Remove ${formatChip(chip)}`}
            onclick={() => removeChip(index)}
            class={[
              "hit-target w-[18px] h-[18px] flex items-center justify-center rounded-full hover:bg-border",
              chip.operator === TAG_CHIP
                ? "text-current"
                : "text-text-secondary",
            ]}
          >
            <X class="w-3 h-3" />
          </button>
        </span>
      {/each}
      <label for="search-mail" class="sr-only">Search mail</label>
      <input
        id="search-mail"
        bind:this={input}
        {@attach showFree}
        oninput={onInput}
        onkeydown={onKeydown}
        onclick={onClick}
        onfocus={() => {
          focused = true;
          dismissed = false;
        }}
        onblur={() => {
          focused = false;
          closeSuggestions();
        }}
        type="search"
        placeholder={chips.length === 0 ? "Search mail" : ""}
        autocomplete="off"
        spellcheck="false"
        role="combobox"
        aria-expanded={listed.length > 0 || recentShown}
        aria-controls={recentShown ? "search-recent" : "search-completions"}
        aria-autocomplete="list"
        aria-activedescendant={listed.length > 0 && highlight >= 0
          ? `search-option-${highlight}`
          : recentShown && recentHighlight >= 0
            ? `search-recent-${recentHighlight}`
            : undefined}
        class="flex-1 min-w-24 self-stretch bg-transparent outline-none text-sm text-text placeholder:text-text-secondary [&::-webkit-search-cancel-button]:hidden"
      >
    </div>
    {#if chips.length > 0 || free !== ""}
      <button
        type="button"
        aria-label="Clear search"
        title="Clear search"
        onmousedown={(event) => event.preventDefault()}
        onclick={clear}
        class="hit-target w-7 h-7 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
      >
        <X class="w-4 h-4" />
      </button>
    {/if}
  </form>
  {#if listed.length > 0}
    <div
      out:closeMenu
      id="search-completions"
      role="listbox"
      aria-label={heading}
      class="absolute top-12 left-0 z-30 w-[320px] p-2 rounded-[20px] bg-surface-raised shadow-pop animate-rise"
    >
      <div
        aria-hidden="true"
        class="h-7 flex items-center px-2 text-[13px] font-semibold text-text-secondary"
      >
        {heading}
      </div>
      {#each listed as candidate, index (candidate.value + candidate.label)}
        <!-- svelte-ignore a11y_click_events_have_key_events: the field handles the keys (Tab, arrows) -->
        <div
          id={`search-option-${index}`}
          role="option"
          tabindex="-1"
          aria-selected={index === highlight}
          onmousedown={(event) => {
            event.preventDefault();
            complete(candidate);
          }}
          class={[
            "min-h-9 flex items-center gap-3 px-2.5 py-1 rounded-xl text-sm cursor-pointer hover:bg-surface-hover",
            index === highlight && "bg-surface-muted",
          ]}
        >
          <span class="flex-1 min-w-0">
            <span class="block truncate">
              {#if context?.kind === "value"}
                <span class="text-text-secondary">{context.operator}:</span>
              {/if}
              {#if typed !== null}
                <span
                  class="font-medium text-accent-strong"
                  style:color={mail.tagColor(candidate.value)}
                  >{candidate.label}</span
                >
              {:else}
                {candidate.label}
              {/if}
            </span>
            {#if candidate.detail}
              <span class="block truncate text-xs text-text-secondary">
                {candidate.detail}
              </span>
            {/if}
          </span>
        </div>
      {/each}
      {#if context?.kind === "operator" && context.prefix === ""}
        <button
          type="button"
          onmousedown={(event) => {
            event.preventDefault();
            mail.setView({ kind: "settings", section: "shortcuts" });
          }}
          class="mt-1 w-full h-9 flex items-center px-2 rounded-full text-[13px] font-semibold text-accent-strong hover:bg-surface-hover"
        >
          All search operators
        </button>
      {/if}
    </div>
  {:else if recentShown}
    <div
      out:closeMenu
      id="search-recent"
      role="listbox"
      aria-label="Recent searches"
      class="absolute top-12 left-0 z-30 w-[min(360px,calc(100vw-2rem))] p-2 rounded-[20px] bg-surface-raised shadow-pop animate-rise"
    >
      <div
        aria-hidden="true"
        class="h-7 flex items-center px-2 text-[13px] font-semibold text-text-secondary"
      >
        Recent searches
      </div>
      {#each recent as query, index (query)}
        <div
          class={[
            "flex items-center rounded-full",
            index === recentHighlight && "bg-surface-muted",
          ]}
        >
          <!-- svelte-ignore a11y_click_events_have_key_events: the field handles the keys (arrows, Enter) -->
          <div
            id={`search-recent-${index}`}
            role="option"
            tabindex="-1"
            aria-selected={index === recentHighlight}
            onmousedown={(event) => {
              event.preventDefault();
              run(query);
            }}
            class="flex-1 min-w-0 min-h-12 flex items-center gap-3 pl-3 text-sm cursor-pointer"
          >
            <History
              class="w-4 h-4 shrink-0 text-text-secondary"
              aria-hidden="true"
            />
            <span class="truncate">{query}</span>
          </div>
          <button
            type="button"
            aria-label={`Remove ${query} from recent searches`}
            title="Remove"
            onmousedown={(event) => {
              event.preventDefault();
              keepRecent(removeRecentSearch(recent, query));
            }}
            class="w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
          >
            <X class="w-4 h-4" aria-hidden="true" />
          </button>
        </div>
      {/each}
    </div>
  {/if}
</search>
