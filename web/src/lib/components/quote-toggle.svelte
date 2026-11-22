<!-- @component The quiet "…" button that shows or hides a quoted earlier message. -->
<script lang="ts">
  let { open = $bindable(false) }: { open?: boolean } = $props();
</script>

<button
  type="button"
  aria-label={open ? "Hide quoted text" : "Show quoted text"}
  title={open ? "Hide quoted text" : "Show quoted text"}
  aria-expanded={open}
  onclick={() => {
    open = !open;
  }}
  class="mt-2 h-4 px-1.5 flex items-center rounded bg-surface-muted text-text-secondary leading-none tracking-[0.1em] hover:bg-surface-hover hover:text-text transition-colors"
>
  <span aria-hidden="true">…</span>
</button>
