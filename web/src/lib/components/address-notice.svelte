<!-- @component A line above the inbox while the @hafa.cc address is being set up: calm while it works, and once it needs the user, the reason and a link to Try again in Settings › Addresses; closing that hides it until the problem changes. -->
<script lang="ts">
  import AtSign from "@lucide/svelte/icons/at-sign";
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import X from "@lucide/svelte/icons/x";
  import { addressNoticeText, noticeState } from "../address/address-model";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";

  const mail = getMailState();
  const hub = getComposeHub();
  const text = $derived(addressNoticeText(hub.settings.addressSetup, mail.now));
  const notice = $derived(noticeState(hub.settings.addressSetup, mail.now));

  function fix() {
    hub.settings.retryRequested = true;
    mail.setView({ kind: "settings", section: "addresses" });
  }
</script>

{#if notice === "working"}
  <div
    role="status"
    class="mb-4 flex items-center gap-3 rounded-[14px] bg-surface-muted text-text-secondary px-4 py-2.5 text-sm animate-fade-in"
  >
    <LoaderCircle
      class="w-4 h-4 shrink-0 animate-spin motion-reduce:animate-none"
      aria-hidden="true"
    />
    <p class="flex-1 min-w-0">
      Setting up {hub.settings.address ?? "your @hafa.cc address"}… this can
      take a few minutes.
    </p>
  </div>
{/if}

{#if notice === "error" &&
  text !== null &&
  hub.settings.dismissedNotice !== text}
  <div
    role="status"
    class="mb-4 flex items-center gap-3 rounded-[14px] bg-snooze-soft text-on-snooze-soft pl-4 pr-1.5 py-2 text-sm animate-fade-in"
  >
    <AtSign class="w-4 h-4 shrink-0" aria-hidden="true" />
    <p class="flex-1 min-w-0">Your @hafa.cc address isn't set up. {text}</p>
    <button
      type="button"
      onclick={fix}
      class="shrink-0 h-8 px-3 rounded-full font-semibold hover:bg-[color-mix(in_srgb,var(--color-on-snooze-soft)_12%,transparent)] transition-colors"
    >
      Fix it
    </button>
    <button
      type="button"
      onclick={() => {
        hub.settings.dismissedNotice = text;
      }}
      aria-label="Hide until something changes"
      class="shrink-0 w-8 h-8 flex items-center justify-center rounded-full hover:bg-[color-mix(in_srgb,var(--color-on-snooze-soft)_12%,transparent)] transition-colors"
    >
      <X class="w-4 h-4" aria-hidden="true" />
    </button>
  </div>
{/if}
