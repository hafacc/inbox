<!-- @component The composer's From address and the menu of addresses to send as. -->
<script lang="ts">
  import { addressKey } from "@inbox/shared/compose";
  import Check from "@lucide/svelte/icons/check";
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { ROUTE_TEXT } from "../compose/send-as";
  import Avatar from "./avatar.svelte";
  import { closeMenu } from "./menu-motion";
  import { popover } from "./popover";

  let {
    value = $bindable(),
    onChange,
    tone = "accent",
  }: {
    value: string;
    onChange: () => void;
    tone?: "accent" | "muted";
  } = $props();

  const hub = getComposeHub();
  let open = $state(false);

  function choose(email: string) {
    open = false;
    if (addressKey(email) === addressKey(value)) return;
    value = email;
    onChange();
  }
</script>

<div class="relative">
  <button
    type="button"
    aria-haspopup="menu"
    aria-expanded={open}
    aria-label={`From: ${value}`}
    disabled={hub.fromOptions.length < 2}
    onclick={() => {
      open = !open;
    }}
    class={[
      "h-7 flex items-center gap-1 pl-2 pr-1.5 rounded-full text-[13px] font-medium text-text disabled:pr-2 disabled:cursor-default",
      tone === "accent" ? "bg-accent-soft" : "bg-surface-muted",
    ]}
  >
    {value}
    {#if hub.fromOptions.length > 1}
      <ChevronDown class="w-3.5 h-3.5 text-text-secondary" aria-hidden="true" />
    {/if}
  </button>
  {#if open}
    <div
      out:closeMenu
      {@attach popover({
        onClose: () => {
          open = false;
        },
      })}
      role="menu"
      aria-label="Send as"
      class="absolute z-40 top-full left-0 mt-1 w-[340px] p-2 rounded-[22px] bg-surface-raised shadow-pop animate-rise flex flex-col gap-0.5"
    >
      <div
        class="h-8 flex items-center px-2.5 text-[13px] font-semibold text-text-secondary"
      >
        Send as
      </div>
      {#each hub.fromOptions as option (option.mailbox.email)}
        {@const checked =
          addressKey(option.mailbox.email) === addressKey(value)}
        <button
          type="button"
          role="menuitemradio"
          aria-checked={checked}
          onclick={() => choose(option.mailbox.email)}
          class={[
            "w-full min-h-14 flex items-center gap-3 px-2.5 py-2 rounded-2xl text-left transition-colors",
            checked ? "bg-accent-soft" : "hover:bg-surface-hover",
          ]}
        >
          <Avatar person={option.mailbox} size={32} text="text-xs" />
          <span class="flex-1 min-w-0 leading-[18px]">
            <span class="flex items-center gap-1.5 min-w-0">
              <span class="truncate text-sm font-medium"
                >{option.mailbox.email}</span
              >
              {#if option.route === "hafa"}
                <span
                  class="shrink-0 h-[18px] px-1.5 rounded-full bg-accent text-on-accent text-[11px] font-semibold leading-[18px]"
                  >hafa.cc</span
                >
              {/if}
            </span>
            <span class="block truncate text-xs text-text-secondary">
              {option.mailbox.name
                ? `${option.mailbox.name} · `
                : ""}{ROUTE_TEXT[option.route]}
            </span>
          </span>
          <span
            class={[
              "w-5 h-5 shrink-0 rounded-full flex items-center justify-center transition-colors",
              checked
                ? "bg-accent text-on-accent"
                : "border border-border-strong",
            ]}
          >
            {#if checked}
              <Check class="w-3 h-3" aria-hidden="true" />
            {/if}
          </span>
        </button>
      {/each}
    </div>
  {/if}
</div>
