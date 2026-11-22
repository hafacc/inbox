<!-- @component The Todos view: the active todos, and the todos of emails in the inbox, under latr's headings (snoozed and done ones are in Snoozed and Done). An email's todo is a row of its own that opens the email, which grows out of it. Rows joining or leaving the list grow in or collapse out. -->
<script lang="ts">
  import { threadRowKey, todoRowKey } from "@inbox/shared/list-state";
  import type { TodosViewEntry } from "@inbox/shared/todos";
  import { untrack } from "svelte";
  import type { Row } from "../list-model";
  import { getMailState } from "../mail-state.svelte";
  import { CardSplit } from "./card-split";
  import { ListMotion } from "./list-motion.svelte";
  import OpenThread from "./open-thread.svelte";
  import ThreadRow from "./thread-row.svelte";
  import TodoMore from "./todo-more.svelte";
  import TodoRow from "./todo-row.svelte";

  type Entry = TodosViewEntry<Row>;
  // An email opened out of a heading's card, splitting it.
  type Opened = { kind: "open"; key: string; row: Row };

  const JOINED =
    "border-t border-separator first:border-t-0 first:rounded-t-[18px] last:rounded-b-[18px]";
  // The row an open email grows out of and shrinks back into.
  const ROW_HEIGHT = 56;

  const mail = getMailState();
  const buckets = $derived(mail.todosViewBuckets);
  const shownBuckets = $derived(mail.shownTodosView);
  const bucketSizes = $derived(
    new Map(buckets.map((bucket) => [bucket.label, bucket.entries.length])),
  );
  let root = $state<HTMLDivElement>();
  const motion = new ListMotion(
    mail,
    () => root,
    () =>
      new Set(shownBuckets.flatMap((bucket) => bucket.entries.map(entryKey))),
    () => shownBuckets,
  );
  const { enter: rowIn, leave: rowOut } = motion;
  // Emails with a todo this device hasn't read yet are read when the todos change.
  $effect(() => {
    void mail.todos.byThread;
    untrack(() => mail.loadTodoEmails());
  });

  function entryKey(entry: Entry): string {
    return entry.kind === "todo"
      ? todoRowKey(entry.todo.id)
      : threadRowKey(entry.email.thread.id);
  }

  function chunksOf(entries: readonly Entry[], label: string) {
    const split = new CardSplit<Entry, Opened>(label);
    for (const entry of entries) {
      if (entry.kind === "email" && motion.showsOpen(entry.email.thread.id)) {
        split.lift({
          kind: "open",
          key: `open:${entry.email.thread.id}`,
          row: entry.email,
        });
      } else {
        split.add(entry);
      }
    }
    return split.done();
  }
</script>

<div bind:this={root} class="contents">
  {#if !mail.todos.loaded || (buckets.length === 0 && mail.readingTodoEmails)}
    <div class="pt-16 flex justify-center" aria-busy="true">
      <span class="text-[13px] text-text-secondary">Loading…</span>
    </div>
  {:else if buckets.length === 0}
    <div class="pt-16 flex flex-col items-center gap-1 text-center">
      <p class="text-text">Nothing to do.</p>
      <p class="text-[13px] text-text-secondary">
        Snoozed and done todos are in Snoozed and Done.
      </p>
    </div>
  {:else}
    <div class="relative">
      {#each shownBuckets as bucket (bucket.label)}
        <div class="h-14 flex items-end gap-2 px-1.5 pb-2.5">
          <h2 class="text-[13px] font-semibold text-text-secondary">
            {bucket.label}
          </h2>
          <span class="text-[13px] text-muted tabular-nums">
            {bucketSizes.get(bucket.label) ?? bucket.entries.length}
          </span>
        </div>
        {#each chunksOf(bucket.entries, bucket.label) as chunk (chunk.key)}
          {#if chunk.kind === "open"}
            <OpenThread
              row={chunk.row}
              rowKey={threadRowKey(chunk.row.thread.id)}
              rowHeight={ROW_HEIGHT}
              closing={motion.thread}
            />
          {:else}
            <div
              class={[
                "flex flex-col rounded-[18px] bg-surface shadow-card transition-opacity duration-200",
                mail.openId !== null && "opacity-45",
              ]}
            >
              {#each chunk.items as entry (entryKey(entry))}
                <div
                  class={JOINED}
                  data-move-key={entryKey(entry)}
                  in:rowIn={entryKey(entry)}
                  out:rowOut={entryKey(entry)}
                >
                  {#if entry.kind === "todo"}
                    <TodoRow
                      todo={mail.todos.get(entry.todo.id) ?? entry.todo}
                      class="rounded-[inherit]"
                    />
                  {:else}
                    <ThreadRow
                      row={entry.email}
                      todo={entry.todo}
                      todoLook
                      class="rounded-[inherit]"
                    />
                  {/if}
                </div>
              {/each}
            </div>
          {/if}
        {/each}
      {/each}
    </div>
    {#if mail.todosHidden > 0}
      <TodoMore />
    {/if}
  {/if}
</div>
