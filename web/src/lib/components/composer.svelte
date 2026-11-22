<!-- @component A message being written: floating for a new message, or in place inside the thread card for a reply or forward. Body with `*bold*` and `_italic_` styles, a light toolbar, files, From, recipients, the quoted message folded under "…", Send with undo and schedule send; read-only while its send is under way. At phone width it covers the screen, and dragging its top bar down (or Minimize) parks it in a strip at the bottom naming its subject; a tap on the strip opens it again as it was. -->
<script lang="ts">
  import { addressKey, markdownToHtml, quoteHtml } from "@inbox/shared/compose";
  import {
    PARK_STRIP_HEIGHT,
    parksOnRelease,
    parkTitle,
  } from "@inbox/shared/compose-park";
  import { formatSize, formatWakeTime } from "@inbox/shared/time-format";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import Bold from "@lucide/svelte/icons/bold";
  import CalendarClock from "@lucide/svelte/icons/calendar-clock";
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import ChevronUp from "@lucide/svelte/icons/chevron-up";
  import CircleAlert from "@lucide/svelte/icons/circle-alert";
  import Clock from "@lucide/svelte/icons/clock";
  import Eye from "@lucide/svelte/icons/eye";
  import FileIcon from "@lucide/svelte/icons/file";
  import ImageIcon from "@lucide/svelte/icons/image";
  import Italic from "@lucide/svelte/icons/italic";
  import Lock from "@lucide/svelte/icons/lock";
  import LockOpen from "@lucide/svelte/icons/lock-open";
  import Maximize2 from "@lucide/svelte/icons/maximize-2";
  import Minimize2 from "@lucide/svelte/icons/minimize-2";
  import Minus from "@lucide/svelte/icons/minus";
  import Paperclip from "@lucide/svelte/icons/paperclip";
  import PencilLine from "@lucide/svelte/icons/pencil-line";
  import RefreshCw from "@lucide/svelte/icons/refresh-cw";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import X from "@lucide/svelte/icons/x";
  import { flushSync, onMount, untrack } from "svelte";
  import { prefersReducedMotion } from "svelte/motion";
  import { pushBack } from "../back-stack.svelte";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import {
    type ComposeImage,
    randomId,
    referencedImages,
    sendProblem,
  } from "../compose/compose-model";
  import type { Composer } from "../compose/composer.svelte";
  import { encryptionNote, isEncryptingAddress } from "../compose/encrypt-send";
  import {
    type FormatCommand,
    imageMarkdown,
    withoutImage,
  } from "../compose/markdown-edit";
  import {
    blockedText,
    failureView,
    isLocked,
    retryView,
  } from "../compose/outbox";
  import { swallowReleaseClick } from "../hold-menu";
  import { isCommandChord, isMacPlatform } from "../keyboard";
  import { getMailState } from "../mail-state.svelte";
  import { cleanMessageHtml } from "../sanitize";
  import { chordTitle } from "../shortcuts";
  import FromPicker from "./from-picker.svelte";
  import HtmlFrame from "./html-frame.svelte";
  import { iconButton } from "./icon-button";
  import { phoneWidth } from "./phone";
  import QuoteToggle from "./quote-toggle.svelte";
  import RecipientField from "./recipient-field.svelte";
  import RichEditor from "./rich-editor.svelte";
  import SendMenu from "./send-menu.svelte";

  let {
    composer,
    variant,
    parked = false,
  }: {
    /** the message being written */
    composer: Composer;
    /** floating for a new message, inline inside the thread for an answer */
    variant: "floating" | "inline";
    /** whether it is parked in the strip at the bottom of a phone's screen */
    parked?: boolean;
  } = $props();

  const hub = getComposeHub();
  const mail = getMailState();
  const commandKey = isMacPlatform() ? "⌘" : "Ctrl+";
  let editor = $state<RichEditor>();
  let imagePicker = $state<HTMLInputElement>();
  let filePicker = $state<HTMLInputElement>();
  let menuOpen = $state(false);
  let preview = $state(false);
  let minimized = $state(false);
  // A new message takes the whole screen at phone width, where Back closes it.
  const fullScreen = $derived(variant === "floating" && phoneWidth.current);
  const folded = $derived(minimized && !fullScreen);
  let expanded = $state(false);
  let dragging = $state(false);
  let quoteOpen = $state(false);

  const title = $derived(
    composer.mode === "forward"
      ? "Forward"
      : composer.subject.trim() || "New message",
  );
  const stripTitle = $derived(
    parkTitle(
      composer.subject,
      composer.mode === "new"
        ? "new"
        : composer.mode === "forward"
          ? "forward"
          : "reply",
    ),
  );
  const encryption = $derived(hub.encryptionOf(composer));
  // Locked still goes encrypted: Send asks to unlock first, unless the user chose otherwise.
  const encrypted = $derived(
    encryption.kind === "encrypted" ||
      (encryption.kind === "locked" && !composer.sendPlain),
  );
  const encryptionLine = $derived(
    encryptionNote(encryption, composer.sendPlain),
  );
  // Keys of every recipient, looked up in the background for the locks on their chips.
  $effect(() => {
    if (!isEncryptingAddress(composer.from)) return;
    hub.lookUpKeys(
      [...composer.to, ...composer.cc, ...composer.bcc].map(
        (mailbox) => mailbox.email,
      ),
    );
  });
  const keyed = (email: string): boolean =>
    isEncryptingAddress(composer.from) &&
    typeof hub.recipientKeys.get(addressKey(email)) === "string";
  // A message opened from the Outbox keeps its schedule (or shows why it failed) while edited.
  const outboxEntry = $derived(hub.outboxOf(composer));
  // While its send is under way the message can't change; canceling the send unlocks it.
  const locked = $derived(isLocked(outboxEntry));
  const retry = $derived(
    outboxEntry === null ? null : retryView(outboxEntry, mail.now),
  );
  const scheduled = $derived(
    outboxEntry?.status === "waiting" ? outboxEntry : null,
  );
  const failure = $derived(
    outboxEntry?.status === "failed" ? failureView(outboxEntry.error) : null,
  );
  const blocked = $derived(
    outboxEntry === null ? null : blockedText(outboxEntry),
  );
  const showSubject = $derived(
    variant === "floating" || composer.mode === "forward",
  );
  // Object URLs of the inline images, made and freed with the images they show.
  let imageUrls = $state.raw<ReadonlyMap<string, string>>(new Map());
  const shownImages = $derived(
    referencedImages(composer.markdown, composer.inlineImages),
  );
  const quotedHtml = $derived(
    composer.quote !== null && quoteOpen
      ? cleanMessageHtml(quoteHtml(composer.quote), imageUrls).html
      : "",
  );
  const previewHtml = $derived(
    preview
      ? cleanMessageHtml(
          composer.html ?? markdownToHtml(composer.markdown),
          imageUrls,
        ).html
      : "",
  );
  const signatureHtml = $derived(
    hub.settings.send.signature.trim() === ""
      ? ""
      : cleanMessageHtml(markdownToHtml(hub.settings.send.signature), new Map())
          .html,
  );
  const statusText = $derived.by(() => {
    if (locked) return "";
    if (composer.loadingFiles > 0) return "Adding files…";
    switch (composer.saveStatus) {
      case "saving":
        return "Saving…";
      case "saved":
        return "Saved";
      case "queued":
        return "Saved on this device";
      case "failed":
        return "Not saved";
      default:
        return "";
    }
  });

  $effect.pre(() => {
    const urls = new Map(
      composer.inlineImages.map((image) => [
        image.contentId,
        URL.createObjectURL(
          new Blob([image.data as BlobPart], { type: image.mimeType }),
        ),
      ]),
    );
    imageUrls = urls;
    return () => {
      for (const url of urls.values()) URL.revokeObjectURL(url);
    };
  });

  $effect(() => {
    if (!fullScreen || parked) return;
    return untrack(() => pushBack(() => void hub.close(composer)));
  });

  // Everything at the bottom of the screen sits above the strip while it shows (app.css `--safe-bottom`).
  $effect(() => {
    if (!parked) return;
    document.documentElement.dataset.parked = "";
    return () => {
      delete document.documentElement.dataset.parked;
    };
  });

  const PARK_MS = 220;
  const PARK_EASING = "cubic-bezier(0.33, 1, 0.68, 1)";
  // How far the top bar must move before a press on it counts as a drag.
  const DRAG_SLOP = 8;
  let form = $state<HTMLFormElement>();
  let topBar = $state<HTMLDivElement>();
  // The field that last held the cursor, which gets it back when the message opens again.
  let lastFocus: HTMLElement | null = null;
  let strip = $state<HTMLButtonElement>();
  let barPress: { pointerId: number; startY: number } | null = null;
  let barDragging = false;
  let barDragged = 0;
  let barSamples: { y: number; time: number }[] = [];
  // How long after a drag of the top bar lets go its trailing click is swallowed: it would act on
  // whatever the drag started on.
  const RELEASE_CLICK_MS = 400;
  let parking = false;

  // How far down the composer moves for its top bar to sit where the strip shows.
  function parkOffset(): number {
    if (!form || !topBar) return 0;
    const bottomInset = Number.parseFloat(getComputedStyle(form).paddingBottom);
    return (
      form.clientHeight - bottomInset - PARK_STRIP_HEIGHT - topBar.offsetTop
    );
  }

  function moveTo(from: number, to: number): Animation | null {
    if (!form || prefersReducedMotion.current) return null;
    return form.animate(
      [
        { transform: `translateY(${from}px)` },
        { transform: to === 0 ? "none" : `translateY(${to}px)` },
      ],
      { duration: PARK_MS, easing: PARK_EASING, fill: "forwards" },
    );
  }

  async function park(from = 0) {
    if (parking || parked || !fullScreen) return;
    parking = true;
    const active = document.activeElement;
    // Closes the on-screen keyboard.
    if (active instanceof HTMLElement && form?.contains(active)) active.blur();
    const motion = moveTo(from, parkOffset());
    if (form) form.style.transform = "";
    await motion?.finished.catch(() => {});
    hub.park(composer);
    flushSync();
    motion?.cancel();
    parking = false;
    // Keyboard and screen reader users carry on from the strip.
    strip?.focus({ preventScroll: true });
  }

  function openAgain() {
    hub.unpark();
    // Shown and focused inside the tap, or iOS won't raise the keyboard.
    flushSync();
    if (lastFocus?.isConnected && !editor?.contains(lastFocus)) {
      lastFocus.focus({ preventScroll: true });
    } else {
      editor?.focus();
    }
    const motion = moveTo(parkOffset(), 0);
    void motion?.finished.then(
      () => motion.cancel(),
      () => {},
    );
  }

  function onBarPointerDown(event: PointerEvent) {
    if (!fullScreen || parked || parking || event.button !== 0) return;
    barPress = { pointerId: event.pointerId, startY: event.clientY };
    barDragging = false;
    barDragged = 0;
    barSamples = [{ y: event.clientY, time: event.timeStamp }];
  }

  function onBarPointerMove(event: PointerEvent) {
    if (barPress === null || event.pointerId !== barPress.pointerId) return;
    const moved = event.clientY - barPress.startY;
    if (!barDragging) {
      if (moved < -DRAG_SLOP) barPress = null;
      if (moved <= DRAG_SLOP) return;
      barDragging = true;
      topBar?.setPointerCapture(event.pointerId);
    }
    barDragged = Math.max(0, moved - DRAG_SLOP);
    if (form) form.style.transform = `translateY(${barDragged}px)`;
    barSamples = [
      ...barSamples.filter((sample) => event.timeStamp - sample.time < 100),
      { y: event.clientY, time: event.timeStamp },
    ];
  }

  function onBarPointerUp(event: PointerEvent) {
    if (barPress === null || event.pointerId !== barPress.pointerId) return;
    barPress = null;
    if (!barDragging) return;
    barDragging = false;
    if (topBar) swallowReleaseClick(topBar, RELEASE_CLICK_MS);
    const first = barSamples[0];
    const elapsed = first === undefined ? 0 : event.timeStamp - first.time;
    // px per second, down positive
    const velocity =
      first === undefined || elapsed <= 0
        ? 0
        : ((event.clientY - first.y) / elapsed) * 1000;
    const dragged = barDragged;
    if (event.type !== "pointercancel" && parksOnRelease(dragged, velocity)) {
      void park(dragged);
    } else {
      const motion = moveTo(dragged, 0);
      if (form) form.style.transform = "";
      void motion?.finished.then(
        () => motion.cancel(),
        () => {},
      );
    }
  }

  onMount(() => {
    if (variant === "inline" || composer.to.length > 0) {
      editor?.focus(true);
    }
  });

  function changed() {
    composer.changed();
  }

  function format(command: FormatCommand) {
    if (preview) preview = false;
    editor?.format(command);
  }

  // Read in files pasted, dropped or picked; Send waits until they are in, and one that can't be
  // read says so.
  async function addFiles(files: readonly File[], inline: boolean) {
    if (files.length === 0) return;
    const images: ComposeImage[] = [];
    const inserts: string[] = [];
    composer.loadingFiles += files.length;
    try {
      for (const file of files) {
        let data: Uint8Array;
        try {
          data = new Uint8Array(await file.arrayBuffer());
        } catch (error) {
          console.error("file read failed", error);
          composer.error = `Couldn't add ${file.name || "the file"}.`;
          continue;
        }
        const entry = {
          id: randomId(),
          filename: file.name || "image.png",
          mimeType: file.type || "application/octet-stream",
          data,
        };
        if (inline && file.type.startsWith("image/")) {
          const contentId = `${randomId()}@inbox`;
          images.push({ ...entry, contentId });
          inserts.push(imageMarkdown(entry.filename, contentId));
        } else {
          composer.attachments = [...composer.attachments, entry];
        }
      }
      if (images.length > 0) {
        composer.inlineImages = [...composer.inlineImages, ...images];
        if (editor) {
          editor.insert(`${inserts.join("\n")}\n`);
        } else {
          composer.markdown += `${inserts.join("\n")}\n`;
          composer.html = null;
          changed();
        }
      } else {
        changed();
      }
    } finally {
      composer.loadingFiles -= files.length;
    }
  }

  function removeAttachment(id: string) {
    composer.attachments = composer.attachments.filter(
      (file) => file.id !== id,
    );
    changed();
  }

  function removeImage(image: ComposeImage) {
    composer.markdown = withoutImage(composer.markdown, image.contentId);
    composer.html = null;
    composer.inlineImages = composer.inlineImages.filter(
      (entry) => entry.id !== image.id,
    );
    changed();
  }

  function check(): boolean {
    const snapshot = composer.snapshot();
    const built = hub.build(snapshot);
    const problem = sendProblem(snapshot, built.size) ?? built.problem;
    composer.error = problem;
    return problem === null;
  }

  function removeQuote() {
    composer.quote = null;
    quoteOpen = false;
    changed();
  }

  function send() {
    if (locked) return;
    if (composer.loadingFiles > 0) {
      composer.error = "Wait for the files to finish adding.";
      return;
    }
    if (check()) void hub.send(composer);
  }

  // The explicit choice to send unencrypted when this device can't encrypt; it sends at once.
  function sendPlain() {
    if (locked) return;
    composer.sendPlain = true;
    send();
  }

  // Submit (and the send shortcut) keeps a scheduled message's time rather than sending early.
  function submit() {
    if (locked) return;
    if (scheduled !== null) void hub.keepSchedule(composer);
    else send();
  }

  function onKeydown(event: KeyboardEvent) {
    if (isCommandChord(event) && event.key === "Enter") {
      event.preventDefault();
      event.stopPropagation();
      submit();
      return;
    }
    // Enter in a one-line field would submit the form, which is Send.
    if (
      event.key === "Enter" &&
      (event.target as HTMLElement).tagName === "INPUT"
    ) {
      event.preventDefault();
      return;
    }
    if (event.key === "Escape" && !menuOpen) {
      event.preventDefault();
      event.stopPropagation();
      void hub.close(composer);
    }
  }

  function onEditorKeydown(event: KeyboardEvent) {
    if (isCommandChord(event) && !event.shiftKey && !event.altKey) {
      const command = (
        { b: "bold", i: "italic" } as Record<string, FormatCommand>
      )[event.key.toLowerCase()];
      if (command) {
        event.preventDefault();
        format(command);
      }
    }
  }

  function onPaste(event: ClipboardEvent) {
    if (locked) return;
    const files = [...(event.clipboardData?.files ?? [])];
    if (files.length === 0) return;
    event.preventDefault();
    void addFiles(files, true);
  }

  function onDrop(event: DragEvent) {
    dragging = false;
    if (locked) return;
    const files = [...(event.dataTransfer?.files ?? [])];
    if (files.length === 0) return;
    event.preventDefault();
    // Images dropped on the text go in the body; anything else is attached.
    const onText = editor?.contains(event.target as Node | null) ?? false;
    void addFiles(files, onText);
  }

  const toolButton = iconButton("small");
  const row =
    "flex-none flex items-center gap-2 px-4 border-b border-separator text-[13px]";
