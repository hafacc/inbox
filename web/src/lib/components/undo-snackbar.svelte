<!-- @component The one-slot pill: undo send (10 s) and what became of a send, undo after discarding a draft, and Open after closing one; else undo (5 s) after done, snooze, pin, spam, delete, a label change, a move, mark read or unread, or a todo deleted or moved; else a queued change Gmail refused; else the offer of a new version. -->
<script lang="ts">
  import { undoText } from "@inbox/shared/undo";
  import Check from "@lucide/svelte/icons/check";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Clock from "@lucide/svelte/icons/clock";
  import Inbox from "@lucide/svelte/icons/inbox";
  import Mail from "@lucide/svelte/icons/mail";
  import MailOpen from "@lucide/svelte/icons/mail-open";
  import MoveVertical from "@lucide/svelte/icons/move-vertical";
  import OctagonAlert from "@lucide/svelte/icons/octagon-alert";
  import Pin from "@lucide/svelte/icons/pin";
  import Send from "@lucide/svelte/icons/send";
  import Tag from "@lucide/svelte/icons/tag";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import X from "@lucide/svelte/icons/x";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { isMacPlatform, undoKeyLabel } from "../keyboard";
  import { getMailState, UNDO_MS } from "../mail-state.svelte";
  import { getPwaState } from "../pwa.svelte";
  import { chordTitle } from "../shortcuts";

  let {
    besideCompose = false,
    above = null,
  }: {
    /** whether the phone's floating Compose button is on screen, so the pill keeps clear of it */
    besideCompose?: boolean;
    /** at phone width, the corner button the pill sits above: an open thread's, or the selection's over its bar */
    above?: "corner" | "selectionCorner" | null;
  } = $props();

  // At phone width it sits centered on the floating Compose button's line, kept clear of it on both sides while it
  // shows, and above an action corner button.
  const PILL = $derived(
    [
      "fixed z-30 bottom-safe-5 left-1/2 -translate-x-1/2 w-max sm:max-w-[calc(100vw-2rem-env(safe-area-inset-left)-env(safe-area-inset-right))] sm:left-[calc(50%+var(--sidebar-reserved)/2)] flex flex-col overflow-hidden rounded-[24px] bg-snackbar text-on-snackbar shadow-pop animate-rise",
      besideCompose
        ? "max-w-[calc(100vw-10.5rem-env(safe-area-inset-left)-env(safe-area-inset-right))]"
        : "max-w-[calc(100vw-2rem-env(safe-area-inset-left)-env(safe-area-inset-right))]",
      above === "corner" && "max-sm:bottom-[calc(5.25rem+var(--safe-bottom))]",
      above === "selectionCorner" &&
        "max-sm:bottom-[calc(8.5rem+var(--safe-bottom))]",
    ]
      .filter(Boolean)
      .join(" "),
  );

  const mail = getMailState();
  const pwa = getPwaState();
  const hub = getComposeHub();

  const label = $derived.by(() => {
    const entry = mail.lastUndo;
    if (!entry) return "";
    const rows = new Set(entry.items.map((item) => item.row)).size;
    const count = rows + (entry.todos?.before.length ?? 0);
    return undoText(entry.kind, count, entry.pinned ?? false);
  });
</script>

{#snippet undoPill(
  Icon: typeof Send,
  text: string,
  onUndo: () => void,
  expiresAt: number,
)}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-4 pl-4 pr-2 text-sm">
      <span class="min-w-0 flex items-center gap-2">
        <Icon class="w-4 h-4 shrink-0" aria-hidden="true" />
        <span class="line-clamp-2">{text}</span>
      </span>
      <button
        type="button"
        onclick={onUndo}
        title={chordTitle("Undo", undoKeyLabel())}
        aria-keyshortcuts={isMacPlatform() ? "Meta+Z" : "Control+Z"}
        class="hit-target shrink-0 h-8 flex items-center gap-2 px-3 rounded-full bg-snackbar-hover font-semibold text-snackbar-accent hover:brightness-125"
      >
        Undo
      </button>
    </div>
    {#key expiresAt}
      <div
        role="progressbar"
        aria-label="Time left to undo"
        class="h-0.5 bg-snackbar-accent origin-left"
        style:animation="undo-drain {Math.max(
          0,
          expiresAt - Date.now(),
        )}ms linear forwards"
      ></div>
    {/key}
  </div>
{/snippet}

