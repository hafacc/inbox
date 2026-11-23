<!-- @component A message's body: sanitized HTML in a sandboxed frame with remote images held back until shown (then loaded straight from the sender), or plain text with its web addresses as links; a quoted earlier message is folded behind a "…" button. -->
<script lang="ts">
  import { splitQuotedHtml, splitQuotedText } from "@inbox/shared/compose";
  import { webLinkPieces } from "@inbox/shared/web-links";
  import ImageOff from "@lucide/svelte/icons/image-off";
  import { untrack } from "svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { showsImagesFrom } from "../images/image-settings";
  import { getMailState } from "../mail-state.svelte";
  import { openMark, openTimed } from "../open-timing";
  import {
    type CleanHtml,
    cleanMessageHtml,
    missingPartLabel,
  } from "../sanitize";
  import type { MessageView } from "../thread-detail";
  import HtmlFrame from "./html-frame.svelte";
  import { inPreview } from "./preview";
  import QuoteToggle from "./quote-toggle.svelte";

  let { message }: { message: MessageView } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  // A neighbor drawn beside the open thread loads no images, remote or inline.
  const preview = inPreview();
  let showImages = $state(false);
  let showQuote = $state(false);
  // Inline images already at hand, so the first frame has them rather than a second one.
  let cidUrls = $state.raw<ReadonlyMap<string, string>>(
    untrack(() =>
      preview
        ? new Map()
        : mail.knownInlineUrls(message.id, message.body.inline),
    ),
  );
  // Inline images that couldn't be fetched offline, by content id, to their placeholder's text.
  let cidMissing = $state.raw<ReadonlyMap<string, string>>(new Map());

  // Runs again when the app comes back online, so images missed offline load then.
  $effect(() => {
    const inline = message.body.inline;
    void mail.offline;
    if (preview || inline.length === 0 || message.body.html === null) return;
    let canceled = false;
    const fetching = performance.now();
    void Promise.all(
      inline.map(async (part) => {
        try {
          return {
            part,
            url: await mail.inlineDataUrl(message.id, part),
            offline: false,
          };
        } catch {
          return { part, url: null, offline: true };
        }
      }),
    ).then((entries) => {
      openMark(
        `${inline.length} inline images fetched`,
        performance.now() - fetching,
      );
      if (canceled) return;
      const urls = new Map<string, string>();
      const missing = new Map<string, string>();
      for (const { part, url, offline } of entries) {
        if (url) urls.set(part.contentId, url);
        else if (offline) {
          missing.set(
            part.contentId,
            missingPartLabel(part.filename, part.size),
          );
        }
      }
      // Unchanged maps aren't set again, which would clean the message a second time.
      if (!sameEntries(urls, cidUrls)) cidUrls = urls;
      if (!sameEntries(missing, cidMissing)) cidMissing = missing;
    });
    return () => {
      canceled = true;
    };
  });

  function sameEntries(
    next: ReadonlyMap<string, string>,
    current: ReadonlyMap<string, string>,
  ): boolean {
    return (
      next.size === current.size &&
      [...next].every(([key, value]) => current.get(key) === value)
    );
  }

  const htmlSplit = $derived(
    message.body.html === null ? null : splitQuotedHtml(message.body.html),
  );
  const textSplit = $derived(
    message.body.text === null ? null : splitQuotedText(message.body.text),
  );
  const hasQuote = $derived(
    htmlSplit !== null ? htmlSplit.quote !== null : textSplit?.quote != null,
  );
  const shownHtml = $derived(
    htmlSplit === null || message.body.html === null
      ? null
      : showQuote
        ? message.body.html
        : htmlSplit.body,
  );
  const clean = $derived<CleanHtml | null>(
    shownHtml === null
      ? null
      : openTimed(`message cleaned, ${shownHtml.length} chars`, () =>
          cleanMessageHtml(shownHtml, cidUrls, cidMissing),
        ),
  );
  const imagesShown = $derived(
    !preview &&
      (showImages || showsImagesFrom(hub.settings.images, message.from.email)),
  );
</script>

{#if clean}
  {#if clean.hasRemoteImages && !imagesShown}
    <div
      class="mb-2 h-9 flex items-center gap-2 pl-3 pr-1 rounded-xl max-sm:rounded-lg bg-surface-muted text-[13px] text-text-secondary"
    >
      <ImageOff class="w-4 h-4 shrink-0" aria-hidden="true" />
      <span class="flex-1 truncate">Images hidden to block tracking</span>
      <button
        type="button"
        onclick={() => (showImages = true)}
        class="h-7 px-2 rounded-full text-[13px] font-medium text-accent-strong hover:bg-surface-hover transition-colors"
      >
        Show images
      </button>
      {#if message.from.email}
        <span aria-hidden="true">·</span>
        <button
          type="button"
          onclick={() => {
            showImages = true;
            void hub.settings
              .setSenderImages(message.from.email, true)
              .catch((error) =>
                console.error("image setting write failed", error),
              );
          }}
          class="h-7 px-2 rounded-full text-[13px] font-medium text-accent-strong hover:bg-surface-hover transition-colors"
        >
          Always show from this sender
        </button>
      {/if}
    </div>
  {/if}
  <HtmlFrame
    html={clean.html}
    showImages={imagesShown}
    colorSchemes={clean.colorSchemes}
    title={`Message from ${message.from.name || message.from.email}`}
  />
  {#if hasQuote}
    <QuoteToggle bind:open={showQuote} />
  {/if}
{:else if message.body.text !== null && textSplit !== null}
  <div
    class="max-w-[640px] whitespace-pre-wrap break-words text-sm leading-[22px]"
  >
    {#each webLinkPieces(
      (showQuote ? message.body.text : textSplit.body).trimEnd(),
    ) as piece, index (index)}
      {#if piece.href === null}
        {piece.text}
      {:else}
        <a
          href={piece.href}
          target="_blank"
          rel="noopener noreferrer"
          class="text-accent-strong underline underline-offset-2"
          >{piece.text}</a
        >
      {/if}
    {/each}
  </div>
  {#if hasQuote}
    <QuoteToggle bind:open={showQuote} />
  {/if}
{:else}
  <div class="text-sm text-text-secondary">This message has no text.</div>
{/if}
