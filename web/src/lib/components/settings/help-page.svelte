<!-- @component Settings › Shortcuts & search: the keyboard shortcuts (and a switch to turn them off), every search operator with an example, and our own search terms. -->
<script lang="ts">
  import {
    OPERATOR_DETAILS,
    OPERATOR_EXAMPLES,
    OPERATORS,
    OWN_TERMS,
  } from "@inbox/shared/search";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import ShortcutList from "../shortcut-list.svelte";
  import Switch from "../switch.svelte";

  const hub = getComposeHub();

  const card = "rounded-[22px] bg-surface shadow-card p-5";
</script>

<h1 class="h-16 flex items-center text-xl font-semibold tracking-[-0.01em]">
  Shortcuts & search
</h1>

<section class={card} aria-labelledby="help-shortcuts">
  <div class="flex items-center gap-4">
    <h2 id="help-shortcuts" class="flex-1 text-[15px] font-semibold">
      Keyboard shortcuts
    </h2>
  </div>
  <div class="mt-2 -mx-1">
    <Switch
      label="Use keyboard shortcuts"
      hint="Single keys like j, k and e act on mail. Escape and undo always work."
      checked={hub.settings.shortcuts}
      onchange={(on) => void hub.settings.setShortcuts(on)}
    />
  </div>
  {#if hub.settings.shortcuts}
    <div class="mt-4">
      <ShortcutList />
    </div>
  {/if}
</section>

<section class={[card, "mt-6"]} aria-labelledby="help-operators">
  <h2 id="help-operators" class="text-[15px] font-semibold">
    Search operators
  </h2>
  <p class="mt-1 text-[13px] text-text-secondary">
    Type one in the search field and it turns into a chip. Put a minus in front
    to leave matches out, as in <code class="font-mono">-is:unread</code>.
  </p>
  <dl class="mt-3">
    {#each OPERATORS as operator (operator)}
      <div
        class="min-h-10 py-2 flex items-baseline gap-4 border-t border-separator first:border-t-0"
      >
        <dt class="w-32 shrink-0 font-mono text-[13px] font-semibold">
          {operator}:
        </dt>
        <dd class="flex-1 min-w-0 flex flex-wrap items-baseline gap-x-3">
          <span class="text-sm">{OPERATOR_DETAILS[operator]}</span>
          <code class="font-mono text-xs text-text-secondary"
            >{OPERATOR_EXAMPLES[operator]}</code
          >
        </dd>
      </div>
    {/each}
  </dl>
  <h3 class="mt-5 text-[13px] font-semibold text-text-secondary">
    Ours, not Gmail's
  </h3>
  <dl class="mt-1">
    {#each OWN_TERMS as [term, detail] (term)}
      <div
        class="min-h-10 py-2 flex items-baseline gap-4 border-t border-separator first:border-t-0"
      >
        <dt class="w-32 shrink-0 font-mono text-[13px] font-semibold">
          {term}
        </dt>
        <dd class="flex-1 min-w-0 text-sm">{detail}</dd>
      </div>
    {/each}
  </dl>
</section>
