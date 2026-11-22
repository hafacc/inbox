<!-- @component An on/off switch with its label (and an optional hint), the whole row clickable. -->
<script lang="ts">
  let {
    label,
    hint = "",
    checked,
    onchange,
  }: {
    /** what it turns on */
    label: string;
    /** a line under the label */
    hint?: string;
    /** whether it is on */
    checked: boolean;
    /** called with the new value */
    onchange: (checked: boolean) => void;
  } = $props();

  const id = $props.id();
</script>

<label
  for={id}
  class="flex items-center gap-3 min-h-10 px-1 py-1.5 rounded-xl cursor-pointer"
>
  <span class="flex-1 min-w-0">
    <span class="block text-sm text-text">{label}</span>
    {#if hint}
      <span class="block text-xs text-text-secondary">{hint}</span>
    {/if}
  </span>
  <input
    {id}
    type="checkbox"
    role="switch"
    aria-checked={checked}
    {checked}
    onchange={(event) => onchange(event.currentTarget.checked)}
    class="w-10 h-6 shrink-0 appearance-none rounded-full bg-border-strong checked:bg-accent relative cursor-pointer transition-colors duration-200 before:content-[''] before:absolute before:top-0.5 before:left-0.5 before:w-5 before:h-5 before:rounded-full before:bg-surface-raised checked:before:bg-on-accent before:shadow before:transition-transform before:duration-200 checked:before:translate-x-4"
  >
</label>
