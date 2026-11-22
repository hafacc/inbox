<!-- @component The privacy policy, docs/privacy.md, as a page anyone can open without signing in. -->
<script lang="ts">
  import DocPage from "../../lib/components/doc-page.svelte";
  import type { PageProps } from "./$types";

  let { data }: PageProps = $props();
</script>

<DocPage title="Privacy" html={data.html} />
