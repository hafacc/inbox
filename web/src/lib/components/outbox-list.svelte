<!-- @component Outbox: every message not sent yet. Sends that failed (with what to do about them), those waiting for a sign-in or a server fix, those being sent or retried or held on this device, then scheduled ones by when they go. -->
<script lang="ts">
  import { decodeEntities } from "@inbox/shared/mime";
  import { HELD_SEND_STATUS } from "@inbox/shared/outbox-held";
  import {
    futureSection,
    type SectionHead,
    sectionize,
  } from "@inbox/shared/sections";
  import { formatWakeTime } from "@inbox/shared/time-format";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Clock from "@lucide/svelte/icons/clock";
  import RefreshCw from "@lucide/svelte/icons/refresh-cw";
  import Send from "@lucide/svelte/icons/send";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { recipientsLine } from "../compose/compose-model";
  import {
    blockedText,
    type DraftPreview,
    failureView,
    type HeldSend,
    isUnderWay,
    type OutboxDoc,
    retryView,
  } from "../compose/outbox";
  import { getMailState } from "../mail-state.svelte";
  import Avatar from "./avatar.svelte";

  const hub = getComposeHub();
  const mail = getMailState();

  const FAILED_HEAD: SectionHead = { key: "failed", title: "Couldn't send" };
  const BLOCKED_HEAD: SectionHead = { key: "blocked", title: "On hold" };
  const UNDER_WAY_HEAD: SectionHead = { key: "under-way", title: "Sending" };

  type Item =
    | { kind: "doc"; key: string; entry: OutboxDoc }
    | { kind: "held"; key: string; held: HeldSend };

  // Failed, then blocked, then under way (held sends due now among them), then by send time.
  function rankOf(item: Item): number {
    if (item.kind === "held") {
      return item.held.now || item.held.sendAt <= mail.now ? 2 : 3;
    } else if (item.entry.status === "failed") {
      return 0;
    } else if (item.entry.status === "blocked") {
      return 1;
    } else {
      return isUnderWay(item.entry, mail.now) ? 2 : 3;
    }
  }

  function sendAtOf(item: Item): number {
    return item.kind === "held" ? item.held.sendAt : item.entry.sendAt;
  }

  const items = $derived(
    [
      ...hub.pending.map(
        (entry): Item => ({ kind: "doc", key: entry.id, entry }),
      ),
      ...hub.held.map(
        (held): Item => ({ kind: "held", key: `held:${held.localId}`, held }),
      ),
    ].sort(
      (first, second) =>
        rankOf(first) - rankOf(second) ||
        (rankOf(first) === 3 ? sendAtOf(first) - sendAtOf(second) : 0),
    ),
  );

  const sections = $derived(
    sectionize(items, (item) => {
      const rank = rankOf(item);
      return rank === 0
        ? FAILED_HEAD
        : rank === 1
          ? BLOCKED_HEAD
          : rank === 2
            ? UNDER_WAY_HEAD
            : futureSection(sendAtOf(item), mail.now);
    }),
  );

  const action =
    "h-8 flex items-center gap-1.5 px-2.5 rounded-full text-[13px] font-medium text-text-secondary hover:bg-surface-hover hover:text-text transition-colors";
  const linkButton =
    "font-semibold text-accent-strong hover:underline disabled:opacity-50";
</script>

