<!-- @component Every keyboard shortcut in three columns, as the `?` list and the Shortcuts settings show them. -->
<script lang="ts">
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { shortcutColumns } from "../shortcuts";

  const hub = getComposeHub();
  const columns = $derived(shortcutColumns(hub.settings.showTodos));
</script>

<div class="grid grid-cols-3 gap-x-8 items-start">
  {#each columns as column, columnIndex (columnIndex)}
    <div class="flex flex-col gap-5">
      {#each column as group (group.title)}
        <section aria-label={group.title}>
          <h3
            class="mb-1.5 h-6 flex items-center text-[13px] font-semibold text-text-secondary"
          >
            {group.title}
          </h3>
          <dl>
            {#each group.shortcuts as shortcut (shortcut.action)}
              <div
                class="h-9 flex items-center gap-3 border-t border-surface-muted"
              >
                <dt class="flex-1">{shortcut.action}</dt>
                <dd class="flex items-center gap-1.5 shrink-0">
                  {#each shortcut.keys as key, keyIndex (key)}
                    {#if keyIndex > 0}
                      <span class="text-xs text-text-secondary">
                        {shortcut.chord ? "then" : "to"}
                      </span>
                    {/if}
                    <kbd
                      class="font-mono text-xs leading-[22px] min-w-6 h-6 text-center px-1.5 rounded-md border border-border bg-surface-muted text-text"
                      >{key}</kbd
                    >
                  {/each}
                </dd>
              </div>
            {/each}
          </dl>
        </section>
      {/each}
    </div>
  {/each}
</div>
<p class="mt-5 text-[13px] text-text-secondary">
  Shortcuts stay off while you type in a field.
</p>
