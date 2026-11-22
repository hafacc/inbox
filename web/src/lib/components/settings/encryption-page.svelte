<!-- @component Settings › Encryption: turn it on for the @hafa.cc address, unlock this device, the passkeys that unlock mail, a new recovery phrase, and starting over with a new key. -->
<script lang="ts">
  import { formatListDate } from "@inbox/shared/time-format";
  import Fingerprint from "@lucide/svelte/icons/fingerprint";
  import Lock from "@lucide/svelte/icons/lock";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import { getMailState } from "../../mail-state.svelte";
  import {
    deviceLabel,
    isPasskeyCancel,
    MAX_PASSKEYS,
    PrfUnsupported,
    passkeysAvailable,
    type SealedCopy,
  } from "../../pgp/passkey";
  import EncryptionSetup from "../encryption/encryption-setup.svelte";
  import RecoveryPhrase from "../encryption/recovery-phrase.svelte";

  const mail = getMailState();
  const hub = getComposeHub();
  const encryption = mail.encryption;
  const address = $derived(hub.settings.activeAddress);
  const sealed = $derived(encryption.doc?.sealed ?? []);

  let setup = $state<"setup" | "reset" | null>(null);
  let newPhrase = $state<string | null>(null);
  let confirming = $state<string | null>(null);
  let busy = $state(false);
  let error = $state<string | null>(null);

  async function run(task: () => Promise<void>, failure: string) {
    busy = true;
    error = null;
    try {
      await task();
    } catch (problem) {
      if (problem instanceof PrfUnsupported) error = problem.message;
      else if (!isPasskeyCancel(problem)) {
        console.error(failure, problem);
        error = `${failure} Try again.`;
      }
    } finally {
      busy = false;
      confirming = null;
    }
  }

  function addPasskey() {
    void run(
      () =>
        encryption.addPasskey(
          { uid: mail.uid, gmail: mail.email, address: address ?? "" },
          deviceLabel(navigator.userAgent),
        ),
      "Couldn't add the passkey.",
    );
  }

  function removePasskey(entry: SealedCopy) {
    if (sealed.length === 1 && confirming !== entry.credentialId) {
      confirming = entry.credentialId;
      return;
    }
    void run(
      () => encryption.removePasskey(entry.credentialId),
      "Couldn't remove the passkey.",
    );
  }

  async function showNewPhrase() {
    newPhrase = await encryption.newPhrase();
  }

  async function saveNewPhrase() {
    if (newPhrase === null) return;
    await encryption.replacePhrase(newPhrase);
    newPhrase = null;
  }

  const card = "rounded-[22px] bg-surface shadow-card";
  const button =
    "h-9 flex items-center gap-2 px-3.5 rounded-full border border-border text-sm font-medium hover:bg-surface-hover disabled:opacity-50";
  const iconButton =
    "w-8 h-8 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text";
</script>

