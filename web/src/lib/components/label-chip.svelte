<!-- @component A Gmail label as a small chip with its icon or color dot. -->
<script lang="ts">
  import type { GmailLabel } from "../gmail/types";
  import LabelMark from "./label-mark.svelte";

  let { label }: { label: GmailLabel } = $props();
</script>

<span
  class="h-[22px] flex items-center gap-1 pl-1.5 pr-2 rounded-full bg-surface-muted text-xs font-medium text-text-secondary shrink-0 max-w-40"
>
  <LabelMark {label} size={12} plain />
  <span class="truncate">{label.name}</span>
</span>
