<!-- @component The quick reply at the bottom of an open thread: a one-line field that grows as it's typed in, Send, and a button that carries the text into the full composer; Reply all when the last message reached more than one other person. Under it the other answers. At phone width there is no field: Reply, Reply all (when it reaches more than one other person) and Forward each open the full composer. -->
<script lang="ts" module>
  // What was typed per thread, kept while the app is open so closing the thread doesn't lose it.
  const typedReplies = new Map<string, string>();
</script>

<script lang="ts">
  import Forward from "@lucide/svelte/icons/forward";
  import Maximize2 from "@lucide/svelte/icons/maximize-2";
  import Reply from "@lucide/svelte/icons/reply";
  import ReplyAll from "@lucide/svelte/icons/reply-all";
  import SendHorizontal from "@lucide/svelte/icons/send-horizontal";
  import { untrack } from "svelte";
  import type { Attachment } from "svelte/attachments";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { isCommandChord } from "../keyboard";
  import { keyTitle } from "../shortcuts";
  import type { ThreadDetail } from "../thread-detail";
  import { answerButtons } from "./answer-buttons";
  import { iconButton } from "./icon-button";
  import { phoneWidth } from "./phone";

  let {
    detail,
  }: {
    /** the open thread */
    detail: ThreadDetail;
  } = $props();

  const hub = getComposeHub();
  const fieldId = $props.id();
  const mode = $derived(hub.quickReplyMode(detail));
  const placeholder = $derived(mode === "replyAll" ? "Reply all" : "Reply");
  let text = $state(untrack(() => typedReplies.get(detail.id)) ?? "");
  let busy = $state(false);
  const empty = $derived(text.trim() === "");

  $effect(() => {
    if (text === "") typedReplies.delete(detail.id);
    else typedReplies.set(detail.id, text);
  });

  const grow: Attachment<HTMLTextAreaElement> = (field) => {
    $effect(() => {
      void text;
      field.style.height = "auto";
      field.style.height = `${field.scrollHeight}px`;
    });
  };

  async function answer(action: "send" | "open", as = mode) {
    if (busy || (action === "send" && empty)) return;
    busy = true;
    const typed = text;
    text = "";
    try {
      if (!(await hub.quickReply(as, typed, action))) text = typed;
    } finally {
      busy = false;
    }
  }

  // The other reply buttons carry what's typed along too.
  function answerAs(as: "reply" | "replyAll") {
    if (empty) void hub.answer(as);
    else void answer("open", as);
  }

  function onKeydown(event: KeyboardEvent) {
    if (isCommandChord(event) && event.key === "Enter") {
      event.preventDefault();
      void answer("send");
    } else if (event.key === "Escape" && !empty) {
      event.stopPropagation();
      (event.currentTarget as HTMLElement).blur();
    }
  }

  const answerButton =
    "h-10 max-sm:h-12 flex items-center gap-2 px-4 rounded-full border border-border text-[13px] max-sm:text-sm font-medium text-text-secondary max-sm:text-text max-sm:bg-surface hover:bg-surface-hover hover:text-text transition-colors";
  // On a phone the icons keep the quieter color beside the words, as on Android.
  const answerIcon =
    "w-4 h-4 max-sm:w-[18px] max-sm:h-[18px] max-sm:text-text-secondary";
</script>

<div class="px-3 sm:pl-[76px] sm:pr-6 pt-2 pb-5 max-sm:pt-4 max-sm:pb-3">
  {#if !phoneWidth.current}
    <div class="flex items-end gap-2">
      <div
        class="flex-1 min-w-0 flex items-end rounded-[24px] border border-border bg-surface transition-colors focus-within:border-accent focus-within:shadow-[0_0_0_3px_var(--color-accent-soft)]"
      >
        <label for={fieldId} class="sr-only">{placeholder}</label>
        <textarea
          id={fieldId}
          {@attach grow}
          bind:value={text}
          onkeydown={onKeydown}
          rows="1"
          {placeholder}
          class="flex-1 min-w-0 max-h-[40vh] resize-none bg-transparent py-3 pl-5 text-sm leading-6 outline-none placeholder:text-text-secondary"
        ></textarea>
        <button
          type="button"
          aria-label="Open in the full composer"
          title="Open in the full composer"
          disabled={busy}
          onclick={() => void answer("open")}
          class={iconButton("large")}
        >
          <Maximize2 class="w-4 h-4" />
        </button>
      </div>
      <button
        type="button"
        aria-label="Send"
        title="Send"
        disabled={empty || busy}
        onclick={() => void answer("send")}
        class={iconButton(
          "large",
          "bg-accent text-on-accent hover:bg-accent-strong disabled:opacity-40 disabled:cursor-not-allowed",
        )}
      >
        <SendHorizontal class="w-5 h-5" />
      </button>
    </div>
  {/if}
  <div
    class={["flex flex-wrap items-center gap-2", !phoneWidth.current && "mt-3"]}
  >
    {#each answerButtons(mode, phoneWidth.current) as button (button)}
      {#if button === "reply"}
        <button
          type="button"
          title={keyTitle("Reply", "r", hub.settings.shortcuts)}
          onclick={() => answerAs("reply")}
          class={answerButton}
        >
          <Reply class={answerIcon} aria-hidden="true" />
          Reply
        </button>
      {:else if button === "replyAll"}
        <button
          type="button"
          title={keyTitle("Reply all", "a", hub.settings.shortcuts)}
          onclick={() => answerAs("replyAll")}
          class={answerButton}
        >
          <ReplyAll class={answerIcon} aria-hidden="true" />
          Reply all
        </button>
      {:else}
        <button
          type="button"
          title={keyTitle("Forward", "f", hub.settings.shortcuts)}
          onclick={() => hub.answer("forward")}
          class={answerButton}
        >
          <Forward class={answerIcon} aria-hidden="true" />
          Forward
        </button>
      {/if}
    {/each}
  </div>
</div>