</script>

{#snippet copyToggle()}
  {#if !composer.showCopies}
    <button
      type="button"
      onclick={() => {
        composer.showCopies = true;
      }}
      class="h-7 px-1.5 rounded-full text-[13px] text-text-secondary hover:bg-surface-hover shrink-0"
    >
      Cc Bcc
    </button>
  {/if}
{/snippet}

<!-- svelte-ignore a11y_no_noninteractive_element_interactions: keys and drops for the whole composer -->
<form
  bind:this={form}
  aria-label={variant === "floating" ? title : "Reply"}
  inert={parked}
  onfocusin={(event) => {
    const target = event.target;
    if (
      target instanceof HTMLInputElement ||
      target instanceof HTMLTextAreaElement ||
      (target instanceof HTMLElement && target.isContentEditable)
    ) {
      lastFocus = target;
    }
  }}
  onsubmit={(event) => {
    event.preventDefault();
    submit();
  }}
  onkeydown={onKeydown}
  ondragover={(event) => {
    if (!locked && event.dataTransfer?.types.includes("Files")) {
      event.preventDefault();
      dragging = true;
    }
  }}
  ondragleave={(event) => {
    if (event.currentTarget === event.target) dragging = false;
  }}
  ondrop={onDrop}
  class={[
    "flex flex-col bg-surface text-text",
    variant === "floating"
      ? [
          fullScreen
            ? "fixed z-40 inset-0 w-full h-dvh pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)] animate-rise"
            : "fixed z-30 right-6 bottom-6 rounded-[20px] border border-border shadow-dialog animate-rise max-w-[calc(100vw-48px)]",
          fullScreen
            ? ""
            : minimized
              ? "w-[360px]"
              : expanded
                ? "w-[min(860px,calc(100vw-48px))] h-[calc(100dvh-48px)]"
                : "w-[560px] h-[min(620px,calc(100dvh-48px))]",
        ]
      : "mx-2 sm:mx-6 mb-6 rounded-[20px] border border-accent shadow-[0_0_0_3px_var(--color-accent-soft)]",
    dragging && "ring-2 ring-focus",
    parked && "invisible",
  ]}
>
  {#if variant === "floating"}
    <!-- svelte-ignore a11y_no_static_element_interactions: the drag that parks it; Minimize does the same from the keyboard -->
    <div
      bind:this={topBar}
      onpointerdown={onBarPointerDown}
      onpointermove={onBarPointerMove}
      onpointerup={onBarPointerUp}
      onpointercancel={onBarPointerUp}
      class={[
        "flex-none flex items-center gap-1",
        fullScreen ? "h-14 px-1 touch-none select-none" : "h-12 pl-4 pr-2",
        !folded && "border-b border-separator",
      ]}
    >
      {#if fullScreen}
        <button
          type="button"
          aria-label="Back"
          title="Saves the draft"
          onclick={() => hub.close(composer)}
          class={iconButton("large")}
        >
          <ArrowLeft class="w-5 h-5" />
        </button>
      {/if}
      <h2 class="flex-1 min-w-0 truncate text-sm font-semibold">{title}</h2>
      {#if fullScreen}
        <span class="shrink-0 text-xs text-text-secondary" aria-live="polite">
          {statusText}
        </span>
        <button
          type="button"
          aria-label="Minimize"
          title="Minimize"
          onclick={() => void park()}
          class={iconButton("large")}
        >
          <ChevronDown class="w-5 h-5" />
        </button>
      {:else}
        <button
          type="button"
          aria-label={folded ? "Restore" : "Minimize"}
          onclick={() => {
            minimized = !minimized;
          }}
          class={toolButton}
        >
          <Minus class="w-[18px] h-[18px]" />
        </button>
        {#if !folded}
          <button
            type="button"
            aria-label={expanded ? "Shrink" : "Expand"}
            onclick={() => {
              expanded = !expanded;
            }}
            class={toolButton}
          >
            {#if expanded}
              <Minimize2 class="w-4 h-4" />
            {:else}
              <Maximize2 class="w-4 h-4" />
            {/if}
          </button>
        {/if}
        <button
          type="button"
          aria-label="Close (Esc)"
          title="Close  esc"
          onclick={() => hub.close(composer)}
          class={toolButton}
        >
          <X class="w-[18px] h-[18px]" />
        </button>
      {/if}
    </div>
  {/if}

  {#if !folded}
    <fieldset disabled={locked} class="contents">
      <div class={[row, "h-11 relative z-20"]}>
        <span class="w-11 shrink-0 text-text-secondary">From</span>
        <FromPicker
          bind:value={composer.from}
          onChange={changed}
          tone={variant === "floating" ? "accent" : "muted"}
        />
      </div>

      <div class={[row, "py-2 flex-col items-stretch gap-1 relative z-10"]}>
        <RecipientField
          label="To"
          bind:mailboxes={composer.to}
          others={[...composer.cc, ...composer.bcc]}
          onChange={changed}
          focusFirst={variant === "floating" && composer.to.length === 0}
          trailing={copyToggle}
          {keyed}
        />
        {#if composer.showCopies}
          <RecipientField
            label="Cc"
            bind:mailboxes={composer.cc}
            others={[...composer.to, ...composer.bcc]}
            onChange={changed}
            {keyed}
          />
          <RecipientField
            label="Bcc"
            bind:mailboxes={composer.bcc}
            others={[...composer.to, ...composer.cc]}
            onChange={changed}
            {keyed}
          />
        {/if}
        {#if encryptionLine}
          <div
            class="ml-[52px] flex items-center gap-1.5 text-xs leading-[18px] text-text-secondary"
          >
            {#if encrypted}
              <Lock class="w-[13px] h-[13px]" aria-hidden="true" />
            {:else}
              <LockOpen class="w-[13px] h-[13px]" aria-hidden="true" />
            {/if}
            {encryptionLine}
            {#if encryption.kind === "locked" && !composer.sendPlain}
              <button
                type="button"
                onclick={hub.requestUnlock}
                class="font-semibold text-accent-strong hover:underline"
              >
                Unlock
              </button>
              <button
                type="button"
                onclick={sendPlain}
                class="font-semibold text-accent-strong hover:underline"
              >
                Send without encryption
              </button>
            {:else if encryption.kind === "locked"}
              <button
                type="button"
                onclick={() => {
                  composer.sendPlain = false;
                }}
                class="font-semibold text-accent-strong hover:underline"
              >
                Encrypt instead
              </button>
            {/if}
          </div>
        {/if}
      </div>

      {#if showSubject}
        <div class={[row, "h-11"]}>
          <label
            for={`${composer.localId}-subject`}
            class="w-11 shrink-0 text-text-secondary"
            >Subject</label
          >
          <input
            id={`${composer.localId}-subject`}
            type="text"
            bind:value={composer.subject}
            oninput={changed}
            class="flex-1 min-w-0 bg-transparent text-sm font-medium outline-none"
          >
        </div>
      {/if}
    </fieldset>

    <div
      class={[
        "flex flex-col min-h-0",
        variant === "floating" ? "flex-1 overflow-y-auto" : "",
      ]}
    >
      {#if preview}
        <!-- Sanitized with DOMPurify; richToHtml escapes the text too. -->
        <div
          class="rendered-markdown px-4 pt-3.5 pb-2 text-sm leading-[22px] min-h-[72px]"
        >
          {@html previewHtml}
        </div>
      {:else}
        <RichEditor
          bind:this={editor}
          bind:value={composer.markdown}
          bind:rendered={composer.html}
          readonly={locked}
          oninput={changed}
          onkeydown={onEditorKeydown}
          onpaste={onPaste}
          label={variant === "floating" ? "Message" : "Reply"}
          placeholder={variant === "inline" ? "Write a reply…" : ""}
          grow={variant === "inline"}
          class={variant === "floating"
            ? "flex-1 min-h-[160px]"
            : "min-h-[72px] max-h-[60vh]"}
        />
      {/if}
      {#if signatureHtml}
        <div
          class="rendered-markdown mx-4 mb-3 pt-2.5 border-t border-border w-fit min-w-[120px] text-sm text-text-secondary"
        >
          {@html signatureHtml}
        </div>
      {/if}
      {#if composer.quote !== null}
        <div class="mx-4 mb-3">
          <QuoteToggle bind:open={quoteOpen} />
          {#if quoteOpen}
            <div class="mt-2">
              <HtmlFrame html={quotedHtml} title="Quoted message" />
              {#if !locked}
                <button
                  type="button"
                  onclick={removeQuote}
                  class="mt-1 h-7 px-1.5 rounded-full text-[13px] text-text-secondary hover:bg-surface-hover hover:text-text"
                >
                  Remove quoted text
                </button>
              {/if}
            </div>
          {/if}
        </div>
      {/if}
    </div>

    {#if composer.attachments.length > 0 || shownImages.length > 0}
      <fieldset
        disabled={locked}
        class="flex-none min-w-0 px-4 py-2 flex flex-wrap gap-2"
      >
        {#each shownImages as image (image.id)}
          <span
            class="h-10 flex items-center gap-2 pl-1 pr-1 rounded-full border border-border text-[13px]"
          >
            <img
              src={imageUrls.get(image.contentId)}
              alt=""
              class="w-8 h-8 rounded-full object-cover"
            >
            <span class="max-w-40 truncate font-medium">{image.filename}</span>
            <button
              type="button"
              aria-label={`Remove ${image.filename}`}
              onclick={() => removeImage(image)}
              class={toolButton}
            >
              <X class="w-3.5 h-3.5" />
            </button>
          </span>
        {/each}
        {#each composer.attachments as file (file.id)}
          <span
            class="h-10 flex items-center gap-2 pl-3 pr-1 rounded-full border border-border text-[13px]"
          >
            <FileIcon class="w-4 h-4 text-text-secondary" aria-hidden="true" />
            <span class="max-w-48 truncate font-medium">{file.filename}</span>
            <span class="text-text-secondary"
              >· {formatSize(file.data.byteLength)}</span
            >
            <button
              type="button"
              aria-label={`Remove ${file.filename}`}
              onclick={() => removeAttachment(file.id)}
              class={toolButton}
            >
              <X class="w-3.5 h-3.5" />
            </button>
          </span>
        {/each}
      </fieldset>
    {/if}

    {#if composer.error}
      <p
        role="alert"
        class="flex-none mx-4 mb-2 px-3 py-2 rounded-xl bg-danger-soft text-danger text-[13px]"
      >
        {composer.error}
      </p>
    {/if}

    {#if locked}
      <p
        role="status"
        class="flex-none mx-4 mb-2 px-3 py-2 flex items-center gap-2 rounded-xl bg-surface-muted text-[13px]"
      >
        <RefreshCw
          class="w-4 h-4 shrink-0 text-text-secondary"
          aria-hidden="true"
        />
        {#if retry}
          <span class="shrink-0">{retry.headline}</span>
          <span
            class="flex-1 min-w-0 truncate text-text-secondary"
            title={retry.reason}
            >{retry.reason}</span
          >
        {:else}
          <span class="flex-1">Sending… Cancel to change it.</span>
        {/if}
      </p>
    {:else if scheduled !== null}
      <p
        class="flex-none mx-4 mb-2 px-3 py-2 flex items-center gap-2 rounded-xl bg-accent-soft text-[13px]"
      >
        <CalendarClock class="w-4 h-4 text-accent-strong" aria-hidden="true" />
        <span>
          Scheduled for
          <span class="font-semibold"
            >{formatWakeTime(scheduled.sendAt, Date.now())}</span
          >. Edits save to it.
        </span>
      </p>
    {:else if failure !== null}
      <p
        class="flex-none mx-4 mb-2 px-3 py-2 flex items-center gap-2 rounded-xl bg-danger-soft text-danger text-[13px]"
      >
        <CircleAlert class="w-4 h-4" aria-hidden="true" />
        <span>Couldn't send. {failure.message}</span>
      </p>
    {:else if blocked !== null}
      <p
        role="status"
        class="flex-none mx-4 mb-2 px-3 py-2 flex items-center gap-2 rounded-xl bg-surface-muted text-[13px]"
      >
        <Clock
          class="w-4 h-4 shrink-0 text-text-secondary"
          aria-hidden="true"
        />
        <span>Not sent yet. {blocked}. It goes by itself after that.</span>
      </p>
    {/if}

    {#if locked && outboxEntry !== null}
      <div
        class={[
          "relative flex-none flex items-center gap-2 px-3",
          variant === "floating"
            ? "h-14 border-t border-separator"
            : "h-[52px]",
        ]}
      >
        <button
          type="button"
          onclick={() => outboxEntry && hub.cancelSend(outboxEntry)}
          title={retry?.mayHaveGone || outboxEntry.status === "sending"
            ? "It may have gone already"
            : undefined}
          class="h-9 px-3 shrink-0 rounded-full border border-border text-sm font-medium hover:bg-surface-hover transition-colors"
        >
          Cancel send
        </button>
        <span class="flex-1"></span>
      </div>
    {:else}
      <div
        class={[
          "relative flex-none flex items-center gap-1 px-3",
          variant === "floating"
            ? "h-14 border-t border-separator"
            : "h-[52px]",
        ]}
      >
        <div class="relative mr-2 flex">
          <button
            type="submit"
            aria-label={encrypted && scheduled === null
              ? "Send encrypted"
              : undefined}
            title={encrypted
              ? "Every recipient has a key"
              : chordTitle("Send", `${commandKey}↵`)}
            aria-keyshortcuts={isMacPlatform() ? "Meta+Enter" : "Control+Enter"}
            class="h-10 max-sm:h-12 flex items-center gap-2 pl-5 pr-3 rounded-l-full bg-accent text-on-accent text-sm font-semibold hover:bg-accent-strong transition-colors"
          >
            {#if encrypted}
              <Lock class="w-4 h-4" aria-hidden="true" />
            {/if}
            {scheduled !== null
              ? "Keep schedule"
              : encrypted
                ? "Encrypted"
                : "Send"}
          </button>
          <button
            type="button"
            aria-label={scheduled !== null
              ? "Change schedule"
              : "Schedule send"}
            title={scheduled !== null ? "Change schedule" : "Schedule send"}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onclick={() => {
              if (!menuOpen && !check()) return;
              menuOpen = !menuOpen;
            }}
            class="w-10 h-10 max-sm:w-12 max-sm:h-12 flex items-center justify-center pr-1 rounded-r-full border-l border-accent-strong bg-accent text-on-accent hover:bg-accent-strong transition-colors"
          >
            <ChevronDown class="w-4 h-4" />
          </button>
          {#if menuOpen}
            <SendMenu
              onPick={(pick) => {
                menuOpen = false;
                void hub.schedule(
                  composer,
                  pick.sendAt,
                  pick.chosenAt,
                  pick.source,
                  pick.keyId,
                  pick.typed,
                );
              }}
              onClose={() => {
                menuOpen = false;
              }}
            />
          {/if}
        </div>
        {#if scheduled !== null}
          <button
            type="button"
            onclick={send}
            class="h-9 mr-2 px-3 shrink-0 rounded-full border border-border text-sm font-medium hover:bg-surface-hover transition-colors"
          >
            Send now
          </button>
        {/if}
        <button
          type="button"
          aria-label={`Bold (${commandKey}B)`}
          title={chordTitle("Bold", `${commandKey}B`)}
          onclick={() => format("bold")}
          class={toolButton}
        >
          <Bold class="w-4 h-4" />
        </button>
        <button
          type="button"
          aria-label={`Italic (${commandKey}I)`}
          title={chordTitle("Italic", `${commandKey}I`)}
          onclick={() => format("italic")}
          class={toolButton}
        >
          <Italic class="w-4 h-4" />
        </button>
        <button
          type="button"
          aria-label="Insert image"
          title="Insert image"
          onclick={() => imagePicker?.click()}
          class={toolButton}
        >
          <ImageIcon class="w-4 h-4" />
        </button>
        <button
          type="button"
          aria-label="Attach file"
          title="Attach file"
          onclick={() => filePicker?.click()}
          class={toolButton}
        >
          <Paperclip class="w-4 h-4" />
        </button>
        <input
          bind:this={imagePicker}
          type="file"
          accept="image/*"
          multiple
          hidden
          onchange={(event) => {
            const input = event.currentTarget;
            void addFiles([...(input.files ?? [])], true);
            input.value = "";
          }}
        >
        <input
          bind:this={filePicker}
          type="file"
          multiple
          hidden
          onchange={(event) => {
            const input = event.currentTarget;
            void addFiles([...(input.files ?? [])], false);
            input.value = "";
          }}
        >
        <span class="flex-1"></span>
        {#if !fullScreen}
          <span
            class="min-w-0 text-xs text-text-secondary mr-1 truncate"
            aria-live="polite"
          >
            {statusText}
          </span>
        {/if}
        <button
          type="button"
          aria-label={preview ? "Edit" : "Preview"}
          title={preview ? "Edit" : "Preview"}
          aria-pressed={preview}
          onclick={() => {
            preview = !preview;
          }}
          class={toolButton}
        >
          {#if preview}
            <PencilLine class="w-4 h-4" />
          {:else}
            <Eye class="w-4 h-4" />
          {/if}
        </button>
        <button
          type="button"
          aria-label="Discard draft"
          title="Discard draft"
          onclick={() => hub.discard(composer)}
          class={toolButton}
        >
          <Trash2 class="w-4 h-4" />
        </button>
      </div>
    {/if}
  {/if}
</form>

{#if parked}
  <button
    bind:this={strip}
    type="button"
    aria-label={`Draft: ${stripTitle}`}
    title="Open the draft"
    onclick={openAgain}
    class="fixed z-[21] inset-x-0 bottom-0 h-[calc(3.5rem+env(safe-area-inset-bottom))] pb-[env(safe-area-inset-bottom)] px-safe-4 flex items-center gap-3 bg-surface border-t border-border shadow-[0_-6px_18px_-12px_rgb(0_0_0/0.35)] text-left animate-fade-in"
  >
    <PencilLine
      class="w-5 h-5 shrink-0 text-accent-strong"
      aria-hidden="true"
    />
    <span class="flex-1 min-w-0 truncate text-sm font-semibold"
      >{stripTitle}</span
    >
    <ChevronUp
      class="w-5 h-5 shrink-0 text-text-secondary"
      aria-hidden="true"
    />
  </button>
{/if}
