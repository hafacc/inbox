<!-- @component The question sign-out asks while changes or drafts haven't reached Gmail: what would be lost, Cancel, and Sign out. -->
<script lang="ts">
  import { pushBack } from "../back-stack.svelte";

  let {
    warning,
    onCancel,
    onSignOut,
  }: {
    /** what hasn't synced yet, as a sentence */
    warning: string;
    onCancel: () => void;
    onSignOut: () => void;
  } = $props();

  let dialog: HTMLDialogElement;
  let release: (() => void) | null = null;

  $effect(() => {
    dialog.showModal();
    release = pushBack(onCancel);
    return () => release?.();
  });
</script>

<!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click on the backdrop cancels; Esc already does natively -->
<dialog
  bind:this={dialog}
  aria-labelledby="sign-out-title"
  aria-describedby="sign-out-warning"
  oncancel={(event) => {
    event.preventDefault();
    onCancel();
  }}
  onclick={(event) => {
    if (event.target === dialog) onCancel();
  }}
  class="m-auto w-[400px] max-w-[calc(100vw-32px)] rounded-[28px] bg-surface text-text shadow-dialog p-6"
>
  <h2 id="sign-out-title" class="text-lg font-semibold tracking-[-0.01em]">
    Sign out anyway?
  </h2>
  <p id="sign-out-warning" class="mt-2 text-sm text-text-secondary">
    {warning}
  </p>
  <div class="mt-6 flex justify-end gap-2">
    <button
      type="button"
      onclick={onCancel}
      class="h-12 px-5 rounded-full text-sm font-semibold hover:bg-surface-hover"
    >
      Cancel
    </button>
    <button
      type="button"
      onclick={() => {
        release?.();
        release = null;
        onSignOut();
      }}
      class="h-12 px-5 rounded-full bg-danger text-on-danger text-sm font-semibold hover:brightness-110"
    >
      Sign out
    </button>
  </div>
</dialog>
