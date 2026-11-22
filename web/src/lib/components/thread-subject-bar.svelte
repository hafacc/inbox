<!-- @component An open thread's top bar on a phone, held at the top of the screen as its messages scroll: Back, its subject (up to two lines, then cut; when cut, a tap shows all of it, another cuts it again) and the importance marker. -->
<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import { iconButton, quietColors } from "./icon-button";
  import ImportantMark from "./important-mark.svelte";
  import { textCut } from "./text-cut";

  let {
    subject,
    important,
    marked = true,
    raised = false,
    onBack,
    onImportant,
    heading = $bindable(),
  }: {
    /** the thread's subject */
    subject: string;
    /** whether the thread is marked important */
    important: boolean;
    /** whether the importance marker shows: not when the thread offers no importance action */
    marked?: boolean;
    /** whether messages have scrolled under it, so it casts a shadow on them */
    raised?: boolean;
    /** close the thread */
    onBack: () => void;
    /** mark it important or not */
    onImportant: () => void;
    /** the subject's heading, which takes focus as the thread opens */
    heading?: HTMLHeadingElement;
  } = $props();

  // The subject shown whole, until the thread (and so the subject) changes.
  let wholeFor = $state<string | null>(null);
  const whole = $derived(wholeFor === subject);
  // Whether the subject doesn't fit in two lines; only then does a tap show it whole.
  let cut = $state(false);
  const toggles = $derived(whole || cut);
</script>

<div
  class={[
    "sticky top-0 z-[6] min-h-14 p-1 flex items-center gap-1 bg-surface transition-shadow duration-200",
    raised && "shadow-[0_6px_18px_-12px_rgb(0_0_0/0.35)]",
  ]}
>
  <button
    type="button"
    aria-label="Back"
    onclick={onBack}
    class={iconButton("large")}
  >
    <ArrowLeft class="w-5 h-5" />
  </button>
  <h1
    bind:this={heading}
    tabindex="-1"
    class="flex-1 min-w-0 text-[17px] leading-[22px] font-semibold tracking-[-0.01em] outline-none"
  >
    <button
      type="button"
      {@attach textCut(subject, (value) => (cut = value))}
      aria-expanded={toggles ? whole : undefined}
      onclick={() => {
        if (toggles) wholeFor = whole ? null : subject;
      }}
      class={[
        "w-full text-left break-words cursor-default outline-none",
        !whole && "line-clamp-2",
      ]}
    >
      {subject}
    </button>
  </h1>
  {#if marked}
    <button
      type="button"
      aria-label={important ? "Mark not important" : "Mark important"}
      aria-pressed={important}
      onclick={onImportant}
      class={iconButton(
        "large",
        important ? "text-accent hover:bg-surface-hover" : quietColors,
      )}
    >
      <ImportantMark filled={important} class="w-5 h-5" />
    </button>
  {/if}
</div>
