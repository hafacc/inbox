<!-- @component An attachment: an image as a thumbnail that opens full screen, a PDF as a card that opens in a new tab, anything else as a card with its size and Download. Images load from Gmail only once the card scrolls into view. Offline, a fetch that fails shows the card with its name and size and says it opens once back online; it loads as usual then. -->
<script lang="ts">
  import { type Attachment, decodeBase64Url } from "@inbox/shared/mime";
  import { formatSize } from "@inbox/shared/time-format";
  import Download from "@lucide/svelte/icons/download";
  import FileText from "@lucide/svelte/icons/file-text";
  import ImageIcon from "@lucide/svelte/icons/image";
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import type { Attachment as Attach } from "svelte/attachments";
  import { isNetworkError } from "../gmail/request";
  import { getMailState } from "../mail-state.svelte";
  import { OPENS_ONLINE } from "../sanitize";
  import ImageViewer from "./image-viewer.svelte";

  let { messageId, attachment }: { messageId: string; attachment: Attachment } =
    $props();

  // Image types every browser draws; others (SVG among them) stay files.
  const PREVIEW_TYPES = new Set([
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "image/avif",
  ]);
  // Larger images aren't fetched just to show a thumbnail.
  const PREVIEW_MAX_BYTES = 15 * 1024 * 1024;

  const mail = getMailState();
  let busy = $state(false);
  let failed = $state(false);
  let thumbnail = $state<string | null>(null);
  // A thumbnail that couldn't load shows the attachment as a file instead.
  let previewFailed = $state(false);
  // A fetch failed for want of a network; cleared once the app is back online, which retries a thumbnail.
  let waitingOnline = $state(false);
  let viewing = $state(false);
  const mimeType = $derived(attachment.mimeType.toLowerCase());
  const kind = $derived(
    (attachment.filename.split(".").pop() ?? attachment.mimeType).toUpperCase(),
  );
  const image = $derived(
    PREVIEW_TYPES.has(mimeType) && attachment.size <= PREVIEW_MAX_BYTES,
  );
  const pdf = $derived(
    mimeType === "application/pdf" ||
      attachment.filename.toLowerCase().endsWith(".pdf"),
  );

  async function bytes(): Promise<Uint8Array | null> {
    if (attachment.data) return decodeBase64Url(attachment.data);
    else if (attachment.attachmentId) {
      return mail.attachmentBytes(
        messageId,
        attachment.partId,
        attachment.attachmentId,
      );
    } else return null;
  }

  async function blobUrl(type: string): Promise<string | null> {
    const data = await bytes();
    if (!data) return null;
    return URL.createObjectURL(
      new Blob([data as Uint8Array<ArrayBuffer>], { type }),
    );
  }

  // Whether a failed fetch was the network; then the card waits for the app to be back online.
  function offlineFailure(error: unknown): boolean {
    if (!isNetworkError(error)) return false;
    waitingOnline = true;
    return true;
  }

  $effect(() => {
    if (!mail.offline) waitingOnline = false;
  });

  async function download() {
    busy = true;
    failed = false;
    try {
      const url = await blobUrl(attachment.mimeType);
      if (!url) return;
      const link = document.createElement("a");
      link.href = url;
      link.download = attachment.filename;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
      waitingOnline = false;
    } catch (error) {
      if (!offlineFailure(error)) {
        console.error(error);
        failed = true;
      }
    } finally {
      busy = false;
    }
  }

  // Opened before the fetch, so the browser counts it as the tap's own window.
  async function openPdf() {
    const tab = window.open("", "_blank");
    // A blocked popup saves the file instead; going to it here would leave the app.
    if (!tab) {
      await download();
      return;
    }
    busy = true;
    failed = false;
    try {
      const url = await blobUrl("application/pdf");
      if (!url) {
        tab.close();
        return;
      }
      tab.opener = null;
      tab.location.href = url;
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
      waitingOnline = false;
    } catch (error) {
      tab.close();
      if (!offlineFailure(error)) {
        console.error(error);
        failed = true;
      }
    } finally {
      busy = false;
    }
  }

  async function loadThumbnail() {
    if (thumbnail !== null || previewFailed) return;
    try {
      thumbnail = await blobUrl(mimeType);
      if (thumbnail === null) previewFailed = true;
    } catch (error) {
      if (!offlineFailure(error)) {
        console.error(error);
        previewFailed = true;
      }
    }
  }

  $effect(() => {
    const url = thumbnail;
    return () => {
      if (url !== null) URL.revokeObjectURL(url);
    };
  });

  // Calls `onVisible` once, when the element first comes near the screen.
  function whenVisible(onVisible: () => void): Attach<HTMLElement> {
    return (node) => {
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            observer.disconnect();
            onVisible();
          }
        },
        { rootMargin: "200px" },
      );
      observer.observe(node);
      return () => observer.disconnect();
    };
  }

  const fileCard =
    "w-[296px] max-w-full h-14 flex items-center gap-3 pl-2 pr-1 rounded-full border border-border";
  const iconBadge =
    "w-10 h-10 shrink-0 rounded-full bg-danger-soft text-danger flex items-center justify-center";
  const roundButton =
    "w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover hover:text-text transition-colors";
