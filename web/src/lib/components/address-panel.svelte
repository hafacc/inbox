<!-- @component The @hafa.cc address: pick a name (checked as you type) or take back the one this account had, follow setup, and finish by hand if Cloudflare's mail needs a click. -->
<script lang="ts">
  import Check from "@lucide/svelte/icons/check";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import type { Snippet } from "svelte";
  import { prefersReducedMotion } from "svelte/motion";
  import {
    checkAddress,
    claimAddress,
    formerAddress,
  } from "../address/address-calls";
  import {
    ADDRESS_DOMAIN,
    canRetry,
    checkName,
    REASON_TEXT,
    refusalText,
    setupErrorText,
    setupSteps,
    statusText,
  } from "../address/address-model";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { isNetworkError } from "../gmail/request";
  import { getMailState } from "../mail-state.svelte";

  let {
    beside,
  }: {
    /** more buttons beside Continue */
    beside?: Snippet;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const settings = hub.settings;

  let name = $state("");
  let touched = $state(false);
  let busy = $state(false);
  let error = $state<string | null>(null);
  let former = $state<string | null>(null);

  const check = $derived(checkName(name));
  const address = $derived(settings.address);
  const setup = $derived(settings.addressSetup);
  const active = $derived(setup?.status === "active");
  const steps = $derived(setupSteps(setup));
  const setupError = $derived(setup ? setupErrorText(setup) : null);
  const confirmByHand = $derived(setup?.error?.code === "confirm-by-hand");
  const retryable = $derived(!confirmByHand && canRetry(setup, mail.now));
  let retryButton = $state<HTMLButtonElement>();
  let pointedAt = $state(false);

  // Only the address this account had before it was deleted can be claimed, so offer just that.
  $effect(() => {
    if (address !== null) return;
    let current = true;
    formerAddress()
      .then((found) => {
        if (current) former = found;
      })
      .catch((failure: unknown) => {
        console.warn("former address lookup failed", failure);
      });
    return () => {
      current = false;
    };
  });

  // The inbox notice's "Fix it" lands here: bring Try again into view and mark it for a moment.
  $effect(() => {
    if (!settings.retryRequested || retryButton === undefined) return;
    settings.retryRequested = false;
    retryButton.scrollIntoView({
      block: "center",
      behavior: prefersReducedMotion.current ? "auto" : "smooth",
    });
    retryButton.focus({ preventScroll: true });
    pointedAt = true;
    const timer = setTimeout(() => {
      pointedAt = false;
    }, 2_500);
    return () => clearTimeout(timer);
  });

  function failureText(failure: unknown): string {
    const refused = refusalText(failure);
    if (refused !== null) return refused;
    if (isNetworkError(failure) || mail.offline) {
      return "You're offline. Try again once you're back.";
    }
    return "Something went wrong. Try again.";
  }

  async function claim(chosen: string | null = check.ok ? check.name : null) {
    touched = true;
    if (chosen === null || busy) return;
    busy = true;
    error = null;
    try {
      await claimAddress(chosen);
    } catch (failure) {
      console.error("claim failed", failure);
      error = failureText(failure);
    } finally {
      busy = false;
    }
  }

  async function checkAgain() {
    busy = true;
    error = null;
    try {
      await checkAddress();
    } catch (failure) {
      error = failureText(failure);
    } finally {
      busy = false;
    }
  }

  const card = "rounded-xl border border-border bg-surface p-5 shadow-card";
  const primary =
    "h-11 px-6 rounded-[10px] bg-accent text-on-accent text-[15px] font-semibold disabled:opacity-60";
</script>

{#if address === null && former !== null}
  {@const formerAddressText = former}
  <div class={card}>
    <p class="text-base font-semibold">{formerAddressText}</p>
    <p class="mt-2 text-sm leading-[22px] text-text-secondary">
      This account had this address before it was deleted. It is the only one it
      can have, and mail to it will land in {mail.email} again.
    </p>
    {#if error}
      <p role="alert" class="mt-3 text-sm text-danger">{error}</p>
    {/if}
    <div class="mt-5 flex items-center gap-3">
      <button
        type="button"
        disabled={busy}
        onclick={() =>
          void claim(formerAddressText.slice(0, -`@${ADDRESS_DOMAIN}`.length))}
        class={primary}
      >
        {busy ? "Claiming…" : `Take back ${formerAddressText}`}
      </button>
      {@render beside?.()}
    </div>
  </div>
{:else if address === null}
  <form
    class={card}
    onsubmit={(event) => {
      event.preventDefault();
      void claim();
    }}
  >
    <label
      for="address-name"
      class="block mb-2 text-[13px] font-semibold text-text-secondary"
      >Address</label
    >
    <div
      class="h-12 flex items-center rounded-[10px] border border-border-strong bg-surface overflow-hidden focus-within:border-focus focus-within:ring-[3px] focus-within:ring-focus/25"
    >
      <input
        id="address-name"
        bind:value={name}
        oninput={() => {
          touched = true;
          error = null;
        }}
        type="text"
        autocomplete="off"
        spellcheck="false"
        aria-describedby="address-status address-help"
        aria-invalid={touched && name !== "" && !check.ok}
        class="flex-1 min-w-0 h-full px-3.5 bg-transparent outline-none text-base"
      >
      <span
        class="h-full flex items-center px-3.5 bg-surface-muted border-l border-border text-base text-text-secondary"
        >@{ADDRESS_DOMAIN}</span
      >
    </div>
    <div
      id="address-status"
      role="status"
      class="mt-2.5 min-h-5 flex items-center gap-2 text-sm font-medium"
    >
      {#if error}
        <CircleAlert class="w-4 h-4 text-danger" aria-hidden="true" />
        <span class="text-danger">{error}</span>
      {:else if touched && name.trim() !== ""}
        {#if check.ok}
          <Check class="w-4 h-4 text-done" aria-hidden="true" />
          <span class="text-done"
            >{check.name}@{ADDRESS_DOMAIN}
            looks good</span
          >
        {:else}
          <CircleAlert class="w-4 h-4 text-danger" aria-hidden="true" />
          <span class="text-danger">{REASON_TEXT[check.problem]}</span>
        {/if}
      {/if}
    </div>
    <p
      id="address-help"
      class="mt-3 text-sm leading-[22px] text-text-secondary"
    >
      Mail to this address lands in {mail.email}. You can send from it here. It
      stays yours for good and can't be changed later.
    </p>
    <div class="mt-5 flex items-center gap-3">
      <button type="submit" disabled={busy || !check.ok} class={primary}>
        {busy ? "Claiming…" : "Continue"}
      </button>
      {@render beside?.()}
    </div>
  </form>
{:else}
  <div class={card}>
    <div class="flex items-center gap-3">
      <span class="flex-1 min-w-0 truncate text-base font-semibold"
        >{address}</span
      >
      {#if setup}
        <span class="text-[13px] text-text-secondary">
          {statusText(setup, address)}
        </span>
      {/if}
    </div>
    {#if !active}
      <ol class="mt-4 flex flex-col gap-2 rounded-xl bg-surface-muted p-4">
        {#each steps as step (step.label)}
          <li
            class={[
              "flex items-center gap-2.5",
              step.state === "waiting" && "text-text-secondary",
              step.state === "active" && "font-medium",
            ]}
          >
            <span class="w-5 h-5 shrink-0 flex items-center justify-center">
              {#if step.state === "done"}
                <span
                  class="w-5 h-5 rounded-full bg-done-soft text-done flex items-center justify-center"
                >
                  <Check class="w-3 h-3" strokeWidth={3} />
                </span>
              {:else if step.state === "active"}
                <span
                  class="w-4 h-4 rounded-full border-2 border-border-strong border-t-accent animate-spin"
                ></span>
              {:else}
                <span
                  class="w-2 h-2 rounded-full border-[1.5px] border-muted"
                ></span>
              {/if}
            </span>
            {step.label}
            <span class="sr-only">{step.state}</span>
          </li>
        {/each}
      </ol>
      {#if setupError}
        <p role="status" class="mt-3 text-sm text-text-secondary">
          {setupError}
        </p>
      {/if}
      {#if retryable && setupError === null}
        <p role="status" class="mt-3 text-sm text-text-secondary">
          Setup stopped partway. Try again to pick up where it left off.
        </p>
      {/if}
      {#if confirmByHand || retryable}
        <button
          bind:this={retryButton}
          type="button"
          disabled={busy}
          onclick={checkAgain}
          class={[
            "mt-3 h-10 px-5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong disabled:opacity-60 transition-[background-color,box-shadow] duration-200",
            pointedAt && "ring-4 ring-accent-soft",
          ]}
        >
          {busy ? "Checking…" : confirmByHand ? "Check again" : "Try again"}
        </button>
      {/if}
    {/if}

    {#if error}
      <p role="alert" class="mt-3 text-sm text-danger">{error}</p>
    {/if}
  </div>
{/if}
