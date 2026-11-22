<!-- @component At phone width, the known tags offered for the `#tag` being typed in a todo, as a row on top of the on-screen keyboard, as latr has it; a tag naming a label in its color. A tap finishes the tag and keeps the keyboard up. -->
<script lang="ts">
  import { getMailState } from "../mail-state.svelte";

  const mail = getMailState();
  // How far the on-screen keyboard covers the page; iOS doesn't shrink the page for it.
  let keyboardInset = $state(0);

  $effect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => {
      keyboardInset = Math.max(
        0,
        window.innerHeight - viewport.height - viewport.offsetTop,
      );
    };
    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  });

  // Keeps focus in the editor being typed in.
  function keepFocus(event: Event) {
    event.preventDefault();
  }
</script>

{#if mail.todos.tagOffer}
  {@const offer = mail.todos.tagOffer}
  <div
    role="toolbar"
    aria-label="Tags"
    style:bottom="{keyboardInset}px"
    class="sm:hidden fixed inset-x-0 z-40 flex items-center gap-2 h-12 px-3 overflow-x-auto bg-surface-raised border-t border-border [scrollbar-width:none]"
  >
    {#each offer.names as name (name)}
      {@const color = mail.tagColor(name)}
      <button
        type="button"
        onpointerdown={keepFocus}
        onmousedown={keepFocus}
        onclick={() => offer.pick(name)}
        class="todo-text shrink-0 h-full px-1.5 flex items-center text-[16px] leading-6"
      >
        <span
          class={["md-tag", color !== null && "md-tag-label"]}
          style:--tag-color={color}
          >#{name}</span
        >
      </button>
    {/each}
  </div>
{/if}
