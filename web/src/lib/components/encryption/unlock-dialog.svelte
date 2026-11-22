<!-- @component Unlocking encrypted mail on this device: with a passkey, or with the recovery phrase and then a passkey for this device. -->
<script lang="ts">
  import type { ParsedPhrase } from "@inbox/shared/pgp";
  import Fingerprint from "@lucide/svelte/icons/fingerprint";
  import X from "@lucide/svelte/icons/x";
  import { pushBack } from "../../back-stack.svelte";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import { getMailState } from "../../mail-state.svelte";
  import { loadPgp } from "../../pgp/load";
  import {
    deviceLabel,
    isPasskeyCancel,
    PrfUnsupported,
    passkeysAvailable,
  } from "../../pgp/passkey";

  let { open = $bindable(false) }: { open?: boolean } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const encryption = mail.encryption;
  let dialog: HTMLDialogElement;
  let phrase = $state("");
  let parse = $state.raw<((text: string) => ParsedPhrase) | null>(null);
  let busy = $state<"passkey" | "phrase" | "add" | null>(null);
  let error = $state<string | null>(null);
  // After the phrase worked: offer this device a passkey of its own.
  let offerPasskey = $state(false);
  const parsed = $derived(parse === null ? null : parse(phrase));
  const unknown = $derived(
    parsed === null
      ? []
      : parsed.unknown
          .filter(
            (index) => index < parsed.words.length - 1 || /\s$/.test(phrase),
          )
          .map((index) => parsed.words[index] ?? ""),
  );
  const canPasskey = $derived(
    passkeysAvailable() && (encryption.doc?.sealed.length ?? 0) > 0,
  );

  $effect(() => {
    if (open && !dialog.open) {
      dialog.showModal();
      void loadPgp().then((pgp) => {
        parse = pgp.parsePhrase;
      });
    } else if (!open && dialog.open) dialog.close();
  });

  // The browser's back closes it, unless an unlock is under way.
  $effect(() => {
    if (!open) return;
    return pushBack(() => {
      if (busy === null) close();
    });
  });

  function close() {
    open = false;
    phrase = "";
    error = null;
    offerPasskey = false;
  }

  async function withPasskey() {
    busy = "passkey";
    error = null;
    try {
      await encryption.unlockWithPasskey();
      close();
    } catch (failure) {
      if (failure instanceof PrfUnsupported) {
        error =
          "That passkey can't unlock mail here. Use your recovery phrase instead.";
      } else if (!isPasskeyCancel(failure)) {
        console.error("passkey unlock failed", failure);
        error =
          "Couldn't unlock with a passkey. Use your recovery phrase instead.";
      }
    } finally {
      busy = null;
    }
  }

  async function withPhrase() {
    if (parsed?.normalized == null || busy !== null) return;
    busy = "phrase";
    error = null;
    try {
      await encryption.unlockWithPhrase(parsed.normalized);
      phrase = "";
      offerPasskey = passkeysAvailable();
      if (!offerPasskey) close();
    } catch (failure) {
      if ((failure as Error).name === "SealError") {
        error = "That phrase doesn't unlock this account's key.";
      } else {
        console.error("phrase unlock failed", failure);
        error = "Couldn't unlock. Try again.";
      }
    } finally {
      busy = null;
    }
  }

  async function addPasskey() {
    const address = hub.settings.address ?? "";
    busy = "add";
    error = null;
    try {
      await encryption.addPasskey(
        { uid: mail.uid, gmail: mail.email, address },
        deviceLabel(navigator.userAgent),
      );
      close();
    } catch (failure) {
      if (failure instanceof PrfUnsupported) error = failure.message;
      else if (!isPasskeyCancel(failure)) {
        console.error("adding a passkey failed", failure);
        error = "Couldn't add the passkey. Try again from Settings.";
      }
    } finally {
      busy = null;
    }
  }
</script>

