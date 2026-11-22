<!-- @component The app mark: the inbox tray on an accent tile. -->
<script lang="ts">
  import Inbox from "@lucide/svelte/icons/inbox";

  let { size = "w-7 h-7", icon = "w-4 h-4" }: { size?: string; icon?: string } =
    $props();
</script>

<span
  class={[
    "shrink-0 rounded-full bg-accent text-on-accent flex items-center justify-center",
    size,
  ]}
  aria-hidden="true"
>
  <Inbox class={icon} />
</span>
