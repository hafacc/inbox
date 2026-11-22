<!-- @component A todo's text as latr shows it: `*bold*` and `_italic_` with the markers kept but dimmed, and `#tags` as chips, one naming a label in that label's color; its first line at the weight of a new email's subject. -->
<script lang="ts">
  import { type StyledNode, styledTree } from "@inbox/shared/todos";
  import { getMailState } from "../mail-state.svelte";

  const mail = getMailState();

  let { text }: { /** the text, markers included */ text: string } = $props();

  const lineEnd = $derived(text.indexOf("\n"));
  const firstLine = $derived(lineEnd < 0 ? text : text.slice(0, lineEnd));
  const rest = $derived(lineEnd < 0 ? "" : text.slice(lineEnd));
</script>

<!-- Spans with the editor's classes, not <strong>/<em>, so a row looks the same at rest and while editing. -->
{#snippet render(nodes: StyledNode[])}
  {#each nodes as node, index (index)}
    {#if node.kind === "text"}
      {node.text}
    {:else if node.kind === "marker"}
      <span class="md-marker">{node.text}</span>
    {:else if node.kind === "tag"}
      {@const color = mail.tagColor(node.text.replace(/^#/, ""))}
      {#if color === null}
        <span class="md-tag">{node.text}</span>
      {:else}
        <span class="md-tag md-tag-label" style:--tag-color={color}
          >{node.text}</span
        >
      {/if}
    {:else}
      <span class={node.style === "bold" ? "md-bold" : "md-italic"}
        >{@render render(node.children)}</span
      >
    {/if}
  {/each}
{/snippet}
<span class="todo-first-line">{@render render(styledTree(firstLine))}</span>
{@render render(styledTree(rest))}
