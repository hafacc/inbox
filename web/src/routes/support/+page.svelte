<!-- @component How to get help with Inbox, docs/support.md, as a page anyone can open without signing in. -->
<script lang="ts">
  import DocPage from "../../lib/components/doc-page.svelte";
  import type { PageProps } from "./$types";

  let { data }: PageProps = $props();
</script>

<DocPage title="Support" html={data.html} />
