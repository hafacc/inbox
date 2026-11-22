<!-- @component The end of a list whose todos show a page at a time: once it scrolls near view, the next page shows. -->
<script lang="ts">
  import { getMailState } from "../mail-state.svelte";

  const mail = getMailState();
  let element = $state<HTMLDivElement>();

  // Watched afresh for each page: a new observer reports at once whether the end is still in view.
  $effect(() => {
    void mail.todoLimit;
    const target = element;
    if (!target) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) mail.showMoreTodos();
      },
      { rootMargin: "400px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  });
</script>

<div bind:this={element} class="h-4" aria-hidden="true"></div>
