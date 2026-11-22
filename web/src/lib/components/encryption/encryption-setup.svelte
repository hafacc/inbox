<!-- @component Turning on encrypted mail (or starting over with a new key): make the key pair, create a passkey that unlocks it, then show the recovery phrase once and check it was saved. -->
<script lang="ts">
  import type { KeyPair } from "@inbox/shared/pgp";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import Check from "@lucide/svelte/icons/check";
  import Fingerprint from "@lucide/svelte/icons/fingerprint";
  import Inbox from "@lucide/svelte/icons/inbox";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import { getMailState } from "../../mail-state.svelte";
  import {
    deviceLabel,
    isPasskeyCancel,
    PrfUnsupported,
    passkeysAvailable,
    type SealedCopy,
  } from "../../pgp/passkey";
  import RecoveryPhrase from "./recovery-phrase.svelte";

  let {
    reset = false,
    onClose,
  }: {
    // start over with a new key, after "Reset encryption"
    reset?: boolean;
    onClose: () => void;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const encryption = mail.encryption;
  const address = $derived(hub.settings.activeAddress ?? "");
  const name = $derived(
    hub.fromOptions.find((option) => option.mailbox.email === address)?.mailbox
      .name ?? "",
  );

  let pair: KeyPair | null = null;
  let sealed = $state.raw<SealedCopy | null>(null);
  let phrase = $state<string | null>(null);
  let busy = $state(false);
  let error = $state<string | null>(null);
  let noPrf = $state(false);
  const step = $derived(phrase === null ? 1 : 2);

  async function keyPair(): Promise<KeyPair> {
    pair ??= await encryption.makeKeyPair(address, name);
    return pair;
  }

  async function createPasskey() {
    if (busy) return;
    busy = true;
    error = null;
    try {
      const keys = await keyPair();
      sealed = await encryption.sealForNewPasskey(
        keys.privateKey,
        { uid: mail.uid, gmail: mail.email, address },
        deviceLabel(navigator.userAgent),
      );
      phrase = await encryption.newPhrase();
    } catch (failure) {
      if (failure instanceof PrfUnsupported) {
        noPrf = true;
        error = failure.message;
      } else if (!isPasskeyCancel(failure)) {
        console.error("passkey setup failed", failure);
        error = "Couldn't create the passkey. Try again.";
      }
    } finally {
      busy = false;
    }
  }

  async function phraseOnly() {
    await keyPair();
    error = null;
    phrase = await encryption.newPhrase();
  }

  async function finish() {
    if (pair === null || phrase === null) return;
    await encryption.finishSetup(pair, sealed === null ? [] : [sealed], phrase);
    onClose();
  }
</script>

<div class="min-h-dvh flex flex-col bg-bg">
  <header class="h-16 shrink-0 flex items-center gap-3 px-6">
    <span
      class="w-7 h-7 rounded-full bg-accent text-on-accent flex items-center justify-center"
      aria-hidden="true"
    >
      <Inbox class="w-4 h-4" />
    </span>
    <span class="flex-1 text-base font-semibold tracking-[-0.01em]">Inbox</span>
    <span class="text-[13px] text-text-secondary">{address}</span>
  </header>
  <main class="flex-1 flex justify-center px-4 pt-16 pb-16">
    <div class="w-[880px] max-w-full">
      <button
        type="button"
        onclick={onClose}
        class="-ml-3 mb-4 h-9 inline-flex items-center gap-2 px-3 rounded-full text-[13px] font-medium text-text-secondary hover:bg-surface-hover"
      >
        <ArrowLeft class="w-4 h-4" aria-hidden="true" />
        Back to settings
      </button>
      <div class="flex items-center gap-3">
        <div
          role="img"
          aria-label={`Step ${step} of 2`}
          class="flex items-center gap-1.5"
        >
          {#each [1, 2] as dot (dot)}
            <span
              class={[
                "h-2 rounded bg-accent",
                dot === step ? "w-5" : "w-2",
                dot > step && "opacity-30",
              ]}
            ></span>
          {/each}
        </div>
        <span class="text-[13px] text-text-secondary">Step {step} of 2</span>
      </div>
      <h1 class="mt-4 text-[28px] leading-9 font-semibold tracking-[-0.02em]">
        {reset ? "Make a new key" : "Turn on encrypted mail"}
      </h1>
      <div
        class="mt-7 grid gap-8 items-start md:grid-cols-[minmax(0,1fr)_440px]"
      >
        <div>
          <p class="text-[15px] leading-6">
            Mail you send from {address} is encrypted on its own when every
            recipient has a key.
          </p>
          <p class="mt-3 text-[15px] leading-6 text-text-secondary">
            Your private key is stored locked on our server. A passkey unlocks
            it on this device with your fingerprint or screen lock.
          </p>
          {#if sealed === null}
            <button
              type="button"
              disabled={busy || phrase !== null || !passkeysAvailable()}
              onclick={createPasskey}
              class="mt-6 h-11 flex items-center gap-2.5 pl-4 pr-5 rounded-full bg-accent text-on-accent text-[15px] font-semibold disabled:bg-surface-muted disabled:text-text-secondary"
            >
              <Fingerprint class="w-5 h-5" aria-hidden="true" />
              {busy ? "Creating…" : "Create passkey"}
            </button>
          {:else}
            <div
              role="status"
              class="mt-6 flex items-center gap-3 px-3.5 py-3 rounded-xl bg-done-soft text-done"
            >
              <span
                class="w-6 h-6 shrink-0 rounded-full bg-done text-on-accent flex items-center justify-center"
              >
                <Check class="w-3.5 h-3.5" aria-hidden="true" />
              </span>
              <span class="font-medium">Passkey saved</span>
            </div>
          {/if}
          {#if error}
            <p role="alert" class="mt-3 text-[13px] text-danger">{error}</p>
          {/if}
          {#if (noPrf || !passkeysAvailable()) && phrase === null}
            <p class="mt-3 text-[13px] text-text-secondary">
              You can turn it on with only a recovery phrase, and add a passkey
              later from Settings.
            </p>
            <button
              type="button"
              onclick={phraseOnly}
              class="mt-2 h-9 px-3.5 rounded-full border border-border text-sm font-medium hover:bg-surface-hover"
            >
              Use only a recovery phrase
            </button>
          {/if}
        </div>
        {#if phrase !== null}
          <RecoveryPhrase {phrase} onFinish={finish} />
        {/if}
      </div>
    </div>
  </main>
</div>