<!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click on the backdrop closes it; Esc already does natively -->
<dialog
  bind:this={dialog}
  aria-labelledby="unlock-title"
  onclose={close}
  onclick={(event) => {
    if (event.target === dialog && busy === null) close();
  }}
  class="m-auto w-[480px] max-w-[calc(100vw-32px)] rounded-2xl border border-border bg-surface text-text shadow-dialog px-6 pt-2 pb-6"
>
  <div class="h-14 flex items-center gap-3">
    <h2
      id="unlock-title"
      class="flex-1 text-lg font-semibold tracking-[-0.01em]"
    >
      {offerPasskey ? "Mail is unlocked" : "Unlock encrypted mail"}
    </h2>
    <button
      type="button"
      aria-label="Close (esc)"
      onclick={close}
      class="-mr-2 w-9 h-9 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
    >
      <X class="w-[18px] h-[18px]" />
    </button>
  </div>
  {#if offerPasskey}
    <p class="text-sm text-text-secondary">
      Add a passkey for this device, so next time your fingerprint or screen
      lock unlocks mail here.
    </p>
    {#if error}
      <p role="alert" class="mt-3 text-[13px] text-danger">{error}</p>
    {/if}
    <div class="mt-5 flex justify-end gap-2">
      <button
        type="button"
        onclick={close}
        class="h-10 px-4 rounded-full font-medium hover:bg-surface-hover"
      >
        Not now
      </button>
      <button
        type="button"
        disabled={busy !== null}
        onclick={addPasskey}
        class="h-10 flex items-center gap-2 px-4 rounded-full bg-accent text-on-accent font-semibold disabled:opacity-60"
      >
        <Fingerprint class="w-4 h-4" aria-hidden="true" />
        {busy === "add" ? "Adding…" : "Add a passkey"}
      </button>
    </div>
  {:else}
    <p class="text-sm text-text-secondary">
      Your key is stored locked. Unlock it once on this device to read and send
      encrypted mail.
    </p>
    {#if canPasskey}
      <button
        type="button"
        disabled={busy !== null}
        onclick={withPasskey}
        class="mt-5 w-full h-11 flex items-center justify-center gap-2.5 rounded-full bg-accent text-on-accent text-[15px] font-semibold disabled:opacity-60"
      >
        <Fingerprint class="w-5 h-5" aria-hidden="true" />
        {busy === "passkey" ? "Waiting for your passkey…" : "Use a passkey"}
      </button>
      <div class="my-5 flex items-center gap-3 text-[13px] text-text-secondary">
        <span class="flex-1 h-px bg-separator"></span>
        or your recovery phrase
        <span class="flex-1 h-px bg-separator"></span>
      </div>
    {:else}
      <p class="mt-4 text-sm">Type your recovery phrase.</p>
    {/if}
    <label for="unlock-phrase" class="sr-only">Recovery phrase</label>
    <textarea
      id="unlock-phrase"
      bind:value={phrase}
      rows="3"
      autocomplete="off"
      autocapitalize="off"
      spellcheck="false"
      placeholder="8 words, separated by spaces"
      onkeydown={(event) => {
        if (event.key === "Enter") {
          event.preventDefault();
          void withPhrase();
        }
      }}
      class="w-full mt-2 px-3 py-2 rounded-xl border border-border bg-surface text-[15px] leading-6 outline-none focus:border-focus resize-none"
    ></textarea>
    {#if unknown.length > 0}
      <p class="mt-1 text-[13px] text-danger">
        Not on the word list: {unknown.join(", ")}
      </p>
    {/if}
    {#if error}
      <p role="alert" class="mt-3 text-[13px] text-danger">{error}</p>
    {/if}
    <div class="mt-4 flex justify-end">
      <button
        type="button"
        disabled={parsed?.normalized == null || busy !== null}
        onclick={withPhrase}
        class="h-10 px-5 rounded-full border border-border font-semibold hover:bg-surface-hover disabled:opacity-50"
      >
        {busy === "phrase" ? "Unlocking…" : "Unlock"}
      </button>
    </div>
  {/if}
</dialog>
