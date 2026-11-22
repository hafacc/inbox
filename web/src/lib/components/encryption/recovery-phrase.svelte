<!-- @component A new recovery phrase, shown once: the eight words numbered, Copy and Download, then two of them typed back before it is saved. -->
<script lang="ts">
  import Check from "@lucide/svelte/icons/check";
  import Copy from "@lucide/svelte/icons/copy";
  import Download from "@lucide/svelte/icons/download";
  import KeyRound from "@lucide/svelte/icons/key-round";
  import { untrack } from "svelte";
  import { confirmPositions, wordMatches } from "../../pgp/pgp-doc";

  let {
    phrase,
    finishLabel = "Finish",
    onFinish,
  }: {
    phrase: string;
    finishLabel?: string;
    // Saves the phrase's seal; rejects when that fails.
    onFinish: () => Promise<void>;
  } = $props();

  const id = $props.id();
  const words = $derived(phrase.split(" "));
  const positions = untrack(() => confirmPositions(phrase.split(" ").length));
  let typed = $state(["", ""]);
  let copied = $state(false);
  let busy = $state(false);
  let error = $state<string | null>(null);
  const confirmed = $derived(
    positions.every((position, index) =>
      wordMatches(typed[index] ?? "", words[position] ?? ""),
    ),
  );

  async function copy() {
    try {
      await navigator.clipboard.writeText(phrase);
      copied = true;
      setTimeout(() => {
        copied = false;
      }, 2_000);
    } catch {
      error = "Couldn't copy. Write the words down instead.";
    }
  }

  function download() {
    const text = `Recovery phrase for encrypted mail at inbox.hafa.cc\n\n${words
      .map((word, index) => `${index + 1}. ${word}`)
      .join("\n")}\n`;
    const url = URL.createObjectURL(new Blob([text], { type: "text/plain" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "inbox-recovery-phrase.txt";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  async function finish() {
    if (!confirmed || busy) return;
    busy = true;
    error = null;
    try {
      await onFinish();
    } catch (failure) {
      console.error("saving the recovery phrase failed", failure);
      error = "Couldn't save it. Check your connection and try again.";
    } finally {
      busy = false;
    }
  }

  const button =
    "h-9 flex items-center gap-2 px-3.5 rounded-full border border-border bg-surface text-sm font-medium hover:bg-surface-hover";
</script>

<section
  aria-labelledby={`${id}-title`}
  class="rounded-xl border border-border bg-surface p-5 shadow-pop"
>
  <div class="flex items-center gap-2.5">
    <KeyRound class="w-[18px] h-[18px] text-accent" aria-hidden="true" />
    <h2 id={`${id}-title`} class="text-base font-semibold">
      Your recovery phrase
    </h2>
  </div>
  <p class="mt-1.5 text-[13px] text-text-secondary">
    If you lose every passkey, these 8 words unlock your mail. This is the only
    time they're shown.
  </p>
  <ol
    class="mt-4 grid grid-cols-2 grid-rows-4 grid-flow-col gap-2 list-none p-0"
  >
    {#each words as word, index (index)}
      <li
        class="h-10 flex items-center gap-2.5 px-3 rounded-xl bg-surface-muted"
      >
        <span class="w-4 text-[13px] text-text-secondary tabular-nums"
          >{index + 1}</span
        >
        <span class="text-[15px] font-medium">{word}</span>
      </li>
    {/each}
  </ol>
  <div class="mt-3 flex gap-2">
    <button type="button" class={button} onclick={copy}>
      {#if copied}
        <Check class="w-4 h-4 text-done" aria-hidden="true" />
        Copied
      {:else}
        <Copy class="w-4 h-4 text-text-secondary" aria-hidden="true" />
        Copy
      {/if}
    </button>
    <button type="button" class={button} onclick={download}>
      <Download class="w-4 h-4 text-text-secondary" aria-hidden="true" />
      Download
    </button>
  </div>
  <div class="h-px bg-separator my-4"></div>
  <p class="text-sm">To check you saved it, type two of the words.</p>
  <div class="mt-3 grid grid-cols-2 gap-3">
    {#each positions as position, index (position)}
      <label class="flex flex-col gap-1 text-[13px] text-text-secondary">
        Word {position + 1}
        <input
          type="text"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          bind:value={typed[index]}
          onkeydown={(event) => {
            if (event.key === "Enter") void finish();
          }}
          class="h-10 px-3 rounded-xl border border-border bg-surface text-[15px] text-text outline-none focus:border-focus"
        >
      </label>
    {/each}
  </div>
  {#if error}
    <p role="alert" class="mt-3 text-[13px] text-danger">{error}</p>
  {/if}
  <div class="mt-4 flex justify-end">
    <button
      type="button"
      disabled={!confirmed || busy}
      onclick={finish}
      class="h-11 px-7 rounded-full bg-accent text-on-accent text-[15px] font-semibold disabled:bg-surface-muted disabled:text-text-secondary"
    >
      {busy ? "Saving…" : finishLabel}
    </button>
  </div>
</section>
