<!-- @component A row's hover actions: Pin, Snooze (with its menu), Label (with its menu), Mark read / Mark unread and Done, or Move to inbox for a thread out of the inbox; Wake now in Snoozed; Not spam in Spam. -->
<script lang="ts">
  import { rowInboxAction } from "@inbox/shared/list-model";
  import AlarmClockOff from "@lucide/svelte/icons/alarm-clock-off";
  import Check from "@lucide/svelte/icons/check";
  import Inbox from "@lucide/svelte/icons/inbox";
  import MailCheck from "@lucide/svelte/icons/mail-check";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { keyLabel, keyTitle } from "../shortcuts";
  import { LABEL } from "../thread-summary";
  import { iconButton, toneColors } from "./icon-button";
  import ThreadButtons from "./thread-buttons.svelte";

  let {
    row,
  }: {
    /** the thread the actions act on */
    row: Row;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const id = $derived(row.thread.id);
  const snoozed = $derived(row.status === "snoozed");
  const inboxAction = $derived(rowInboxAction(row));
  const spam = $derived(row.thread.labelIds.includes(LABEL.spam));
</script>

<div class="flex items-center gap-1 shrink-0">
  {#if spam}
    <button
      type="button"
      data-action="not-spam"
      title={keyTitle("Not spam", "!", hub.settings.shortcuts)}
      onclick={() => mail.notSpam([id])}
      class="relative h-8 flex items-center gap-1.5 px-2.5 rounded-full border border-border bg-surface text-[13px] font-medium hover:bg-surface-hover transition-colors pointer-coarse:after:absolute pointer-coarse:after:-inset-y-2 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
    >
      <MailCheck class="w-4 h-4" aria-hidden="true" />
      Not spam
    </button>
  {:else if snoozed}
    <button
      type="button"
      data-action="wake"
      onclick={() => mail.wakeNow(id)}
      class="relative h-8 flex items-center gap-1.5 px-2.5 rounded-full border border-border bg-surface text-[13px] font-medium hover:bg-surface-hover transition-colors pointer-coarse:after:absolute pointer-coarse:after:-inset-y-2 pointer-coarse:after:inset-x-0 pointer-coarse:after:content-['']"
    >
      <AlarmClockOff class="w-4 h-4" aria-hidden="true" />
      Wake now
    </button>
  {/if}
  <ThreadButtons {row} showPin={!spam && !snoozed} />
  {#if inboxAction === "done"}
    <button
      type="button"
      data-action="done"
      aria-label={keyLabel("Done", "e", hub.settings.shortcuts)}
      title={keyTitle("Done", "e", hub.settings.shortcuts)}
      onclick={() => mail.done([id])}
      class={iconButton("small", toneColors.done)}
    >
      <Check class="w-[18px] h-[18px]" />
    </button>
  {:else if inboxAction === "inbox"}
    <button
      type="button"
      data-action="inbox"
      aria-label={keyLabel("Move to inbox", "e", hub.settings.shortcuts)}
      title={keyTitle("Move to inbox", "e", hub.settings.shortcuts)}
      onclick={() => mail.moveToInbox([id])}
      class={iconButton("small")}
    >
      <Inbox class="w-[18px] h-[18px]" />
    </button>
  {/if}
</div>
