<!-- @component New messages kept on this device that aren't in Gmail yet, at the top of Drafts: a row each, newest first, that opens it in the composer. -->
<script lang="ts">
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { recipientsLine } from "../compose/compose-model";
  import type { LocalDraft } from "../compose/local-drafts";
  import Avatar from "./avatar.svelte";

  const hub = getComposeHub();

  function recipientsOf(draft: LocalDraft): string {
    if (draft.to.length > 0 || draft.cc.length > 0) {
      return recipientsLine([...draft.to, ...draft.cc]);
    } else if (draft.bcc.length > 0) {
      return `${recipientsLine(draft.bcc)} (Bcc)`;
    } else {
      return "(no recipients)";
    }
  }
</script>

{#if hub.newDrafts.length > 0}
  <div class="h-9 flex items-end px-1.5 pb-2.5">
    <h2 class="text-[13px] font-semibold text-text-secondary">
      Not in Gmail yet
    </h2>
  </div>
  <ul class="mb-4 flex flex-col rounded-[18px] bg-surface shadow-card">
    {#each hub.newDrafts as draft (draft.localId)}
      {@const first = draft.to[0] ??
        draft.cc[0] ??
        draft.bcc[0] ?? { name: "", email: "?" }}
      <li
        class="border-t border-separator first:border-t-0 first:rounded-t-[18px] last:rounded-b-[18px]"
      >
        <button
          type="button"
          onclick={() => void hub.openLocalDraft(draft.localId)}
          class="w-full h-[68px] flex items-center gap-3.5 px-5 text-left rounded-[inherit] hover:bg-surface-hover focus-visible:bg-surface-hover transition-colors"
        >
          <Avatar person={first} />
          <span class="flex-1 min-w-0 flex flex-col">
            <span class="truncate">
              <span class="text-text-secondary">To </span
              ><span class="font-semibold">{recipientsOf(draft)}</span>
            </span>
            <span class="truncate text-[13px]">
              <span class="font-medium">{draft.subject || "(no subject)"}</span>
              {#if draft.snippet}
                <span class="text-text-secondary"> — {draft.snippet}</span>
              {/if}
            </span>
          </span>
        </button>
      </li>
    {/each}
  </ul>
{/if}