</script>

{#if image && !previewFailed && !waitingOnline}
  <button
    type="button"
    {@attach whenVisible(() => void loadThumbnail())}
    onclick={() => {
      if (thumbnail !== null) viewing = true;
    }}
    aria-label={`View ${attachment.filename}`}
    title={attachment.filename}
    class="relative w-[160px] h-[120px] shrink-0 overflow-hidden rounded-[20px] border border-border bg-surface-muted"
  >
    {#if thumbnail !== null}
      <img
        src={thumbnail}
        alt=""
        class="w-full h-full object-cover animate-fade-in"
      >
    {:else}
      <span
        class="absolute inset-0 flex items-center justify-center text-text-secondary animate-shimmer"
      >
        <ImageIcon class="w-6 h-6" aria-hidden="true" />
      </span>
    {/if}
  </button>
  {#if viewing && thumbnail !== null}
    <ImageViewer
      src={thumbnail}
      filename={attachment.filename}
      onDownload={download}
      onClose={() => {
        viewing = false;
      }}
    />
  {/if}
{:else if pdf}
  <div class={fileCard}>
    <button
      type="button"
      onclick={openPdf}
      disabled={busy}
      aria-label={`Open ${attachment.filename}`}
      class="flex-1 min-w-0 h-full flex items-center gap-3 text-left rounded-full"
    >
      <span class={iconBadge}>
        {#if busy}
          <LoaderCircle class="w-4 h-4 animate-spin" aria-hidden="true" />
        {:else}
          <FileText class="w-4 h-4" aria-hidden="true" />
        {/if}
      </span>
      <span class="flex-1 min-w-0 leading-[18px]">
        <span class="block truncate text-[13px] font-medium"
          >{attachment.filename}</span
        >
        <span class="block truncate text-xs text-text-secondary">
          {failed
            ? "Couldn't open it"
            : waitingOnline
              ? `${formatSize(attachment.size)} · ${OPENS_ONLINE}`
              : `${formatSize(attachment.size)} · PDF`}
        </span>
      </span>
    </button>
    <button
      type="button"
      onclick={download}
      disabled={busy}
      aria-label={`Download ${attachment.filename}`}
      class={roundButton}
    >
      <Download class="w-4 h-4" />
    </button>
  </div>
{:else}
  <div class={fileCard}>
    <span class={iconBadge}>
      <FileText class="w-4 h-4" aria-hidden="true" />
    </span>
    <span class="flex-1 min-w-0 leading-[18px]">
      <span class="block truncate text-[13px] font-medium"
        >{attachment.filename}</span
      >
      <span class="block truncate text-xs text-text-secondary">
        {failed
          ? "Download failed"
          : waitingOnline
            ? `${formatSize(attachment.size)} · ${OPENS_ONLINE}`
            : `${formatSize(attachment.size)} · ${kind}`}
      </span>
    </span>
    <button
      type="button"
      onclick={download}
      disabled={busy}
      aria-label={`Download ${attachment.filename}`}
      class={roundButton}
    >
      {#if busy}
        <LoaderCircle class="w-4 h-4 animate-spin" />
      {:else}
        <Download class="w-4 h-4" />
      {/if}
    </button>
  </div>
{/if}
