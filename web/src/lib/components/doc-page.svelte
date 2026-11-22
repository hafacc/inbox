<!-- @component A page of rendered markdown from docs/, such as the privacy policy, with a link back to the app. -->
<script lang="ts">
  import { resolve } from "$app/paths";

  /** The page's tab title and its rendered HTML. */
  let { title, html }: { title: string; html: string } = $props();
</script>

<svelte:head>
  <title>{title} · Inbox</title>
</svelte:head>

<main class="min-h-dvh px-4 py-10">
  <article class="doc mx-auto max-w-[680px] leading-6">
    <a
      href={resolve("/")}
      class="text-[13px] font-semibold text-accent-strong hover:underline"
    >
      ← Inbox
    </a>
    {@html html}
  </article>
</main>

<style>
  .doc :global(h1) {
    margin: 1.5rem 0 1rem;
    font-size: 1.5rem;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  .doc :global(h2) {
    margin: 2rem 0 0.5rem;
    font-size: 1.0625rem;
    font-weight: 600;
  }
  .doc :global(p) {
    margin: 0.75rem 0;
  }
  .doc :global(ul) {
    margin: 0.75rem 0;
    padding-left: 1.25rem;
    list-style: disc;
  }
  .doc :global(li) {
    margin: 0.375rem 0;
  }
  .doc :global(a) {
    color: var(--color-accent-strong);
    text-decoration: underline;
    overflow-wrap: anywhere;
  }
  .doc :global(strong) {
    font-weight: 600;
  }
</style>
