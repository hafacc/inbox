<!-- @component The thread before or after the open one, drawn beside it off screen so a sideways swipe slides it in, from where it opens: from its saved copy, else its row, without asking Gmail or marking anything read. Until `full`, its messages are light text with no message frames; once full it stays so. -->
<script lang="ts">
  import { isImportant } from "@inbox/shared/focus";
  import type { Person } from "@inbox/shared/mime";
  import { threadOpenState } from "@inbox/shared/thread-open";
  import { untrack } from "svelte";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { previewText } from "../neighbor-preview";
  import { afterPaint } from "../open-timing";
  import { rowText } from "../pgp/message-crypto";
  import type { ThreadDetail } from "../thread-detail";
  import { displayName } from "../thread-summary";
  import Avatar from "./avatar.svelte";
  import LabelChip from "./label-chip.svelte";
  import MessageItem from "./message-item.svelte";
  import { setPreview } from "./preview";
  import ThreadSubjectBar from "./thread-subject-bar.svelte";

  let {
    row,
    full,
  }: {
    /** the neighbor's row */
    row: Row;
    /** whether a swipe brings it near, so its messages are drawn in full */
    full: boolean;
  } = $props();

  const mail = getMailState();
  setPreview();
  const thread = $derived(row.thread);
  const subject = $derived(
    rowText(thread.subject, thread.snippet, mail.decryptedSubjectOf(thread.id))
      .subject || "(no subject)",
  );
  const labels = $derived(mail.labelsOf(thread));
  let detail = $state.raw<ThreadDetail | null>(null);
  const opening = $derived(
    detail === null ? null : threadOpenState(detail.messages),
  );
  const expanded = $derived(new Set(opening?.expanded ?? []));
  // Drawn from where the thread opens (its first unread message, else its last), as the open
  // thread lands there and a swipe shouldn't jump; the labels above it are then scrolled off.
  const shown = $derived.by(() => {
    if (detail === null) return [];
    const from = detail.messages.findIndex(
      (message) => message.id === opening?.scrollTo,
    );
    return detail.messages.slice(Math.max(from, 0));
  });
  const atTop = $derived(
    detail === null || shown[0]?.id === detail.messages[0]?.id,
  );
  const sender = $derived(thread.senders.at(-1) ?? null);
  // Drawn in full once, it stays so: going back to light text would rebuild its frames on the
  // next swipe.
  let drawnFull = $state(false);
  $effect(() => {
    if (full) drawnFull = true;
  });

  // Read once the open thread has drawn, so its own read and frames don't wait on its neighbors'.
  $effect(() => {
    const id = thread.id;
    let current = true;
    afterPaint(() => {
      if (!current) return;
      void untrack(() => mail.savedDetail(id)).then((saved) => {
        if (current) detail = saved;
      });
    });
    return () => {
      current = false;
    };
  });
</script>

{#snippet light(person: Person, text: string)}
  <div class="border-t border-separator px-3 pt-4 pb-5 flex items-start gap-4">
    <Avatar {person} size={36} />
    <div class="flex-1 min-w-0 leading-5">
      <div class="font-semibold truncate">
        {displayName(person, mail.email)}
      </div>
      <p class="mt-3 text-text-secondary break-words whitespace-pre-line">
        {text}
      </p>
    </div>
  </div>
{/snippet}

<div class="min-h-full">
  <ThreadSubjectBar
    {subject}
    important={isImportant(thread.labelIds)}
    onBack={() => {}}
    onImportant={() => {}}
  />
  {#if labels.length > 0 && atTop}
    <div class="px-4 py-1 flex flex-wrap items-center gap-x-1.5">
      {#each labels as label (label.id)}
        <span class="h-12 flex items-center"><LabelChip {label} /></span>
      {/each}
    </div>
  {/if}
  <div
    class="mx-1 my-2 rounded-xl bg-surface overflow-hidden [&>*:first-child]:border-t-0"
  >
    {#if detail !== null}
      {#each shown as message (message.id)}
        {#if drawnFull || !expanded.has(message.id)}
          <MessageItem
            {message}
            expanded={expanded.has(message.id)}
            last={message.id === detail.messages.at(-1)?.id}
            onToggle={() => {}}
          />
        {:else}
          {@render light(message.from, previewText(message))}
        {/if}
      {/each}
    {:else if sender !== null}
      {@render light(sender, thread.snippet)}
    {/if}
  </div>
</div>