{#if hub.pill?.kind === "undo"}
  {@render undoPill(
    Send,
    mail.offline && hub.pill.outboxId === null
      ? "Sends when you're back online"
      : "Sending…",
    hub.undoSend,
    hub.pill.expiresAt,
  )}
{:else if hub.pill?.kind === "discarded"}
  {@render undoPill(
    Trash2,
    "Draft discarded",
    hub.undoDiscard,
    hub.pill.expiresAt,
  )}
{:else if hub.pill?.kind === "saved"}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-4 pl-4 pr-2 text-sm">
      <span class="min-w-0 flex items-center gap-2">
        <Check class="w-4 h-4" aria-hidden="true" />
        <span class="line-clamp-2">Saved to Drafts</span>
      </span>
      <button
        type="button"
        onclick={hub.openSaved}
        class="hit-target shrink-0 h-8 flex items-center gap-2 px-3 rounded-full bg-snackbar-hover font-semibold text-snackbar-accent hover:brightness-125"
      >
        Open
      </button>
    </div>
  </div>
{:else if hub.pill?.kind === "notice"}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-2 pl-4 pr-4 text-sm">
      {#if hub.pill.failed}
        <CircleAlert class="w-4 h-4 text-snackbar-accent" aria-hidden="true" />
      {:else}
        <Check class="w-4 h-4" aria-hidden="true" />
      {/if}
      <span class="line-clamp-2">{hub.pill.text}</span>
    </div>
  </div>
{:else if mail.lastUndo && mail.undoExpiresAt}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-4 pl-4 pr-2 text-sm">
      <span class="min-w-0 flex items-center gap-2">
        {#if mail.lastUndo.kind === "snooze"}
          <Clock class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "pin"}
          <Pin class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "delete" ||
          mail.lastUndo.kind === "trash"}
          <Trash2 class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "move"}
          <MoveVertical class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "label"}
          <Tag class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "read"}
          <MailOpen class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "unread"}
          <Mail class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "spam"}
          <OctagonAlert class="w-4 h-4" aria-hidden="true" />
        {:else if mail.lastUndo.kind === "notSpam"}
          <Inbox class="w-4 h-4" aria-hidden="true" />
        {:else}
          <Check class="w-4 h-4" aria-hidden="true" />
        {/if}
        <span class="line-clamp-2">{label}</span>
      </span>
      <button
        type="button"
        onclick={mail.undo}
        title={chordTitle("Undo", undoKeyLabel())}
        aria-keyshortcuts={isMacPlatform() ? "Meta+Z" : "Control+Z"}
        class="hit-target shrink-0 h-8 flex items-center gap-2 px-3 rounded-full bg-snackbar-hover font-semibold text-snackbar-accent hover:brightness-125"
      >
        Undo
      </button>
    </div>
    {#key mail.undoExpiresAt}
      <div
        class="h-0.5 bg-snackbar-accent origin-left"
        style:animation="undo-drain {UNDO_MS}ms linear forwards"
      ></div>
    {/key}
  </div>
{:else if mail.notice}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-2 pl-4 pr-4 text-sm">
      <CircleAlert class="w-4 h-4 text-snackbar-accent" aria-hidden="true" />
      <span class="line-clamp-2">{mail.notice.text}</span>
    </div>
  </div>
{:else if pwa.updateReady}
  <div role="status" class={PILL}>
    <div class="min-h-12 py-1.5 flex items-center gap-4 pl-4 pr-2 text-sm">
      <span>New version</span>
      <button
        type="button"
        onclick={pwa.applyUpdate}
        class="hit-target shrink-0 h-8 px-3 rounded-full font-semibold text-snackbar-accent hover:bg-snackbar-hover"
      >
        Reload
      </button>
      <button
        type="button"
        onclick={pwa.dismissUpdate}
        aria-label="Dismiss"
        class="hit-target -ml-2 p-1.5 rounded-full opacity-80 hover:bg-snackbar-hover"
      >
        <X class="w-3.5 h-3.5" />
      </button>
    </div>
  </div>
{/if}
