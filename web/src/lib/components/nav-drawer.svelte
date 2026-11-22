<!-- @component The phone side panel: the mailbox list sliding in from the left over a scrim. A tap on the scrim, Esc, the browser's back or picking an item closes it; focus stays inside while it is open. -->
<script lang="ts">
  import { cubicIn, cubicOut } from "svelte/easing";
  import { prefersReducedMotion } from "svelte/motion";
  import type { TransitionConfig } from "svelte/transition";
  import Sidebar from "./sidebar.svelte";

  let {
    open,
    onClose,
    onSignOut,
    onSettings,
  }: {
    open: boolean;
    onClose: () => void;
    onSignOut: () => void;
    onSettings: () => void;
  } = $props();

  // The width the panel gives way to the rail at, as the `md` breakpoint.
  const WIDE = "(min-width: 48rem)";
  let opener: Element | null = null;

  function slideIn(_node: Element): TransitionConfig {
    if (prefersReducedMotion.current) return { duration: 0 };
    return {
      duration: 220,
      easing: cubicOut,
      css: (progress) => `transform:translateX(${(progress - 1) * 100}%)`,
    };
  }

  function slideOut(node: Element): TransitionConfig {
    node.classList.add("closing");
    if (prefersReducedMotion.current) return { duration: 0 };
    return {
      duration: 200,
      easing: cubicIn,
      css: (progress) => `transform:translateX(${(progress - 1) * 100}%)`,
    };
  }

  $effect(() => {
    if (!open) return;
    const wide = window.matchMedia(WIDE);
    function onWide() {
      if (wide.matches) onClose();
    }
    wide.addEventListener("change", onWide);
    return () => wide.removeEventListener("change", onWide);
  });
</script>

{#if open}
  <!-- biome-ignore lint/a11y/useKeyWithClickEvents: a click on the scrim closes it; Esc already does natively -->
  <dialog
    {@attach (node) => {
      opener = document.activeElement;
      node.showModal();
    }}
    in:slideIn
    out:slideOut
    onoutroend={() => {
      if (opener instanceof HTMLElement) opener.focus();
    }}
    aria-label="Menu"
    oncancel={(event) => {
      event.preventDefault();
      onClose();
    }}
    onclick={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}
    class="side-panel fixed inset-y-0 left-0 m-0 h-full max-h-none w-[min(20rem,calc(100vw-56px))] max-w-none p-0 rounded-r-[20px] bg-sidebar text-text shadow-dialog overflow-hidden"
  >
    <Sidebar
      drawer
      collapsed={false}
      onToggleCollapsed={() => {}}
      onNavigate={onClose}
      onSignOut={() => {
        onClose();
        onSignOut();
      }}
      onSettings={() => {
        onClose();
        onSettings();
      }}
    />
  </dialog>
{/if}
