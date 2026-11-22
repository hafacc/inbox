<!-- @component The first screen after sign-in for an account without an @hafa.cc address: pick one and watch it set up, or skip for now. -->
<script lang="ts">
  import Inbox from "@lucide/svelte/icons/inbox";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";
  import AddressPanel from "./address-panel.svelte";
  import Avatar from "./avatar.svelte";

  let { onClose }: { onClose: () => void } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const setup = $derived(hub.settings.addressSetup);
  const step = $derived(hub.settings.address === null ? 1 : 2);
  const active = $derived(setup?.status === "active");
  const TITLES = ["Pick your address", "Setting it up"];
</script>

<div class="min-h-dvh flex flex-col bg-bg">
  <header class="h-16 shrink-0 flex items-center gap-3 px-6">
    <span
      class="w-7 h-7 rounded-lg bg-accent text-on-accent flex items-center justify-center"
      aria-hidden="true"
    >
      <Inbox class="w-4 h-4" />
    </span>
    <span class="flex-1 text-base font-semibold tracking-[-0.01em]">Inbox</span>
    <Avatar person={{ name: "", email: mail.email }} />
    <span class="text-[13px] text-text-secondary">{mail.email}</span>
  </header>
  <main class="flex-1 flex justify-center px-4 pt-[72px] pb-16">
    <div class="w-[520px] max-w-full">
      <div class="flex items-center gap-3">
        <div
          role="img"
          aria-label={`Step ${step} of 2`}
          class="flex items-center gap-1.5"
        >
          {#each [1, 2] as dot (dot)}
            <span
              class={[
                "h-2 rounded",
                dot === step ? "w-5 bg-accent" : "w-2 bg-border-strong",
              ]}
            ></span>
          {/each}
        </div>
        <span class="text-[13px] text-text-secondary">Step {step} of 2</span>
      </div>
      <h1 class="mt-4 text-[28px] leading-9 font-semibold tracking-[-0.02em]">
        {active ? "All set" : TITLES[step - 1]}
      </h1>
      <div class="mt-6">
        <AddressPanel>
          {#snippet beside()}
            <button
              type="button"
              onclick={onClose}
              class="h-11 px-4 rounded-[10px] text-[15px] font-medium hover:bg-surface-hover"
            >
              Skip for now
            </button>
          {/snippet}
        </AddressPanel>
      </div>
      {#if step > 1}
        <div class="mt-6">
          <button
            type="button"
            onclick={onClose}
            class="h-11 px-4 -ml-4 rounded-[10px] text-[15px] font-medium hover:bg-surface-hover"
          >
            {active ? "Go to Inbox" : "Continue in the background"}
          </button>
        </div>
      {/if}
    </div>
  </main>
</div>
