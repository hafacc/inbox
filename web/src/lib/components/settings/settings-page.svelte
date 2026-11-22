<!-- @component The settings: a nav of pages (General, Addresses, Encryption, Filters, Labels, Shortcuts & search), then Privacy, Support, Feedback and Source code, beside the page shown, in place of the mail. -->
<script lang="ts">
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import AtSign from "@lucide/svelte/icons/at-sign";
  import Code from "@lucide/svelte/icons/code";
  import Funnel from "@lucide/svelte/icons/funnel";
  import Inbox from "@lucide/svelte/icons/inbox";
  import Keyboard from "@lucide/svelte/icons/keyboard";
  import LifeBuoy from "@lucide/svelte/icons/life-buoy";
  import Lock from "@lucide/svelte/icons/lock";
  import MessageSquare from "@lucide/svelte/icons/message-square";
  import ShieldCheck from "@lucide/svelte/icons/shield-check";
  import SlidersHorizontal from "@lucide/svelte/icons/sliders-horizontal";
  import Tag from "@lucide/svelte/icons/tag";
  import { resolve } from "$app/paths";
  import { addressNoticeText } from "../../address/address-model";
  import { backToPreviousPlace } from "../../back-stack.svelte";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import { getMailState } from "../../mail-state.svelte";
  import { FEEDBACK_URL, SOURCE_URL } from "../../project-links";
  import { SETTINGS_SECTIONS, type SettingsSection } from "../../views";
  import AddressPanel from "../address-panel.svelte";
  import type { IconType } from "../icons";
  import IndicatorDot from "../indicator-dot.svelte";
  import { scrollPageTo } from "../page-scroll";
  import SendAsList from "../send-as-list.svelte";
  import EncryptionPage from "./encryption-page.svelte";
  import FiltersPage from "./filters-page.svelte";
  import GeneralPage from "./general-page.svelte";
  import HelpPage from "./help-page.svelte";
  import LabelsPage from "./labels-page.svelte";

  let {
    section,
    onDeleteAccount,
  }: {
    section: SettingsSection;
    /** delete the account; throws when the server couldn't */
    onDeleteAccount: () => Promise<void>;
  } = $props();

  const mail = getMailState();
  const hub = getComposeHub();
  // The address needs attention: the Addresses entries carry a dot.
  const addressTrouble = $derived(
    addressNoticeText(hub.settings.addressSetup, mail.now) !== null,
  );
  // Back to the page or view before, as the browser's back would.
  function leave(): void {
    if (!backToPreviousPlace()) mail.setView({ kind: "inbox" });
  }

  function openSection(next: SettingsSection): void {
    mail.setView({ kind: "settings", section: next });
    scrollPageTo(0);
  }
  const navItem =
    "relative h-10 flex items-center gap-3 px-3 rounded-full text-left transition-[background-color,box-shadow,color] duration-150";
  const ICONS: Record<SettingsSection, IconType> = {
    general: SlidersHorizontal,
    addresses: AtSign,
    encryption: Lock,
    filters: Funnel,
    labels: Tag,
    shortcuts: Keyboard,
  };
  // Pages about Inbox, under the sections; external ones open in a new tab.
  const ABOUT_LINKS: {
    label: string;
    href: string;
    icon: IconType;
    external: boolean;
  }[] = [
    {
      label: "Privacy",
      href: resolve("/privacy"),
      icon: ShieldCheck,
      external: false,
    },
    {
      label: "Support",
      href: resolve("/support"),
      icon: LifeBuoy,
      external: false,
    },
    {
      label: "Feedback",
      href: FEEDBACK_URL,
      icon: MessageSquare,
      external: true,
    },
    { label: "Source code", href: SOURCE_URL, icon: Code, external: true },
  ];
</script>

