<!-- @component What shows under a row being swiped: the action of the side the row moves toward, on its soft color, turning to its strong color with the icon growing once letting go acts. -->
<script lang="ts" module>
  import type { IconType } from "./icons";

  /** How a swipe's action looks under the row. */
  export type SwipeLook = {
    /** classes for its soft fill and ink */
    soft: string;
    /** classes for its strong fill and ink, once letting go acts */
    strong: string;
    /** its name */
    label: string;
    /** its icon */
    icon: IconType;
  };
</script>

<script lang="ts">
  import { swipeProgress } from "@inbox/shared/swipe";

  let {
    dx,
    width,
    start,
    end,
  }: {
    /** how far the row has moved, rightward positive */
    dx: number;
    /** the row's width */
    width: number;
    /** the rightward swipe's action, if any */
    start: SwipeLook | null;
    /** the leftward swipe's action, if any */
    end: SwipeLook | null;
  } = $props();

  const armed = $derived(swipeProgress(dx, width) >= 1);
  const look = $derived(dx > 0 ? start : dx < 0 ? end : null);
</script>

{#if look}
  <div
    aria-hidden="true"
    class={[
      "absolute inset-0 flex items-center gap-2 px-5 text-sm font-medium transition-colors duration-150",
      dx < 0 && "flex-row-reverse",
      armed ? look.strong : look.soft,
    ]}
  >
    <look.icon
      class={[
        "w-5 h-5 transition-transform duration-150 ease-out",
        armed && "scale-125",
      ]}
    />
    {look.label}
  </div>
{/if}
