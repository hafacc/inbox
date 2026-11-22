<!-- @component Gmail's importance marker, an arrow-shaped tag: filled on an important thread's row, outlined on a button. -->
<script lang="ts">
  import type { ClassValue } from "svelte/elements";

  let {
    filled = true,
    label,
    class: className,
  }: {
    /** whether the shape is filled rather than outlined */
    filled?: boolean;
    /** what it means, for screen readers; without it the mark is hidden from them */
    label?: string;
    /** size and color classes */
    class?: ClassValue;
  } = $props();
</script>

<svg
  viewBox="0 0 24 24"
  class={className}
  fill={filled ? "currentColor" : "none"}
  stroke="currentColor"
  stroke-width="2"
  stroke-linejoin="round"
  role={label ? "img" : undefined}
  aria-label={label}
  aria-hidden={label ? undefined : "true"}
>
  <path d="M3 5h12.5l5.5 7-5.5 7H3l5-7z" />
</svg>
