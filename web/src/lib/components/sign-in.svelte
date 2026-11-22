<!-- @component The signed-out page: what the app asks Google for, and the sign-in button. -->
<script lang="ts">
  import Calendar from "@lucide/svelte/icons/calendar";
  import Info from "@lucide/svelte/icons/info";
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";
  import Mail from "@lucide/svelte/icons/mail";
  import Users from "@lucide/svelte/icons/users";
  import { onMount } from "svelte";
  import { resolve } from "$app/paths";
  import { getAuthState } from "../auth.svelte";
  import { preloadIdentityServices } from "../google-identity";
  import { FEEDBACK_URL, SOURCE_URL } from "../project-links";
  import GoogleMark from "./google-mark.svelte";
  import type { IconType } from "./icons";
  import Logo from "./logo.svelte";

  const ACCESS: { icon: IconType; title: string; detail: string }[] = [
    {
      icon: Mail,
      title: "Gmail",
      detail: "To read, send, snooze and label your mail.",
    },
    {
      icon: Users,
      title: "Contacts",
      detail: "To suggest names as you type an address.",
    },
    {
      icon: Calendar,
      title: "Calendar",
      detail: "To show conflicts and answer invites in place.",
    },
  ];

  // External links open in a new tab.
  const footerLinks: { label: string; href: string; external: boolean }[] = [
    { label: "Privacy", href: resolve("/privacy"), external: false },
    { label: "Support", href: resolve("/support"), external: false },
    { label: "Feedback", href: FEEDBACK_URL, external: true },
    { label: "Source code", href: SOURCE_URL, external: true },
  ];

  const auth = getAuthState();

  onMount(preloadIdentityServices);
</script>

<div class="min-h-dvh flex flex-col items-center">
  <main
    class="flex-1 w-full flex flex-col items-center justify-center px-safe-4 pt-safe-12 pb-safe-12"
  >
    <div class="w-full max-w-[400px] flex flex-col items-center">
      <Logo size="w-14 h-14" icon="w-7 h-7" />
      <h1 class="mt-5 text-[28px] leading-9 font-semibold tracking-[-0.02em]">
        Inbox
      </h1>
      <p class="mt-2 text-base leading-6 text-text-secondary text-center">
        Your Gmail, with snooze, pins and an @hafa.cc address.
      </p>

      <button
        type="button"
        onclick={auth.signIn}
        disabled={auth.busy}
        class="mt-8 w-full h-12 flex items-center justify-center gap-3 rounded-full border border-border-strong bg-surface text-[15px] font-semibold shadow-pill hover:bg-surface-hover transition-colors disabled:opacity-60"
      >
        {#if auth.busy}
          <LoaderCircle
            class="w-[18px] h-[18px] animate-spin"
            aria-hidden="true"
          />
        {:else}
          <GoogleMark />
        {/if}
        Sign in with Google
      </button>
      {#if auth.error}
        <p role="alert" class="mt-3 text-[13px] text-danger text-center">
          {auth.error}
        </p>
      {/if}

      <section
        aria-labelledby="access-title"
        class="mt-6 w-full rounded-[20px] border border-border bg-surface px-4 py-2"
      >
        <h2
          id="access-title"
          class="h-9 flex items-center text-[13px] font-semibold text-text-secondary"
        >
          Inbox will ask for access to
        </h2>
        <ul>
          {#each ACCESS as item (item.title)}
            <li class="flex gap-3 py-2 border-t border-separator">
              <item.icon
                class="w-[18px] h-[18px] mt-px shrink-0 text-text-secondary"
                aria-hidden="true"
              />
              <div>
                <div class="font-semibold">{item.title}</div>
                <div class="text-[13px] text-text-secondary">{item.detail}</div>
              </div>
            </li>
          {/each}
        </ul>
      </section>

      <div
        role="note"
        class="mt-4 w-full flex gap-2.5 px-3.5 py-3 rounded-[20px] bg-surface-muted text-[13px] leading-5 text-text-secondary"
      >
        <Info class="w-4 h-4 mt-0.5 shrink-0" aria-hidden="true" />
        <span>
          Google will say this app isn't verified. That's expected for a small
          app — choose
          <strong class="font-semibold text-text">Advanced</strong>, then
          continue.
        </span>
      </div>

      <nav
        aria-label="About Inbox"
        class="mt-3 flex flex-wrap justify-center gap-x-4 text-[13px] text-text-secondary"
      >
        {#each footerLinks as link (link.label)}
          <a
            href={link.href}
            target={link.external ? "_blank" : undefined}
            rel={link.external ? "noopener noreferrer" : undefined}
            class="min-h-12 inline-flex items-center hover:text-text hover:underline"
          >
            {link.label}
          </a>
        {/each}
      </nav>
    </div>
  </main>
</div>
