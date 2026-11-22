<!-- @component Sidebar footer: the account, opening a menu with the theme and sign-out. -->
<script lang="ts">
  import Bell from "@lucide/svelte/icons/bell";
  import BellOff from "@lucide/svelte/icons/bell-off";
  import LogOut from "@lucide/svelte/icons/log-out";
  import Settings from "@lucide/svelte/icons/settings";
  import { addressNoticeText } from "../address/address-model";
  import { getComposeHub } from "../compose/compose-hub.svelte";
  import { getMailState } from "../mail-state.svelte";
  import { getNotificationState } from "../notifications.svelte";
  import Avatar from "./avatar.svelte";
  import IndicatorDot from "./indicator-dot.svelte";
  import { closeMenu } from "./menu-motion";
  import { popover } from "./popover";
  import ThemeSegmented from "./theme-segmented.svelte";

  let {
    onSignOut,
    onSettings,
  }: { onSignOut: () => void; onSettings: () => void } = $props();

  const mail = getMailState();
  const notifications = getNotificationState();
  const NOTIFICATION_HINTS = {
    on: "On",
    off: "Off",
    blocked: "Blocked by browser",
  } as const;
  const person = $derived({ name: "", email: mail.email });
  const hub = getComposeHub();
  const addressTrouble = $derived(
    addressNoticeText(hub.settings.addressSetup, mail.now),
  );
  let open = $state(false);
  let root: HTMLDivElement;

  // A collapsed sidebar shrinks back when the pointer leaves it; close with it.
  $effect(() => {
    if (!open) return;
    const sidebar = root.closest("aside");
    function onLeave() {
      if (sidebar?.dataset.collapsed === "true") open = false;
    }
    sidebar?.addEventListener("mouseleave", onLeave);
    return () => sidebar?.removeEventListener("mouseleave", onLeave);
  });
</script>

<div bind:this={root} class="relative mt-2">
  <button
    type="button"
    onclick={() => {
      open = !open;
    }}
    aria-expanded={open}
    aria-label={`Account: ${mail.email}${addressTrouble === null ? "" : ". Your @hafa.cc address isn't set up"}`}
    title={mail.email}
    class="w-full h-11 flex items-center gap-3 px-1.5 group-data-[collapsed=true]/sidebar:h-10 group-data-[collapsed=true]/sidebar:px-[calc((2.5rem-32px)/2)] group-data-[collapsed=true]/sidebar:group-hover/sidebar:h-11 group-data-[collapsed=true]/sidebar:group-hover/sidebar:px-1.5 rounded-full group-data-[collapsed=true]/sidebar:rounded-full group-data-[collapsed=true]/sidebar:group-hover/sidebar:rounded-full text-left hover:bg-sidebar-hover transition-colors"
  >
    <IndicatorDot show={addressTrouble !== null} tone="warning" round>
      <Avatar {person} />
    </IndicatorDot>
    <span
      class="flex-1 min-w-0 leading-[18px] group-data-[collapsed=true]/sidebar:hidden group-data-[collapsed=true]/sidebar:group-hover/sidebar:block"
    >
      <span class="block text-[13px] font-semibold truncate">{mail.email}</span>
      {#if mail.offline}
        <span class="block text-xs text-text-secondary">Offline</span>
      {/if}
    </span>
  </button>
  {#if open}
    <div
      out:closeMenu
      {@attach popover({
        onClose: () => {
          open = false;
        },
      })}
      class="absolute bottom-full mb-2 left-0 w-56 rounded-[20px] bg-surface-raised shadow-pop z-30 animate-rise p-2 flex flex-col gap-2"
    >
      <div class="px-2 pt-1 text-xs text-text-secondary truncate">
        {mail.email}
      </div>
      <ThemeSegmented />
      {#if notifications.status !== "unsupported"}
        <button
          type="button"
          disabled={notifications.busy || notifications.status === "blocked"}
          aria-pressed={notifications.status === "on"}
          onclick={() =>
            notifications.status === "on"
              ? notifications.disable()
              : notifications.enable()}
          title={notifications.status === "blocked"
            ? "Allow notifications for this site in the browser's settings"
            : undefined}
          class="w-full h-9 pointer-coarse:h-12 flex items-center gap-2.5 px-2.5 rounded-full text-sm text-text hover:bg-surface-hover disabled:hover:bg-transparent transition-colors"
        >
          {#if notifications.status === "on"}
            <Bell class="w-4 h-4 text-text-secondary" aria-hidden="true" />
          {:else}
            <BellOff class="w-4 h-4 text-text-secondary" aria-hidden="true" />
          {/if}
          <span class="flex-1 text-left">Notifications</span>
          <span class="text-xs text-text-secondary">
            {notifications.busy
              ? "…"
              : NOTIFICATION_HINTS[notifications.status]}
          </span>
        </button>
        {#if notifications.error}
          <p class="px-2.5 text-xs text-text-secondary">
            {notifications.error}
          </p>
        {/if}
      {/if}
      <button
        type="button"
        onclick={() => {
          open = false;
          onSettings();
        }}
        class="w-full h-9 pointer-coarse:h-12 flex items-center gap-2.5 px-2.5 rounded-full text-sm text-text hover:bg-surface-hover transition-colors"
      >
        <IndicatorDot
          show={addressTrouble !== null}
          tone="warning"
          ring="var(--color-surface-raised)"
        >
          <Settings class="w-4 h-4 text-text-secondary" aria-hidden="true" />
        </IndicatorDot>
        Settings
      </button>
      <button
        type="button"
        onclick={() => {
          open = false;
          onSignOut();
        }}
        class="w-full h-9 pointer-coarse:h-12 flex items-center gap-2.5 px-2.5 rounded-full text-sm text-text hover:bg-surface-hover transition-colors"
      >
        <LogOut class="w-4 h-4 text-text-secondary" aria-hidden="true" />
        Sign out
      </button>
    </div>
  {/if}
</div>
