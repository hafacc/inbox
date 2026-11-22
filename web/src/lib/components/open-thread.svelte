<!-- @component An open thread in a list or a bundle: its own raised card, grown out of the row it replaces; at phone width the whole screen, grown out of the row. -->
<script lang="ts">
  import type { Row } from "../list-model";
  import type { ThreadDetail } from "../thread-detail";
  import type { Closing } from "./closing";
  import { unfold } from "./open-motion.svelte";
  import { phoneWidth } from "./phone";
  import ThreadView from "./thread-view.svelte";

  let {
    row,
    rowKey,
    rowHeight,
    closing,
  }: {
    /** the open thread's row */
    row: Row;
    /** its row key in the list (see `threadRowKey`) */
    rowKey: string;
    /** the height of the row it grows out of and shrinks back into */
    rowHeight: number;
    /** the list's open thread, kept with its messages while it shrinks back into its row */
    closing: Closing<ThreadDetail | null>;
  } = $props();
  const id = $derived(row.thread.id);
</script>

<!-- At phone width the open thread covers the screen (see ThreadView) and this keeps its row's place in the list behind, which stays where it was. -->
<div
  class="my-4 max-sm:my-0"
  style:height={phoneWidth.current ? `${rowHeight}px` : undefined}
  data-row-key={rowKey}
  {@attach phoneWidth.current
    ? undefined
    : unfold(rowHeight, () => closing.has(id))}
>
  <ThreadView {row} closedDetail={closing.shown(id) ?? null} />
</div>
