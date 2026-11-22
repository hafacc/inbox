<!-- @component An image attachment full screen over everything, with its name and Download; Escape, Back or a tap beside the image closes it. -->
<script lang="ts">
  import Download from "@lucide/svelte/icons/download";
  import X from "@lucide/svelte/icons/x";
  import { untrack } from "svelte";
  import { pushBack } from "../back-stack.svelte";
  import { fadeScrim } from "./menu-motion";
  import { portal } from "./phone";

  let {
    src,
    filename,
    onDownload,
    onClose,
  }: {
    /** the image's address */
    src: string;
    /** its file name */
    filename: string;
    /** save it to the device */
    onDownload: () => void;
    /** close the viewer */
    onClose: () => void;
  } = $props();

  let closeButton = $state<HTMLButtonElement>();
  // Focus goes back where it was when the viewer closes.
  const before = document.activeElement as HTMLElement | null;

  $effect(() => untrack(() => pushBack(() => onClose())));

  $effect(() => {
    closeButton?.focus();
    return () => before?.focus({ preventScroll: true });
  });

  function onKeydown(event: KeyboardEvent) {
    if (event.key !== "Escape") return;
    event.preventDefault();
    event.stopPropagation();
    onClose();
  }

  const barButton =
    "w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-white hover:bg-white/15 transition-colors";
</script>

<svelte:document onkeydowncapture={onKeydown} />

<div class="contents">
  <div
    {@attach portal}
    transition:fadeScrim|global
    role="dialog"
    aria-modal="true"
    aria-label={filename}
    class="fixed inset-0 z-[60] flex flex-col bg-black/90"
  >
    <div
      class="relative z-10 flex-none flex items-center gap-1 pt-[env(safe-area-inset-top)] px-1"
    >
      <button
        bind:this={closeButton}
        type="button"
        aria-label="Close"
        onclick={onClose}
        class={barButton}
      >
        <X class="w-5 h-5" />
      </button>
      <span class="flex-1 min-w-0 truncate text-sm font-medium text-white">
        {filename}
      </span>
      <button
        type="button"
        aria-label={`Download ${filename}`}
        onclick={onDownload}
        class={barButton}
      >
        <Download class="w-5 h-5" />
      </button>
    </div>
    <div
      class="relative flex-1 min-h-0 flex items-center justify-center p-4 pb-[max(1rem,env(safe-area-inset-bottom))]"
    >
      <button
        type="button"
        tabindex="-1"
        aria-label="Close"
        onclick={onClose}
        class="absolute inset-0 w-full cursor-default"
      ></button>
      <img
        {src}
        alt={filename}
        class="relative max-w-full max-h-full object-contain rounded-lg"
      >
    </div>
  </div>
</div>
