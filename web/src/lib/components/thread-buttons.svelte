<!-- @component A thread's Pin, Snooze (with its menu), Label (with its menu) and Mark read / Mark unread buttons (each can be left out), shared by a row's hover actions and the open thread's header. -->
<script lang="ts">
  import Clock from "@lucide/svelte/icons/clock";
  import Mail from "@lucide/svelte/icons/mail";
  import MailOpen from "@lucide/svelte/icons/mail-open";
  import Pin from "@lucide/svelte/icons/pin";
  import PinOff from "@lucide/svelte/icons/pin-off";
  import Tag from "@lucide/svelte/icons/tag";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { keyLabel, keyTitle, shiftKey } from "../shortcuts";
  import { isPinned } from "../thread-summary";
  import {
    type IconButtonSize,
    iconButton,
    openColors,
    pinnedColors,
    snoozeOpenColors,
    toneColors,
  } from "./icon-button";
  import LabelMenu from "./label-menu.svelte";
  import SnoozePopover from "./snooze-popover.svelte";

  let {
    row,
    showPin = true,
    showSnooze = true,
    showLabel = true,
    showRead = true,
    size = "small",
  }: {
    /** the thread the buttons act on */
    row: Row;
    /** whether Pin shows */
    showPin?: boolean;
    /** whether Snooze shows */
    showSnooze?: boolean;
    /** whether Label shows */
    showLabel?: boolean;
    /** whether Mark read / Mark unread shows */
    showRead?: boolean;
    /** the buttons' circle size */
    size?: IconButtonSize;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  const id = $derived(row.thread.id);
  const pinned = $derived(isPinned(row.thread));
  const snoozed = $derived(row.status === "snoozed");
  const snoozeOpen = $derived(mail.snoozeMenuFor === id);
  const labelMenuOpen = $derived(mail.labelMenuFor === id);
  const unread = $derived(row.thread.unread);
  const readWords = $derived(unread ? "Mark read" : "Mark unread");
  const readKey = $derived(shiftKey(unread ? "I" : "U"));
  const quietButton = $derived(iconButton(size));
  const openButton = $derived(iconButton(size, openColors));
</script>

{#if showPin}
  <button
    type="button"
    data-action="pin"
    aria-label={keyLabel(pinned ? "Unpin" : "Pin", "p", hub.settings.shortcuts)}
    title={keyTitle(pinned ? "Unpin" : "Pin", "p", hub.settings.shortcuts)}
    aria-pressed={pinned}
    onclick={() => mail.togglePin(id)}
    class={iconButton(size, pinned ? pinnedColors : toneColors.pin)}
  >
    {#if pinned}
      <PinOff class="w-[18px] h-[18px]" />
    {:else}
      <Pin class="w-[18px] h-[18px]" />
    {/if}
  </button>
{/if}
{#if showSnooze}
  <div class="relative">
    <button
      type="button"
      data-action="snooze"
      aria-label={keyLabel(
        snoozed ? "Change snooze time" : "Snooze",
        "b",
        hub.settings.shortcuts,
      )}
      title={keyTitle(
        snoozed ? "Change snooze time" : "Snooze",
        "b",
        hub.settings.shortcuts,
      )}
      aria-haspopup="menu"
      aria-expanded={snoozeOpen}
      onclick={() => {
        if (snoozeOpen) mail.closeSnoozeMenu();
        else mail.openSnoozeMenu(id);
      }}
      class={iconButton(
        size,
        snoozeOpen ? snoozeOpenColors : toneColors.snooze,
      )}
    >
      <Clock class="w-[18px] h-[18px]" />
    </button>
    {#if snoozeOpen}
      <SnoozePopover
        onPick={(epoch, source, key) => mail.snooze(id, epoch, source, key)}
        onClose={mail.closeSnoozeMenu}
      />
    {/if}
  </div>
{/if}
{#if showLabel}
  <div class="relative">
    <button
      type="button"
      data-action="label"
      aria-label={keyLabel("Label", "l", hub.settings.shortcuts)}
      title={keyTitle("Label", "l", hub.settings.shortcuts)}
      aria-haspopup="dialog"
      aria-expanded={labelMenuOpen}
      onclick={() => mail.toggleLabelMenu(id)}
      class={labelMenuOpen ? openButton : quietButton}
    >
      <Tag class="w-[18px] h-[18px]" />
    </button>
    {#if labelMenuOpen}
      <LabelMenu threadIds={[id]} />
    {/if}
  </div>
{/if}
{#if showRead}
  <button
    type="button"
    data-action="read"
    aria-label={keyLabel(readWords, readKey, hub.settings.shortcuts)}
    title={keyTitle(readWords, readKey, hub.settings.shortcuts)}
    onclick={() => mail.setUnread([id], !unread)}
    class={quietButton}
  >
    {#if unread}
      <MailOpen class="w-[18px] h-[18px]" />
    {:else}
      <Mail class="w-[18px] h-[18px]" />
    {/if}
  </button>
{/if}