{#if setup !== null}
  <div class="fixed inset-0 z-50 overflow-auto bg-bg">
    <EncryptionSetup
      reset={setup === "reset"}
      onClose={() => {
        setup = null;
      }}
    />
  </div>
{/if}

<h1 class="h-16 flex items-center text-xl font-semibold tracking-[-0.01em]">
  Encryption
</h1>
<p class="mb-4 text-[13px] text-text-secondary">
  Mail you send from your @hafa.cc address is encrypted when every recipient has
  a key, and encrypted mail you get opens here.
</p>

{#if !encryption.ready}
  <div class={[card, "p-4 text-text-secondary"]}>Loading…</div>
{:else if encryption.doc === null}
  <section class={[card, "px-4 pt-3.5 pb-4"]}>
    <h2 class="text-sm font-semibold">Encrypted mail is off</h2>
    {#if hub.settings.address !== null && address === null}
      <p class="mt-1 text-[13px] text-text-secondary">
        You can turn it on once {hub.settings.address} is set up.
      </p>
    {:else if address === null}
      <p class="mt-1 text-[13px] text-text-secondary">
        It works for an @hafa.cc address. Claim one first.
      </p>
      <button
        type="button"
        onclick={() => mail.setView({ kind: "settings", section: "addresses" })}
        class={[button, "mt-3"]}
      >
        Go to Addresses
      </button>
    {:else}
      <p class="mt-1 text-[13px] text-text-secondary">
        Turn it on for {address}: a passkey unlocks your key on each device, and
        a recovery phrase unlocks it if you lose every passkey.
      </p>
      <button
        type="button"
        onclick={() => {
          setup = "setup";
        }}
        class="mt-3 h-9 flex items-center gap-2 px-3.5 rounded-full bg-accent text-on-accent text-sm font-semibold"
      >
        <Lock class="w-4 h-4" aria-hidden="true" />
        Turn on encrypted mail
      </button>
    {/if}
  </section>
{:else}
  <section aria-labelledby="enc-title" class={card}>
    <div class="h-12 flex items-center gap-3 px-4">
      <h2 id="enc-title" class="flex-1 text-sm font-semibold">Encryption</h2>
      {#if address !== null}
        <span
          class="h-6 flex items-center px-2.5 rounded-full bg-done-soft text-done text-xs font-medium"
          >On for {address}</span
        >
      {/if}
    </div>
    {#if !encryption.unlocked}
      <div
        class="mx-4 mb-3 flex flex-wrap items-center gap-3 px-3 py-2 rounded-[10px] bg-surface-muted text-[13px]"
      >
        <Lock class="w-4 h-4 text-text-secondary" aria-hidden="true" />
        <span class="flex-1 min-w-48"
          >This device hasn't unlocked encrypted mail yet.</span
        >
        <button
          type="button"
          onclick={encryption.requestUnlock}
          class="h-8 px-3 rounded-full bg-accent-soft text-accent-strong font-semibold"
        >
          Unlock
        </button>
      </div>
    {/if}
    <ul aria-label="Passkeys" class="px-4 border-t border-separator">
      {#each sealed as entry (entry.credentialId)}
        <li class="min-h-11 flex items-center gap-3 py-1">
          <Fingerprint
            class="w-[18px] h-[18px] text-text-secondary shrink-0"
            aria-hidden="true"
          />
          <span class="flex-1 min-w-0 truncate">
            <span class="font-medium">{entry.name}</span>
            {#if entry.addedAt > 0}
              <span class="text-text-secondary">
                · added {formatListDate(entry.addedAt, mail.now)}</span
              >
            {/if}
          </span>
          {#if confirming === entry.credentialId}
            <span class="text-[13px] text-danger"
              >Remove the only passkey?</span
            >
            <button
              type="button"
              disabled={busy}
              onclick={() => removePasskey(entry)}
              class="h-8 px-3 rounded-full bg-danger-soft text-danger text-[13px] font-semibold"
            >
              Remove
            </button>
            <button
              type="button"
              onclick={() => {
                confirming = null;
              }}
              class="h-8 px-3 rounded-full text-[13px] font-medium hover:bg-surface-hover"
            >
              Keep
            </button>
          {:else}
            <button
              type="button"
              aria-label={`Remove ${entry.name} passkey`}
              title="Remove"
              disabled={busy}
              onclick={() => removePasskey(entry)}
              class={iconButton}
            >
              <Trash2 class="w-4 h-4" />
            </button>
          {/if}
        </li>
      {:else}
        <li class="min-h-11 flex items-center text-[13px] text-text-secondary">
          No passkey unlocks your mail. Only your recovery phrase does.
        </li>
      {/each}
    </ul>
    {#if sealed.length === 1}
      <p
        class="mx-4 mb-3 flex items-center gap-2 px-3 py-2 rounded-[10px] bg-surface-muted text-[13px]"
      >
        <TriangleAlert
          class="w-4 h-4 text-text-secondary shrink-0"
          aria-hidden="true"
        />
        Only one passkey unlocks your mail. Keep your recovery phrase safe.
      </p>
    {/if}
    <div
      class="flex flex-wrap items-center gap-2 px-4 py-3 border-t border-separator"
    >
      <button
        type="button"
        disabled={busy ||
          !encryption.unlocked ||
          !passkeysAvailable() ||
          sealed.length >= MAX_PASSKEYS}
        title={encryption.unlocked ? undefined : "Unlock this device first"}
        onclick={addPasskey}
        class={button}
      >
        <Fingerprint class="w-4 h-4 text-text-secondary" aria-hidden="true" />
        Add a passkey
      </button>
      <span class="flex-1"></span>
      <button
        type="button"
        disabled={busy || !encryption.unlocked || newPhrase !== null}
        title={encryption.unlocked ? undefined : "Unlock this device first"}
        onclick={showNewPhrase}
        class={button}
      >
        New recovery phrase
      </button>
    </div>
    {#if error}
      <p role="alert" class="px-4 pb-3 text-[13px] text-danger">{error}</p>
    {/if}
  </section>

  {#if newPhrase !== null}
    <div class="mt-4">
      <p class="mb-2 text-[13px] text-text-secondary">
        Your old phrase stops working once you save this one.
      </p>
      <RecoveryPhrase
        phrase={newPhrase}
        finishLabel="Save new phrase"
        onFinish={saveNewPhrase}
      />
      <button
        type="button"
        onclick={() => {
          newPhrase = null;
        }}
        class="mt-2 h-9 px-3 rounded-full text-sm font-medium hover:bg-surface-hover"
      >
        Cancel
      </button>
    </div>
  {/if}

  <section class={[card, "mt-4 px-4 py-3.5"]}>
    <div class="flex flex-wrap items-center gap-3">
      <span class="flex-1 min-w-60">
        <span class="block text-sm font-semibold">Reset encryption</span>
        <span class="block text-[13px] text-text-secondary">
          For when every passkey and the phrase are lost: make a new key.
        </span>
      </span>
      {#if confirming === "reset"}
        <span class="w-full text-[13px] text-danger">
          Mail encrypted to your current key can't be opened again, here or
          anywhere.
        </span>
        <button
          type="button"
          onclick={() => {
            confirming = null;
            setup = "reset";
          }}
          class="h-9 px-3.5 rounded-full bg-danger-soft text-danger text-sm font-semibold"
        >
          Make a new key
        </button>
        <button
          type="button"
          onclick={() => {
            confirming = null;
          }}
          class="h-9 px-3.5 rounded-full text-sm font-medium hover:bg-surface-hover"
        >
          Cancel
        </button>
      {:else}
        <button
          type="button"
          disabled={busy || address === null}
          onclick={() => {
            confirming = "reset";
          }}
          class={button}
        >
          Reset…
        </button>
      {/if}
    </div>
  </section>
{/if}
