<!-- @component A person's contact photo when their contacts have one, else their initials on theme colors picked from their address. -->
<script lang="ts">
  import { initials } from "@inbox/shared/initials";
  import type { Person } from "@inbox/shared/mime";
  import { avatarColors } from "../avatar";
  import { photoObjectUrl, photoSize } from "../contacts/photo-cache";
  import { photoDirectory } from "../contacts/photo-directory.svelte";

  let {
    person,
    size = 32,
    text = "text-[13px]",
    ring = false,
    single = false,
  }: {
    /** whose avatar it is */
    person: Person;
    /** its width and height in CSS pixels */
    size?: number;
    /** the initials' text size class */
    text?: string;
    /** whether a ring in the surface color sets it off from overlapping avatars */
    ring?: boolean;
    /** whether only the first initial shows */
    single?: boolean;
  } = $props();

  let photo = $state<string | null>(null);
  const url = $derived(photoDirectory.photoFor(person.email));
  const colors = $derived(avatarColors(person.email));

  $effect(() => {
    photo = null;
    if (url === null) return;
    let current = true;
    void photoObjectUrl(url, photoSize(size, window.devicePixelRatio)).then(
      (loaded) => {
        if (current) photo = loaded;
      },
    );
    return () => {
      current = false;
    };
  });
</script>

<span
  aria-hidden="true"
  style:width={`${size}px`}
  style:height={`${size}px`}
  style:background-color={photo === null ? colors.fill : null}
  style:color={colors.ink}
  class={[
    "shrink-0 rounded-full font-semibold flex items-center justify-center leading-none overflow-hidden",
    text,
    ring && "shadow-[0_0_0_2px_var(--color-surface)]",
  ]}
>
  {#if photo !== null}
    <img src={photo} alt="" class="w-full h-full object-cover">
  {:else}
    {initials(person, single)}
  {/if}
</span>
