<!-- @component A draft in an open thread, or a message on its way: one line that opens the draft in the composer; at phone width a card of its own. -->
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

  const ROW = "w-full h-12 flex items-center gap-3 px-3 sm:px-6";
  // On a phone it is a card of its own between the thread's runs of messages, as on Android.
  const PHONE_CARD =
    "max-sm:mx-3 max-sm:mt-2 max-sm:w-[calc(100%-1.5rem)] max-sm:h-auto max-sm:min-h-12 max-sm:px-4 max-sm:py-3 max-sm:rounded-xl max-sm:border max-sm:border-border max-sm:bg-surface";
</script>

{#if sending}
  <div class={[ROW, "border-t border-separator", PHONE_CARD]} role="status">
    <span class="w-[28px] shrink-0 max-sm:hidden"></span>
    <span class="shrink-0 font-semibold text-text-secondary max-sm:text-sm"
      >Sending…</span
    >
    <span class="flex-1 min-w-0 truncate text-text-secondary max-sm:text-[13px]"
      >{snippet}</span
    >
  </div>
{:else}
  <button
    type="button"
    onclick={onOpen}
    aria-label={`Edit draft${snippet ? `: ${snippet}` : ""}`}
    class={[
      ROW,
      "border-t border-separator text-left hover:bg-surface-muted transition-colors",
      PHONE_CARD,
    ]}
  >
    <span class="w-[28px] shrink-0 max-sm:hidden"></span>
    <span class="shrink-0 font-semibold text-snooze max-sm:text-sm">Draft</span>
    <span class="flex-1 min-w-0 truncate text-text-secondary max-sm:text-[13px]"
      >{snippet || "(empty)"}</span
    >
    <Pencil
      class="w-4 h-4 max-sm:w-[18px] max-sm:h-[18px] shrink-0 text-text-secondary"
      aria-hidden="true"
    />
  </button>
{/if}
