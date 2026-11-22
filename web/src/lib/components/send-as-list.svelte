<!-- @component The addresses compose can send from: the @hafa.cc address once it works, the Gmail address and Gmail's send-as addresses, with a link to change them in Gmail. -->
<script lang="ts">
  import { addressKey } from "@inbox/shared/compose";
  import ExternalLink from "@lucide/svelte/icons/external-link";
  import RefreshCw from "@lucide/svelte/icons/refresh-cw";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { ROUTE_TEXT } from "../compose/send-as";

  const hub = getComposeHub();
  // Gmail send-as addresses still waiting on their confirmation can't send yet.
  const unconfirmed = $derived(
    hub.sendAs.filter(
      (entry) => !entry.isPrimary && entry.verificationStatus === "pending",
    ),
  );
  let refreshing = $state(false);

  async function refresh() {
    refreshing = true;
    try {
      await hub.reloadSendAs();
    } finally {
      refreshing = false;
    }
  }
</script>

<section class="mt-10">
  <div class="flex items-center gap-2">
    <h2 class="flex-1 text-base font-semibold">Send mail as</h2>
    <button
      type="button"
      onclick={refresh}
      disabled={refreshing}
      aria-label="Check Gmail for changes"
      title="Check Gmail for changes"
      class="w-9 h-9 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text disabled:opacity-60 transition-colors"
    >
      <RefreshCw
        class={[
          "w-4 h-4",
          refreshing && "animate-spin motion-reduce:animate-none",
        ]}
        aria-hidden="true"
      />
    </button>
  </div>
  <p class="mb-4 text-[13px] text-text-secondary">
    Compose offers these in From. Your @hafa.cc address joins them once mail to
    it arrives.
  </p>
  <ul
    class="flex flex-col rounded-xl border border-border bg-surface shadow-card divide-y divide-border"
  >
    {#each hub.fromOptions as option (addressKey(option.mailbox.email))}
      <li class="flex items-center gap-3 px-5 py-3">
        <div class="flex-1 min-w-0">
          <div class="truncate text-sm font-medium">
            {option.mailbox.email}
          </div>
          <div class="truncate text-[13px] text-text-secondary">
            {option.mailbox.name ? `${option.mailbox.name} · ` : ""}{ROUTE_TEXT[
              option.route
            ]}
          </div>
        </div>
        {#if hub.defaultFrom &&
          addressKey(hub.defaultFrom.mailbox.email) ===
            addressKey(option.mailbox.email)}
          <span
            class="shrink-0 h-6 px-2.5 flex items-center rounded-full bg-accent-soft text-accent-strong text-xs font-semibold"
          >
            Default
          </span>
        {/if}
      </li>
    {/each}
    {#each unconfirmed as entry (addressKey(entry.sendAsEmail))}
      <li class="flex items-center gap-3 px-5 py-3 text-text-secondary">
        <div class="flex-1 min-w-0">
          <div class="truncate text-sm">{entry.sendAsEmail}</div>
          <div class="truncate text-[13px]">
            Waiting for its confirmation in Gmail
          </div>
        </div>
      </li>
    {/each}
  </ul>
  <a
    href="https://mail.google.com/mail/u/0/#settings/accounts"
    target="_blank"
    rel="noopener noreferrer"
    class="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-accent-strong hover:underline"
  >
    Add or change these in Gmail's settings
    <ExternalLink class="w-3.5 h-3.5" aria-hidden="true" />
  </a>
</section>