<div class="min-h-dvh flex">
  <nav
    aria-label="Settings"
    class="hidden sm:flex w-60 shrink-0 flex-col px-3 pb-4 sticky top-0 h-dvh bg-sidebar"
  >
    <div class="h-16 flex items-center gap-3 px-3">
      <span
        class="w-7 h-7 rounded-full bg-accent text-on-accent flex items-center justify-center"
        aria-hidden="true"
      >
        <Inbox class="w-4 h-4" />
      </span>
      <span class="text-base font-semibold tracking-[-0.01em]">Inbox</span>
    </div>
    <button
      type="button"
      onclick={() => mail.setView({ kind: "inbox" })}
      class="mb-4 h-10 flex items-center gap-3 px-3 rounded-full font-medium text-left text-text-secondary hover:bg-sidebar-hover hover:text-text transition-colors"
    >
      <ArrowLeft class="w-[18px] h-[18px]" aria-hidden="true" />
      Back to Inbox
    </button>
    <div
      class="h-8 flex items-end px-3 pb-2 text-[13px] font-semibold text-text-secondary"
    >
      Settings
    </div>
    <div class="flex flex-col gap-0.5">
      {#each SETTINGS_SECTIONS as entry (entry.section)}
        {@const Icon = ICONS[entry.section]}
        {@const active = entry.section === section}
        <button
          type="button"
          aria-current={active ? "page" : undefined}
          onclick={() => openSection(entry.section)}
          class={[
            navItem,
            active
              ? "bg-surface text-text font-semibold shadow-pill"
              : "text-text-secondary hover:bg-sidebar-hover hover:text-text",
          ]}
        >
          <IndicatorDot
            show={entry.section === "addresses" && addressTrouble}
            tone="warning"
            ring={active ? "var(--color-surface)" : "var(--color-sidebar)"}
            label="Needs attention"
          >
            <Icon class="w-[18px] h-[18px]" aria-hidden="true" />
          </IndicatorDot>
          <span class="flex-1">{entry.title}</span>
        </button>
      {/each}
    </div>
    <span class="flex-1"></span>
    {#each ABOUT_LINKS as link (link.label)}
      <a
        href={link.href}
        target={link.external ? "_blank" : undefined}
        rel={link.external ? "noopener noreferrer" : undefined}
        class="h-9 flex items-center gap-3 px-3 rounded-full text-[13px] text-text-secondary hover:bg-sidebar-hover hover:text-text transition-colors"
      >
        <link.icon class="w-4 h-4 shrink-0" aria-hidden="true" />
        {link.label}
      </a>
    {/each}
  </nav>
  <main class="flex-1 min-w-0 px-safe-4 sm:px-12 pt-safe pb-safe-24">
    <div class="sm:hidden h-14 flex items-center gap-2 overflow-x-auto -ml-1">
      <button
        type="button"
        onclick={leave}
        aria-label="Back"
        title="Back"
        class="w-12 h-12 shrink-0 flex items-center justify-center rounded-full text-text hover:bg-surface-hover"
      >
        <ArrowLeft class="w-5 h-5" aria-hidden="true" />
      </button>
      {#each SETTINGS_SECTIONS as entry (entry.section)}
        <button
          type="button"
          aria-current={entry.section === section ? "page" : undefined}
          onclick={() => openSection(entry.section)}
          class={[
            "hit-target h-8 px-3 rounded-full text-sm shrink-0",
            entry.section === section
              ? "bg-surface text-text font-semibold shadow-pill"
              : "text-text-secondary hover:bg-surface-hover",
          ]}
        >
          <IndicatorDot
            show={entry.section === "addresses" && addressTrouble}
            tone="warning"
            ring={entry.section === section
              ? "var(--color-surface)"
              : "var(--color-bg)"}
            label="Needs attention"
          >
            {entry.title}
          </IndicatorDot>
        </button>
      {/each}
      {#each ABOUT_LINKS as link (link.label)}
        <a
          href={link.href}
          target={link.external ? "_blank" : undefined}
          rel={link.external ? "noopener noreferrer" : undefined}
          class="hit-target h-8 px-3 flex items-center rounded-full text-sm text-text-secondary hover:bg-surface-hover shrink-0"
          >{link.label}</a
        >
      {/each}
    </div>
    <div class="w-full max-w-[760px] mx-auto">
      {#if section === "general"}
        <GeneralPage {onDeleteAccount} />
      {:else if section === "addresses"}
        <h1
          class="h-16 flex items-center text-xl font-semibold tracking-[-0.01em]"
        >
          Addresses
        </h1>
        <p class="mb-4 text-[13px] text-text-secondary">
          One @hafa.cc address per account. Mail to it arrives in your Gmail,
          and you can send from it here.
        </p>
        <AddressPanel />
        <SendAsList />
      {:else if section === "encryption"}
        <EncryptionPage />
      {:else if section === "filters"}
        <FiltersPage />
      {:else if section === "labels"}
        <LabelsPage />
      {:else}
        <HelpPage />
      {/if}
    </div>
  </main>
</div>
