<!-- @component A draft in an open thread, or a message on its way: a row of the thread's messages that opens the draft in the composer, its word lined up with the senders' names. -->
<script lang="ts">
  import Pencil from "@lucide/svelte/icons/pencil";

  let {
    snippet,
    sending,
    onOpen,
  }: {
    /** the body's first words */
    snippet: string;
    /** whether it is being sent rather than kept as a draft */
    sending: boolean;
    /** open it in the composer */
    onOpen: () => void;
  } = $props();

  // At phone width it is laid out as a message's header: 16 in, 12 above and below, past a 36 avatar.
  const ROW =
    "w-full h-12 flex items-center gap-3 px-3 sm:px-6 border-t border-separator max-sm:h-auto max-sm:min-h-12 max-sm:px-4 max-sm:py-3";
  const SPACE = "w-[28px] max-sm:w-9 shrink-0";
  const WORD =
    "w-28 sm:w-40 max-sm:w-auto shrink-0 font-semibold truncate max-sm:text-sm";
</script>

{#if sending}
  <div class={ROW} role="status">
    <span class={SPACE}></span>
    <span class={[WORD, "text-text-secondary"]}>Sending…</span>
    <span class="flex-1 min-w-0 truncate text-text-secondary max-sm:text-[13px]"
      >{snippet}</span
    >
  </div>
{:else}
  <button
    type="button"
    onclick={onOpen}
    aria-label={`Edit draft${snippet ? `: ${snippet}` : ""}`}
    class={[ROW, "text-left hover:bg-surface-muted transition-colors"]}
  >
    <span class={SPACE}></span>
    <span class={[WORD, "text-snooze"]}>Draft</span>
    <span class="flex-1 min-w-0 truncate text-text-secondary max-sm:text-[13px]"
      >{snippet || "(empty)"}</span
    >
    <Pencil
      class="w-4 h-4 max-sm:w-[18px] max-sm:h-[18px] shrink-0 text-text-secondary"
      aria-hidden="true"
    />
  </button>
{/if}
