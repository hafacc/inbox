<!-- @component One message of an open thread: a one-line header when folded, the full message when expanded, with Unsubscribe for list mail and the invite card for calendar invites. At phone width it is laid out as on Android: the same header folded or not, the body 8 px from the card's edges. -->
<script lang="ts">
  import { addressKey } from "@inbox/shared/compose";
  import type { Person } from "@inbox/shared/mime";
  import { formatListDate, formatMessageDate } from "@inbox/shared/time-format";
  import Check from "@lucide/svelte/icons/check";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import Lock from "@lucide/svelte/icons/lock";
  import MailMinus from "@lucide/svelte/icons/mail-minus";
  import Paperclip from "@lucide/svelte/icons/paperclip";
  import ShieldAlert from "@lucide/svelte/icons/shield-alert";
  import ShieldCheck from "@lucide/svelte/icons/shield-check";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";
  import { pgpBadge } from "../pgp/message-crypto";
  import type { MessageView } from "../thread-detail";
  import { displayName } from "../thread-summary";
  import {
    isFailure,
    stepText,
    unsubscribeOf,
  } from "../unsubscribe/unsubscribe-model";
  import AttachmentCard from "./attachment-card.svelte";
  import Avatar from "./avatar.svelte";
  import MessageBody from "./message-body.svelte";
  import { phoneWidth } from "./phone";
  import { inPreview } from "./preview";

  let {
    message,
    expanded,
    last,
    onToggle,
  }: {
    /** the message shown */
    message: MessageView;
    /** whether it shows in full rather than as one line */
    expanded: boolean;
    /** whether it is the thread's last message, with more room below it */
    last: boolean;
    /** fold or unfold it */
    onToggle: () => void;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  // A neighbor drawn beside the open thread fetches no invite or attachment.
  const preview = inPreview();
  const sender = $derived(displayName(message.from, mail.email));
  const showAddress = $derived(
    message.from.email !== "" && sender !== message.from.email,
  );
  const fromSelf = $derived(
    hub.ownAddresses.some(
      (address) => addressKey(address) === addressKey(message.from.email),
    ),
  );
  const unsubscribe = $derived(
    fromSelf
      ? null
      : unsubscribeOf(message.listUnsubscribe, message.listUnsubscribePost),
  );
  // Kept in mail state, so folding the message or closing the thread keeps how it went.
  const step = $derived(mail.unsubscribes.stepOf(message.id));
  // A failed one-click unsubscribe can still be done on the sender's page.
  const fallbackPage = $derived(
    unsubscribe?.preferred === "oneClick" ? unsubscribe.url : null,
  );

  function runUnsubscribe() {
    if (unsubscribe === null) return;
    void mail.unsubscribes.run({
      messageId: message.id,
      post: message.listUnsubscribePost ?? null,
      ways: unsubscribe,
      send: (request) =>
        hub.sendMessage({
          from:
            message.to.find((person) => hub.canSendFrom(person.email))?.email ??
            null,
          ...request,
          threadId: null,
        }),
      // Only the sender's page knows how this ends, so the control stays as it was.
      openPage: (url) => window.open(url, "_blank", "noopener,noreferrer"),
    });
  }

  // An account without a key can't open any encrypted mail.
  const badge = $derived(
    message.pgp
      ? pgpBadge(
          mail.encryption.ready &&
            !mail.encryption.hasKey &&
            message.pgp.status === "locked"
            ? { status: "wrong-key", signature: null }
            : message.pgp,
          sender,
        )
      : null,
  );
  const recipients = $derived(
    message.to.map((person) => displayName(person, mail.email)).join(", "),
  );
  // A phone header's second line, as on Android: whom an open message went to (Cc and Bcc too),
  // else its snippet.
  const secondLine = $derived.by(() => {
    if (!expanded) {
      return badge?.problem ? "Encrypted message" : message.snippet;
    }
    const names = (people: readonly Person[]) =>
      people.map((person) => displayName(person, mail.email)).join(", ");
    const bcc = message.headers?.bcc ?? [];
    const sentTo =
      names([...message.to, ...(message.headers?.cc ?? [])]) ||
      "undisclosed recipients";
    return bcc.length > 0 ? `to ${sentTo}, bcc: ${names(bcc)}` : `to ${sentTo}`;
  });
</script>

{#snippet unsubscribeBar(phone: boolean)}
  <!-- A banner like the hidden-images one, as on Android. -->
  <div
    aria-live="polite"
    class={[
      "min-h-9 flex flex-wrap items-center gap-x-1 py-1 pl-3 pr-1 bg-surface-muted text-[13px]",
      phone ? "mb-2 rounded-lg" : "mt-3 sm:ml-[52px] rounded-xl",
    ]}
  >
    {#if step === "idle"}
      <MailMinus
        class="w-4 h-4 shrink-0 mr-1 text-text-secondary"
        aria-hidden="true"
      />
      <button
        type="button"
        onclick={runUnsubscribe}
        class="h-7 px-2 -ml-1 rounded-full font-medium text-accent-strong hover:bg-surface-hover transition-colors"
      >
        {stepText(step)}
      </button>
    {:else if step === "working"}
      <LoaderCircle
        class="w-4 h-4 shrink-0 mr-1 text-accent animate-spin motion-reduce:animate-none"
        aria-hidden="true"
      />
      <span class="h-7 inline-flex items-center font-medium text-text-secondary"
        >{stepText(step)}</span
      >
    {:else if isFailure(step)}
      <TriangleAlert
        class="w-4 h-4 shrink-0 mr-1 text-danger"
        aria-hidden="true"
      />
      <span class="h-7 inline-flex items-center font-medium text-danger"
        >{stepText(step)}</span
      >
      {#if fallbackPage}
        <span class="text-text-secondary" aria-hidden="true">·</span>
        <a
          href={fallbackPage}
          target="_blank"
          rel="noopener noreferrer"
          class="h-7 inline-flex items-center px-2 rounded-full font-medium text-accent-strong hover:bg-surface-hover transition-colors"
          >Open page</a
        >
      {/if}
      <span class="text-text-secondary" aria-hidden="true">·</span>
      <button
        type="button"
        onclick={runUnsubscribe}
        class="h-7 px-2 rounded-full font-medium text-accent-strong hover:bg-surface-hover transition-colors"
      >
        Retry
      </button>
    {:else}
      <Check class="w-4 h-4 shrink-0 mr-1 text-done" aria-hidden="true" />
      <span class="h-7 inline-flex items-center font-medium text-done"
        >{stepText(step)}</span
      >
    {/if}
  </div>
{/snippet}

<!-- On a phone, as on Android: one header folded or not, its second line the snippet or whom it went to. -->
{#if phoneWidth.current}
  <div class="border-t border-separator">
    <button
      type="button"
      onclick={onToggle}
      aria-expanded={expanded}
      aria-label={expanded
        ? `Fold message from ${sender}`
        : `Message from ${sender}: ${secondLine}`}
      class="w-full flex gap-3 px-4 py-3 text-left"
    >
      <Avatar person={message.from} size={36} />
      <span class="flex-1 min-w-0 flex flex-col">
        <span class="flex items-center gap-2 min-w-0">
          <span
            class={[
              "flex-1 min-w-0 truncate text-base leading-6",
              message.unread ? "font-semibold" : "font-medium",
            ]}
            >{sender}</span
          >
          {#if badge}
            <Lock
              class="w-3.5 h-3.5 shrink-0 text-done"
              role="img"
              aria-label="Encrypted"
            />
          {/if}
          <span
            class="shrink-0 text-[13px] leading-[18px] tabular-nums text-text-secondary"
            >{formatMessageDate(message.date, mail.now)}</span
          >
        </span>
        {#if expanded && showAddress}
          <span class="truncate text-[13px] leading-[18px] text-text-secondary"
            >{message.from.email}</span
          >
        {/if}
        <span class="truncate text-[13px] leading-[18px] text-text-secondary"
          >{secondLine}</span
        >
      </span>
    </button>
    {#if expanded}
      {#if badge && !badge.problem}
        {@const invalid =
          message.pgp?.status === "decrypted" &&
          message.pgp.signature === "invalid"}
        {@const Icon = invalid
          ? CircleAlert
          : badge.signed
            ? ShieldCheck
            : Lock}
        <p
          class={[
            "px-4 pb-2 flex items-center gap-1.5 text-[13px] leading-[18px]",
            invalid ? "text-snooze" : "text-done",
          ]}
        >
          <Icon class="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
          {invalid ? badge.warning : badge.label}
        </p>
        {#if !invalid && badge.warning}
          <p
            role="alert"
            class="px-4 pb-2 flex items-center gap-1.5 text-[13px] leading-[18px] text-snooze"
          >
            <CircleAlert class="w-3.5 h-3.5 shrink-0" aria-hidden="true" />
            {badge.warning}
          </p>
        {/if}
      {/if}
      {#if badge?.problem}
        <div class="px-4 pb-4 flex flex-col gap-2">
          <p class="flex items-center gap-2">
            <Lock class="w-4 h-4 shrink-0 text-done" aria-hidden="true" />
            {badge.problem}
          </p>
          {#if badge.unlock && mail.encryption.hasKey}
            <button
              type="button"
              onclick={() => mail.encryption.requestUnlock()}
              class="self-start min-h-12 pr-2 rounded-full font-medium text-accent"
            >
              Unlock encrypted mail
            </button>
          {/if}
        </div>
      {:else}
        <div class="px-2 pb-4">
          {#if unsubscribe}
            {@render unsubscribeBar(true)}
          {/if}
          {#if message.body.calendar && !preview}
            {@const part = message.body.calendar}
            <div class="mb-2">
              {#await import(
                "./invite-card.svelte"
              ) then { default: InviteCard }}
                <InviteCard {message} {part} />
              {/await}
            </div>
          {/if}
          <MessageBody {message} />
          {#if message.body.attachments.length > 0 && !preview}
            <div class="pt-3 flex flex-col gap-2">
              {#each message.body
                .attachments as attachment, index (`${index}-${attachment.filename}`)}
                <AttachmentCard messageId={message.id} {attachment} />
              {/each}
            </div>
          {/if}
        </div>
      {/if}
    {/if}
  </div>
{:else if expanded}
  <div
    class={[
      "border-t border-separator px-3 sm:px-6 pt-4",
      last ? "pb-6" : "pb-5",
    ]}
  >
    <div class="relative flex items-center gap-4">
      <button
        type="button"
        onclick={onToggle}
        class="absolute inset-0 w-full"
        aria-label={`Fold message from ${sender}`}
      ></button>
      <span class="pointer-events-none">
        <Avatar person={message.from} size={36} />
      </span>
      <span class="flex-1 min-w-0 leading-5 pointer-events-none">
        <span class="flex items-center gap-2 min-w-0">
          <span class="font-semibold truncate">{sender}</span>
          {#if showAddress}
            <span class="max-sm:hidden text-[13px] text-text-secondary truncate"
              >{message.from.email}</span
            >
          {/if}
        </span>
        <!-- On a phone the address gets its own line, so a long name and address don't both cut short. -->
        {#if showAddress}
          <span class="sm:hidden block truncate text-[13px] text-text-secondary"
            >{message.from.email}</span
          >
        {/if}
        {#if recipients}
          <span class="block truncate text-[13px] text-text-secondary">
            to {recipients}
          </span>
        {/if}
      </span>
      {#if badge}
        <span
          class="shrink-0 flex items-center gap-1 text-done pointer-events-none"
          title={badge.label}
        >
          <Lock class="w-3.5 h-3.5" role="img" aria-label={badge.label} />
          {#if badge.signed}
            <Check class="w-3.5 h-3.5" aria-hidden="true" />
          {/if}
        </span>
      {/if}
      <span
        class="text-[13px] text-text-secondary tabular-nums shrink-0 pointer-events-none"
      >
        {formatMessageDate(message.date, mail.now)}
      </span>
    </div>
    {#if unsubscribe}
      {@render unsubscribeBar(false)}
    {/if}
    {#if badge?.warning}
      <div
        role="alert"
        class="mt-3 sm:ml-[52px] flex items-center gap-2 px-3 py-2 rounded-xl bg-danger-soft text-danger text-[13px] font-medium"
      >
        <ShieldAlert class="w-4 h-4 shrink-0" aria-hidden="true" />
        {badge.warning}
      </div>
    {/if}
    {#if message.body.calendar && !preview}
      {@const part = message.body.calendar}
      <div class="mt-3 sm:ml-[52px]">
        {#await import("./invite-card.svelte") then { default: InviteCard }}
          <InviteCard {message} {part} />
        {/await}
      </div>
    {/if}
    {#if badge?.problem}
      <div
        class="mt-4 sm:ml-[52px] min-h-11 flex flex-wrap items-center gap-3 py-1.5 pl-3 pr-1.5 rounded-xl border border-border bg-bg text-[13px] text-text-secondary"
      >
        <Lock class="w-4 h-4 shrink-0" aria-hidden="true" />
        <span class="flex-1 min-w-48">{badge.problem}</span>
        {#if badge.unlock && mail.encryption.hasKey}
          <button
            type="button"
            onclick={() => mail.encryption.requestUnlock()}
            class="h-8 px-3 rounded-full bg-accent-soft text-accent-strong font-semibold"
          >
            Unlock encrypted mail
          </button>
        {/if}
      </div>
    {:else}
      <div class="mt-4 sm:ml-[52px]">
        <MessageBody {message} />
      </div>
    {/if}
    {#if message.body.attachments.length > 0 && !preview}
      <div class="mt-4 sm:ml-[52px] flex flex-wrap gap-2">
        {#each message.body
          .attachments as attachment, index (`${index}-${attachment.filename}`)}
          <AttachmentCard messageId={message.id} {attachment} />
        {/each}
      </div>
    {/if}
  </div>
{:else}
  <button
    type="button"
    onclick={onToggle}
    class="w-full h-12 flex items-center gap-3 px-3 sm:px-6 border-t border-separator text-left hover:bg-surface-muted transition-colors"
  >
    <Avatar person={message.from} size={28} text="text-xs" />
    <span class="w-28 sm:w-40 shrink-0 font-semibold truncate">{sender}</span>
    <span class="flex-1 min-w-0 truncate text-text-secondary">
      {#if badge}
        <Lock
          class="inline w-3.5 h-3.5 -mt-0.5 mr-1 text-done"
          role="img"
          aria-label="Encrypted"
        />
      {/if}
      {message.snippet || (badge?.problem ?? "")}
    </span>
    {#if message.body.attachments.length > 0}
      <Paperclip
        class="w-4 h-4 text-muted shrink-0"
        aria-label="Has attachments"
      />
    {/if}
    <span
      class="w-12 shrink-0 text-right text-[13px] text-text-secondary tabular-nums"
    >
      {formatListDate(message.date, mail.now)}
    </span>
  </button>
{/if}
