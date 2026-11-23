<!-- @component A link in the composer, added or edited: its text (not asked when making selected text a link) and address, prefilled when editing, with Cancel and Save. -->
<script lang="ts">
  import { linkAddress } from "@inbox/shared/web-links";
  import { untrack } from "svelte";
  import { pushBack } from "../back-stack.svelte";
  import { portal } from "./phone";

  let {
    text,
    href,
    asksText = true,
    title,
    onCancel,
    onSave,
  }: {
    /** the link's text as it stands, or as selected */
    text: string;
    /** where it leads as it stands; empty for a new link */
    href: string;
    /** whether it asks for the text, not only the address */
    asksText?: boolean;
    /** its heading */
    title: string;
    onCancel: () => void;
    /** save the new text (the address when left empty) and address (`https://` put before one without a scheme; never one but http, https or mailto) */
    onSave: (text: string, href: string) => void;
  } = $props();

  let dialog: HTMLDialogElement;
  let release: (() => void) | null = null;
  // Prefilled once; the dialog edits its own copy.
  let shownText = $state(untrack(() => text));
  let address = $state(untrack(() => href));
  const target = $derived(linkAddress(address));
  // An address that isn't a web or mail address ("javascript:…") can't be a link.
  const refused = $derived(address.trim() !== "" && target === null);
  const canSave = $derived(address.trim() !== "" && target !== null);

  $effect(() => {
    dialog.showModal();
    release = pushBack(onCancel);
    return () => release?.();
  });

  function save(event: SubmitEvent) {
    event.preventDefault();
    if (!canSave || target === null) return;
    release?.();
    release = null;
    onSave(shownText.trim() === "" ? target : shownText, target);
  }

  const field =
    "mt-1 h-10 max-sm:h-12 w-full px-3.5 rounded-xl border border-border-strong bg-surface text-sm text-text outline-none focus:border-focus focus:ring-1 focus:ring-focus";
</script>

<!-- The dialog moves to the body, out of the composer's form, so its submit, keys and pastes aren't the composer's. -->
<div>
  <!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click on the backdrop cancels; Esc already does natively -->
  <dialog
    bind:this={dialog}
    {@attach portal}
    aria-labelledby="link-dialog-title"
    oncancel={(event) => {
      event.preventDefault();
      onCancel();
    }}
    onclick={(event) => {
      if (event.target === dialog) onCancel();
    }}
    class="m-auto w-[400px] max-w-[calc(100vw-32px)] rounded-[28px] bg-surface text-text shadow-dialog p-6"
  >
    <form onsubmit={save}>
      <h2
        id="link-dialog-title"
        class="text-lg font-semibold tracking-[-0.01em]"
      >
        {title}
      </h2>
      {#if asksText}
        <label class="mt-4 block text-[13px] font-medium text-text-secondary">
          Text
          <input bind:value={shownText} class={field}>
        </label>
      {/if}
      <label
        class={[
          "block text-[13px] font-medium text-text-secondary",
          asksText ? "mt-3" : "mt-4",
        ]}
      >
        Address
        <input
          bind:value={address}
          inputmode="url"
          autocapitalize="off"
          spellcheck="false"
          aria-invalid={refused}
          aria-describedby={refused ? "link-dialog-refused" : undefined}
          class={field}
        >
      </label>
      {#if refused}
        <p id="link-dialog-refused" class="mt-1.5 text-[13px] text-danger">
          A link can lead only to a web page (http or https) or an email address
          (mailto)
        </p>
      {/if}
      <div class="mt-6 flex justify-end gap-2">
        <button
          type="button"
          onclick={onCancel}
          class="h-12 px-5 rounded-full text-sm font-semibold hover:bg-surface-hover"
        >
          Cancel
        </button>
        <button
          type="submit"
          disabled={!canSave}
          class="h-12 px-5 rounded-full bg-accent text-on-accent text-sm font-semibold hover:brightness-110 disabled:opacity-50"
        >
          Save
        </button>
      </div>
    </form>
  </dialog>
</div>
