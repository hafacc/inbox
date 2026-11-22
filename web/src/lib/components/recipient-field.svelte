<!-- @component One address row of the composer: chips, a text field, and contact suggestions as you type. -->
<script lang="ts">
  import type { Mailbox } from "@inbox/shared/compose";
  import Lock from "@lucide/svelte/icons/lock";
  import X from "@lucide/svelte/icons/x";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import {
    addRecipients,
    chipLabel,
    parseRecipients,
  } from "../compose/recipients";
  import { searchContacts } from "../contacts/contacts";
  import { isCommandChord } from "../keyboard";
  import Avatar from "./avatar.svelte";

  let {
    label,
    mailboxes = $bindable(),
    others = [],
    onChange,
    trailing,
    focusFirst = false,
    keyed = () => false,
  }: {
    label: string;
    mailboxes: Mailbox[];
    // addresses on the other rows, left out of suggestions
    others?: Mailbox[];
    onChange: () => void;
    trailing?: import("svelte").Snippet;
    focusFirst?: boolean;
    // whether a recipient has an OpenPGP key, for the lock on their chip
    keyed?: (email: string) => boolean;
  } = $props();

  const hub = getComposeHub();
  const id = $props.id();
  let text = $state("");
  let focused = $state(false);
  let highlight = $state(0);
  let invalid = $state(false);
  let input: HTMLInputElement;

  const suggestions = $derived(
    focused && text.trim().length > 0
      ? searchContacts(hub.contacts.contacts, text, [...mailboxes, ...others])
      : [],
  );

  $effect(() => {
    if (focusFirst) input.focus();
  });

  function add(added: Mailbox[]) {
    if (added.length === 0) return;
    mailboxes = addRecipients(mailboxes, added);
    onChange();
  }

  /** Turn typed text into chips; text that isn't an address stays for fixing. */
  function commit(): boolean {
    if (text.trim() === "") return false;
    const { mailboxes: parsed, invalid: rest } = parseRecipients(text);
    add(parsed);
    text = rest.join(", ");
    invalid = rest.length > 0;
    return parsed.length > 0;
  }

  function pick(index: number) {
    const contact = suggestions[index];
    if (!contact) return;
    add([{ name: contact.name, email: contact.email }]);
    text = "";
    invalid = false;
    highlight = 0;
  }

  function remove(index: number) {
    mailboxes = mailboxes.filter((_, at) => at !== index);
    onChange();
    input.focus();
  }

  function onKeydown(event: KeyboardEvent) {
    // Send from this field: what's typed counts as a recipient.
    if (event.key === "Enter" && isCommandChord(event)) {
      commit();
      return;
    }
    if (suggestions.length > 0) {
      if (event.key === "ArrowDown") {
        event.preventDefault();
        highlight = (highlight + 1) % suggestions.length;
        return;
      }
      if (event.key === "ArrowUp") {
        event.preventDefault();
        highlight = (highlight - 1 + suggestions.length) % suggestions.length;
        return;
      }
      if (event.key === "Enter" || (event.key === "Tab" && !event.shiftKey)) {
        event.preventDefault();
        pick(highlight);
        return;
      }
    }
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "," || event.key === ";") {
      event.preventDefault();
      commit();
    } else if (event.key === "Backspace" && text === "" && mailboxes.length) {
      event.preventDefault();
      remove(mailboxes.length - 1);
    } else if (event.key === "Escape" && suggestions.length > 0) {
      event.preventDefault();
      event.stopPropagation();
      text = "";
    }
  }

  function onPaste(event: ClipboardEvent) {
    const pasted = event.clipboardData?.getData("text/plain") ?? "";
    if (!/[,;\n]/.test(pasted)) return;
    event.preventDefault();
    text = `${text}${pasted}`;
    commit();
  }
</script>

<div class="relative min-h-7 flex items-center gap-2">
  <label for={id} class="w-11 shrink-0 text-text-secondary">{label}</label>
  <div class="flex-1 min-w-0 flex flex-wrap items-center gap-1.5 py-0.5">
    {#each mailboxes as mailbox, index (mailbox.email)}
      <span
        title={mailbox.email}
        class="h-7 max-w-full flex items-center gap-1.5 pl-1 pr-0.5 rounded-full border border-border font-medium"
      >
        <Avatar person={mailbox} size={20} text="text-xs" single />
        <span class="truncate">{chipLabel(mailbox)}</span>
        {#if keyed(mailbox.email)}
          <Lock
            class="w-3 h-3 shrink-0 text-done"
            role="img"
            aria-label="Has a key"
          />
        {/if}
        <button
          type="button"
          aria-label={`Remove ${chipLabel(mailbox)}`}
          onclick={() => remove(index)}
          class="hit-target w-5 h-5 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
        >
          <X class="w-3.5 h-3.5" />
        </button>
      </span>
    {/each}
    <input
      {id}
      bind:this={input}
      bind:value={text}
      type="text"
      autocomplete="off"
      spellcheck="false"
      role="combobox"
      aria-expanded={suggestions.length > 0}
      aria-controls={`${id}-list`}
      aria-autocomplete="list"
      aria-invalid={invalid}
      onkeydown={onKeydown}
      onpaste={onPaste}
      oninput={() => {
        highlight = 0;
        invalid = false;
      }}
      onfocus={() => {
        focused = true;
      }}
      onblur={() => {
        focused = false;
        commit();
      }}
      class={[
        "flex-1 min-w-12 h-7 bg-transparent text-[13px] text-text outline-none",
        invalid && "underline decoration-danger decoration-wavy",
      ]}
    >
  </div>
  {@render trailing?.()}
  {#if suggestions.length > 0}
    <div
      id={`${id}-list`}
      role="listbox"
      aria-label="Contacts"
      class="absolute z-40 top-full left-[52px] mt-1 w-[320px] p-1.5 rounded-xl border border-border bg-surface-raised shadow-pop"
    >
      {#each suggestions as contact, index (contact.email)}
        <div
          role="option"
          tabindex="-1"
          aria-selected={highlight === index}
          onmousedown={(event) => {
            event.preventDefault();
            pick(index);
          }}
          onmouseenter={() => {
            highlight = index;
          }}
          class={[
            "h-11 flex items-center gap-3 px-2 rounded-xl cursor-pointer",
            highlight === index && "bg-surface-muted",
          ]}
        >
          <Avatar
            person={{ name: contact.name, email: contact.email }}
            size={28}
            text="text-xs"
          />
          <span class="flex-1 min-w-0 leading-[18px]">
            {#if contact.name}
              <span class="block truncate text-sm font-medium"
                >{contact.name}</span
              >
            {/if}
            <span class="block truncate text-xs text-text-secondary"
              >{contact.email}</span
            >
          </span>
        </div>
      {/each}
    </div>
  {/if}
</div>
