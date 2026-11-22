<!-- @component A label's mark: its chosen icon, or its colored dot, in a soft circle of its color. -->
<script lang="ts">
  import type { GmailLabel } from "../gmail/types";
  import { labelDot } from "../labels";
  import { getMailState } from "../mail-state.svelte";
  import { LABEL_ICON_COMPONENTS } from "./label-icons";

  let {
    label,
    size = 36,
    plain = false,
  }: {
    /** the label */
    label: Pick<GmailLabel, "id" | "color"> | undefined;
    /** the circle's size in pixels */
    size?: number;
    /** just the icon or dot, without the circle (sidebar, chips) */
    plain?: boolean;
  } = $props();

  const mail = getMailState();
  const color = $derived(labelDot(label));
  const iconId = $derived(
    label ? (mail.labelPrefs.get(label.id)?.icon ?? null) : null,
  );
  const Icon = $derived(
    iconId === null ? null : (LABEL_ICON_COMPONENTS[iconId] ?? null),
  );
  const iconSize = $derived(plain ? size : Math.round(size * 0.47));
</script>

{#if plain}
  <span
    class="shrink-0 flex items-center justify-center"
    style:width={`${size}px`}
    style:height={`${size}px`}
    style:color
    aria-hidden="true"
  >
    {#if Icon}
      <Icon style={`width:${iconSize}px;height:${iconSize}px`} />
    {:else}
      <span
        class="w-2.5 h-2.5 rounded-full"
        style:background-color={color}
      ></span>
    {/if}
  </span>
{:else}
  <span
    class="shrink-0 rounded-full flex items-center justify-center transition-colors"
    style:width={`${size}px`}
    style:height={`${size}px`}
    style:background-color={`color-mix(in srgb, ${color} 18%, var(--color-surface))`}
    style:color
    aria-hidden="true"
  >
    {#if Icon}
      <Icon style={`width:${iconSize}px;height:${iconSize}px`} />
    {:else}
      <span
        class="rounded-full"
        style:width={`${Math.round(size * 0.3)}px`}
        style:height={`${Math.round(size * 0.3)}px`}
        style:background-color={color}
      ></span>
    {/if}
  </span>
{/if}
