<!-- @component The `?` list of keyboard shortcuts. -->
<script lang="ts">
  import X from "@lucide/svelte/icons/x";
  import { pushBack } from "../back-stack.svelte";
  import { getMailState } from "../mail-state.svelte";
  import ShortcutList from "./shortcut-list.svelte";

  const mail = getMailState();

  let { open = $bindable(false) }: { open?: boolean } = $props();

  let dialog: HTMLDialogElement;

  $effect(() => {
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  });

  // The browser's back closes the list.
  $effect(() => {
    if (!open) return;
    return pushBack(() => {
      open = false;
    });
  });
</script>

<!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click on the backdrop closes it; Esc already does natively -->
<dialog
  bind:this={dialog}
  aria-labelledby="keys-title"
  onclose={() => {
    open = false;
  }}
  onclick={(event) => {
    if (event.target === dialog) open = false;
  }}
  class="m-auto w-[880px] max-w-[calc(100vw-32px)] rounded-[24px] bg-surface text-text shadow-dialog px-6 pt-2 pb-6"
>
  <div class="h-14 flex items-center gap-3">
    <h2 id="keys-title" class="flex-1 text-lg font-semibold tracking-[-0.01em]">
      Keyboard shortcuts
    </h2>
    <button
      type="button"
      aria-label="Close (esc)"
      onclick={() => {
        open = false;
      }}
      class="hit-target -mr-2 w-9 h-9 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
    >
      <X class="w-[18px] h-[18px]" />
    </button>
  </div>
  <div class="mt-1">
    <ShortcutList />
  </div>
  <div class="mt-5 flex justify-end">
    <button
      type="button"
      onclick={() => {
        open = false;
        mail.setView({ kind: "settings", section: "shortcuts" });
      }}
      class="h-9 px-4 rounded-full text-[13px] font-semibold text-accent-strong hover:bg-surface-hover"
    >
      Search operators and shortcut settings
    </button>
  </div>
</dialog>