{#snippet recipients(preview: DraftPreview | undefined)}
  {@const first = preview?.to[0] ?? preview?.bcc[0] ?? { name: "", email: "?" }}
  <Avatar person={first} />
  <div class="w-44 shrink-0 truncate">
    <span class="text-text-secondary">To </span
    ><span class="font-semibold"
      >{preview
        ? recipientsLine(preview.to) ||
          (preview.bcc.length > 0
            ? `${recipientsLine(preview.bcc)} (Bcc)`
            : "(no recipients)")
        : "…"}</span
    >
  </div>
  <div class="flex-1 min-w-0 truncate">
    <span class="font-medium">{preview?.subject || "(no subject)"}</span>
    {#if preview?.snippet}
      <span class="text-text-secondary">
        — {decodeEntities(preview.snippet)}</span
      >
    {/if}
  </div>
{/snippet}

{#snippet heldRow(held: HeldSend)}
  <li class="group/row bg-surface">
    <div class="h-14 flex items-center gap-4 px-4">
      {@render recipients(held.preview ?? undefined)}
      <div
        class="hidden group-hover/row:flex group-focus-within/row:flex items-center gap-1"
      >
        <button
          type="button"
          class={action}
          onclick={() => hub.cancelHeld(held)}
        >
          Cancel
        </button>
      </div>
      <span
        class="shrink-0 text-[13px] text-text-secondary truncate max-w-48 group-hover/row:hidden group-focus-within/row:hidden"
      >
        <span>From</span> <span class="text-text">{held.from}</span>
      </span>
      <span
        class="shrink-0 text-right text-[13px] text-text-secondary whitespace-nowrap"
      >
        {HELD_SEND_STATUS}
      </span>
    </div>
  </li>
{/snippet}

{#snippet row(entry: OutboxDoc)}
  {@const preview = hub.previews.get(entry.draftId)}
  {@const failed = entry.status === "failed"}
  {@const failure = failed ? failureView(entry.error) : null}
  {@const blocked = blockedText(entry)}
  {@const settled = failed || blocked !== null}
  {@const underWay = isUnderWay(entry, mail.now)}
  {@const retry = retryView(entry, mail.now)}
  <li class="group/row bg-surface">
    <div class="h-14 flex items-center gap-4 px-4">
      {@render recipients(preview)}
      {#if !settled}
        <div
          class="hidden group-hover/row:flex group-focus-within/row:flex items-center gap-1"
        >
          {#if !underWay}
            <button
              type="button"
              class={action}
              onclick={() => hub.sendScheduledNow(entry)}
            >
              <Send class="w-4 h-4" aria-hidden="true" />
              Send now
            </button>
          {/if}
          <button
            type="button"
            class={action}
            onclick={() => hub.editScheduled(entry)}
          >
            {underWay ? "Open" : "Edit"}
          </button>
          <button
            type="button"
            class={action}
            title={retry?.mayHaveGone || entry.status === "sending"
              ? "It may have gone already"
              : undefined}
            onclick={() => hub.cancelSend(entry)}
          >
            Cancel
          </button>
        </div>
      {/if}
      <span
        class={[
          "shrink-0 text-[13px] text-text-secondary truncate max-w-48",
          !settled && "group-hover/row:hidden group-focus-within/row:hidden",
        ]}
      >
        <span>From</span> <span class="text-text">{entry.from}</span>
      </span>
      <span
        class={[
          "w-24 shrink-0 text-right text-[13px] tabular-nums",
          failed
            ? "text-danger"
            : underWay || blocked !== null
              ? "text-text-secondary"
              : "text-accent-strong font-medium",
        ]}
      >
        {blocked !== null
          ? "Not sent yet"
          : underWay
            ? entry.status === "retrying"
              ? "Not sent yet"
              : "Sending…"
            : formatWakeTime(entry.sendAt, mail.now)}
      </span>
    </div>
    {#if retry}
      <div
        role="status"
        class="mx-4 mb-3 -mt-1 ml-16 min-h-9 flex items-center gap-2 px-3 py-1.5 rounded-xl bg-surface-muted text-[13px]"
      >
        <RefreshCw
          class="w-4 h-4 shrink-0 text-text-secondary"
          aria-hidden="true"
        />
        <span class="shrink-0">{retry.headline}</span>
        <span
          class="flex-1 min-w-0 truncate text-text-secondary"
          title={retry.reason}
          >{retry.reason}</span
        >
      </div>
    {/if}
    {#if blocked !== null}
      <div
        role="status"
        class="mx-4 mb-3 -mt-1 ml-16 h-9 flex items-center gap-2 px-3 rounded-xl bg-surface-muted text-[13px]"
      >
        <Clock
          class="w-4 h-4 shrink-0 text-text-secondary"
          aria-hidden="true"
        />
        <span class="flex-1 min-w-0 truncate" title={blocked}
          >{blocked}. It goes by itself after that.</span
        >
        <button
          type="button"
          class={linkButton}
          onclick={() => hub.sendScheduledNow(entry)}
        >
          Send again
        </button>
        <span aria-hidden="true">·</span>
        <button
          type="button"
          class={linkButton}
          onclick={() => hub.editScheduled(entry)}
        >
          Edit
        </button>
        <span aria-hidden="true">·</span>
        <button
          type="button"
          class={linkButton}
          onclick={() => hub.cancelSend(entry)}
        >
          Cancel
        </button>
      </div>
    {/if}
    {#if failure}
      <div
        role="alert"
        class="mx-4 mb-3 -mt-1 ml-16 h-9 flex items-center gap-2 px-3 rounded-xl bg-danger-soft text-[13px] text-danger"
      >
        <CircleAlert class="w-4 h-4 shrink-0" aria-hidden="true" />
        <span class="flex-1 min-w-0 truncate" title={failure.message}
          >Couldn't send — {failure.message}</span
        >
        {#if failure.canRetry}
          <button
            type="button"
            class={linkButton}
            onclick={() => hub.sendScheduledNow(entry)}
          >
            Send again
          </button>
        {/if}
        {#if failure.canRetry && failure.canEdit}
          <span aria-hidden="true">·</span>
        {/if}
        {#if failure.canEdit}
          <button
            type="button"
            class={linkButton}
            onclick={() => hub.editScheduled(entry)}
          >
            Edit
          </button>
          <span aria-hidden="true">·</span>
          <button
            type="button"
            class={linkButton}
            onclick={() => hub.cancelSend(entry)}
          >
            Cancel
          </button>
        {:else}
          <button
            type="button"
            class={linkButton}
            onclick={() => hub.dismissFailed(entry)}
          >
            Dismiss
          </button>
        {/if}
      </div>
    {/if}
  </li>
{/snippet}

{#if sections.length === 0}
  <p class="pt-16 text-center text-text-secondary">
    Nothing waiting to go. Schedule a message from the arrow next to Send.
  </p>
{:else}
  {#each sections as section (section.key)}
    <section aria-label={section.title} class="mb-2">
      <h2
        class="h-12 flex items-end pb-2 px-1 text-[13px] font-semibold text-text-secondary"
      >
        {section.title}
      </h2>
      <ul
        class="flex flex-col gap-px bg-border border border-border rounded-xl overflow-hidden"
      >
        {#each section.items as item (item.key)}
          {#if item.kind === "held"}
            {@render heldRow(item.held)}
          {:else}
            {@render row(item.entry)}
          {/if}
        {/each}
      </ul>
    </section>
  {/each}
{/if}
