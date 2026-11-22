<!-- @component Settings › General: signature, the default From address, images, varied send times, todos, mail saved for offline, the theme, and deleting the account. -->
<script lang="ts">
  import { markdownToHtml } from "@inbox/shared/compose";
  import { MAX_OFFLINE_DAYS } from "@inbox/shared/offline-set";
  import {
    MAX_MAX_SHIFT_MINUTES,
    MIN_MAX_SHIFT_MINUTES,
  } from "@inbox/shared/send-suggest";
  import { formatSize } from "@inbox/shared/time-format";
  import X from "@lucide/svelte/icons/x";
  import { getComposeHub } from "../../compose/compose-hub.svelte";
  import { cornerSide } from "../../corner-side.svelte";
  import type { ImageMode } from "../../images/image-settings";
  import { getMailState } from "../../mail-state.svelte";
  import { cleanMessageHtml } from "../../sanitize";
  import ThemePicker from "../theme-picker.svelte";
  import ThemeSegmented from "../theme-segmented.svelte";

  let {
    onDeleteAccount,
  }: {
    /** delete the account; throws when the server couldn't */
    onDeleteAccount: () => Promise<void>;
  } = $props();

  const hub = getComposeHub();
  const mail = getMailState();
  // While dragging, the offline slider shows its own value; it saves on release.
  let dragDays = $state<number | null>(null);
  const offlineDays = $derived(dragDays ?? mail.offlineDays);
  // What this site stores on the device, nearly all of it saved mail; null when the browser won't say.
  let spaceUsed = $state<number | null>(null);
  $effect(() => {
    void mail.offlineCount;
    let current = true;
    navigator.storage
      ?.estimate?.()
      .then((estimate) => {
        if (current) spaceUsed = estimate.usage ?? null;
      })
      .catch(() => {});
    return () => {
      current = false;
    };
  });
  const DELETE_WORD = "delete";
  let deleteTyped = $state("");
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);

  async function deleteAccount() {
    if (deleteTyped.trim().toLowerCase() !== DELETE_WORD || deleting) return;
    deleting = true;
    deleteError = null;
    try {
      await onDeleteAccount();
    } catch (failure) {
      console.error("account deletion failed", failure);
      deleteError =
        "Couldn't delete the account. Check your connection and try again.";
      deleting = false;
    }
  }
  const SAVE_DELAY_MS = 600;

  let signature = $state("");
  let saveTimer: ReturnType<typeof setTimeout> | null = null;
  let error = $state<string | null>(null);
  // While dragging, the slider shows its own value; it saves on release.
  let dragMinutes = $state<number | null>(null);
  const maxMinutes = $derived(
    dragMinutes ?? hub.settings.send.varySendMaxMinutes,
  );

  const preview = $derived(
    signature.trim() === ""
      ? ""
      : cleanMessageHtml(markdownToHtml(signature), new Map()).html,
  );

  const imageSenders = $derived([...hub.settings.images.senders].sort());

  // The saved signature, until the user starts typing (the settings may load after this page).
  let edited = false;
  $effect(() => {
    const saved = hub.settings.send.signature;
    if (!edited) signature = saved;
  });

  $effect(() => () => flushSignature());

  function saveWrite(write: Promise<void>) {
    error = null;
    write.catch((failure) => {
      console.error("settings write failed", failure);
      error = "Couldn't save that. Try again.";
    });
  }

  function save(patch: Parameters<typeof hub.settings.update>[0]) {
    error = null;
    hub.settings.update(patch).catch((failure) => {
      console.error("settings write failed", failure);
      error = "Couldn't save that. Try again.";
    });
  }

  function saveSignatureSoon() {
    edited = true;
    if (saveTimer !== null) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      saveTimer = null;
      save({ signature });
    }, SAVE_DELAY_MS);
  }

  function flushSignature() {
    if (saveTimer === null) return;
    clearTimeout(saveTimer);
    saveTimer = null;
    save({ signature });
  }

  const heading = "text-sm font-semibold";
  const card = "rounded-[22px] bg-surface shadow-card px-4 pt-3.5 pb-4";
  const hint = "text-[13px] text-text-secondary";
</script>

<h1 class="h-16 flex items-center text-xl font-semibold tracking-[-0.01em]">
  General
</h1>
<div class="flex flex-col gap-3">
  <section aria-labelledby="sig-title" class={card}>
    <div class="flex items-center justify-between">
      <h2 id="sig-title" class={heading}>Signature</h2>
      <span class="text-xs text-text-secondary">*bold* _italic_</span>
    </div>
    <div class="mt-2.5 grid grid-cols-2 gap-3">
      <label class="sr-only" for="settings-signature">Signature</label>
      <textarea
        id="settings-signature"
        rows="3"
        bind:value={signature}
        oninput={saveSignatureSoon}
        onblur={flushSignature}
        placeholder="— Your name"
        class="w-full resize-y rounded-xl border border-border-strong bg-field px-3 py-2 font-mono text-[13px] leading-5 outline-none focus:border-focus focus:ring-1 focus:ring-focus"
      ></textarea>
      <div class="rounded-xl bg-surface-muted px-3 py-2 min-h-[72px]">
        <div class="text-xs text-text-secondary mb-1">Preview</div>
        {#if preview}
          <!-- Sanitized with DOMPurify; markdownToHtml escapes the text too. -->
          <div class="rendered-markdown text-sm leading-[22px]">
            {@html preview}
          </div>
        {:else}
          <div class={hint}>No signature</div>
        {/if}
      </div>
    </div>
    <p class={[hint, "mt-2"]}>
      Added under every message you send, after a “-- ” line in plain text.
    </p>
  </section>

  <section aria-labelledby="from-title" class={card}>
    <div class="flex items-center gap-4">
      <h2 id="from-title" class={[heading, "flex-1"]}>
        <label for="settings-from">Default from</label>
      </h2>
      <select
        id="settings-from"
        value={hub.settings.send.sendAsDefault ?? ""}
        onchange={(event) =>
          save({ sendAsDefault: event.currentTarget.value || null })}
        class="h-9 min-w-56 px-2.5 rounded-xl border border-border-strong bg-field text-sm outline-none focus:border-focus"
      >
        <option value="">Gmail's default</option>
        {#each hub.fromOptions as option (option.mailbox.email)}
          <option value={option.mailbox.email}>{option.mailbox.email}</option>
        {/each}
      </select>
    </div>
  </section>

  <section aria-labelledby="img-title" class={card}>
    <fieldset>
      <legend id="img-title" class={heading}>Images</legend>
      <div class="mt-2 flex flex-col gap-1.5">
        {#each [
          ["ask", "Ask before showing"],
          ["always", "Always show"],
        ] as [mode, text] (mode)}
          <label class="flex items-center gap-2 text-sm">
            <input
              type="radio"
              name="settings-images"
              value={mode}
              checked={hub.settings.images.mode === mode}
              onchange={() =>
                saveWrite(hub.settings.setImageMode(mode as ImageMode))}
              class="accent-accent"
            >
            {text}
          </label>
        {/each}
      </div>
    </fieldset>
    <p class={[hint, "mt-2"]}>
      Shown images load straight from the sender, who can then see your IP
      address and when you opened the mail.
    </p>
    {#if hub.settings.images.mode === "ask" && imageSenders.length > 0}
      <h3 class="mt-3 mb-1 text-[13px] font-semibold text-text-secondary">
        Always shown from
      </h3>
      <ul class="flex flex-col">
        {#each imageSenders as sender (sender)}
          <li class="h-9 flex items-center gap-3 border-t border-surface-muted">
            <span class="flex-1 min-w-0 truncate">{sender}</span>
            <button
              type="button"
              aria-label={`Stop always showing images from ${sender}`}
              onclick={() =>
                saveWrite(hub.settings.setSenderImages(sender, false))}
              class="w-7 h-7 flex items-center justify-center rounded-full text-text-secondary hover:bg-surface-hover"
            >
              <X class="w-4 h-4" />
            </button>
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <section class={card}>
    <div class="flex items-start gap-4">
      <div class="flex-1">
        <h2 class={heading}>
          <label for="settings-vary">Vary scheduled send times</label>
        </h2>
        <p class={[hint, "mt-1"]}>
          Suggested times shift by a few minutes either way, so mail doesn't
          land on the exact hour.
        </p>
      </div>
      <input
        id="settings-vary"
        type="checkbox"
        role="switch"
        aria-checked={hub.settings.send.varySendTimes}
        checked={hub.settings.send.varySendTimes}
        onchange={(event) =>
          save({ varySendTimes: event.currentTarget.checked })}
        class="mt-1 w-10 h-6 shrink-0 appearance-none rounded-full bg-border-strong checked:bg-accent relative cursor-pointer transition-colors before:content-[''] before:absolute before:top-0.5 before:left-0.5 before:w-5 before:h-5 before:rounded-full before:bg-surface-raised checked:before:bg-on-accent before:shadow before:transition-transform checked:before:translate-x-4"
      >
    </div>
    {#if hub.settings.send.varySendTimes}
      <div class="mt-3 flex items-center gap-4">
        <label for="settings-vary-max" class="text-sm w-40 shrink-0"
          >Up to {maxMinutes} {maxMinutes === 1 ? "minute" : "minutes"}</label
        >
        <input
          id="settings-vary-max"
          type="range"
          min={MIN_MAX_SHIFT_MINUTES}
          max={MAX_MAX_SHIFT_MINUTES}
          step="1"
          value={maxMinutes}
          oninput={(event) => {
            dragMinutes = Number(event.currentTarget.value);
          }}
          onchange={(event) => {
            dragMinutes = null;
            save({
              varySendMaxMinutes: Math.round(Number(event.currentTarget.value)),
            });
          }}
          class="flex-1 accent-accent"
        >
      </div>
    {/if}
  </section>

  <section class={card}>
    <div class="flex items-start gap-4">
      <div class="flex-1">
        <h2 class={heading}>
          <label for="settings-todos">Show todos</label>
        </h2>
        <p class={[hint, "mt-1"]}>
          Todos sit among your mail and in a list of their own. They snooze and
          pin like mail, and can repeat.
        </p>
      </div>
      <input
        id="settings-todos"
        type="checkbox"
        role="switch"
        aria-checked={hub.settings.showTodos}
        checked={hub.settings.showTodos}
        onchange={(event) =>
          saveWrite(hub.settings.setShowTodos(event.currentTarget.checked))}
        class="mt-1 w-10 h-6 shrink-0 appearance-none rounded-full bg-border-strong checked:bg-accent relative cursor-pointer transition-colors before:content-[''] before:absolute before:top-0.5 before:left-0.5 before:w-5 before:h-5 before:rounded-full before:bg-surface-raised checked:before:bg-on-accent before:shadow before:transition-transform checked:before:translate-x-4"
      >
    </div>
  </section>

  <section class={card}>
    <div class="flex items-start gap-4">
      <div class="flex-1">
        <h2 class={heading}>
          <label for="settings-quiet-bundles"
            >Skip notifications for bundled mail</label
          >
        </h2>
        <p class={[hint, "mt-1"]}>
          New mail that goes into a bundle arrives without a notification. All
          other mail still notifies.
        </p>
      </div>
      <input
        id="settings-quiet-bundles"
        type="checkbox"
        role="switch"
        aria-checked={hub.settings.quietBundles}
        checked={hub.settings.quietBundles}
        onchange={(event) =>
          saveWrite(hub.settings.setQuietBundles(event.currentTarget.checked))}
        class="mt-1 w-10 h-6 shrink-0 appearance-none rounded-full bg-border-strong checked:bg-accent relative cursor-pointer transition-colors before:content-[''] before:absolute before:top-0.5 before:left-0.5 before:w-5 before:h-5 before:rounded-full before:bg-surface-raised checked:before:bg-on-accent before:shadow before:transition-transform checked:before:translate-x-4"
      >
    </div>
  </section>

  <section aria-labelledby="offline-title" class={card}>
    <h2 id="offline-title" class={heading}>Offline</h2>
    <p class={[hint, "mt-1"]}>
      Saved mail opens without a connection. Your inbox, snoozed mail and drafts
      are always saved, and so is encrypted mail, so search can find words
      inside it. Attachments and images open when you're back online.
    </p>
    <div class="mt-3 flex items-center gap-4">
      <label for="settings-offline-days" class="text-sm w-40 shrink-0"
        >{offlineDays === null
          ? ""
          : offlineDays === 0
            ? "No other mail"
            : `Also the last ${offlineDays} ${offlineDays === 1 ? "day" : "days"}`}</label
      >
      <input
        id="settings-offline-days"
        type="range"
        min="0"
        max={MAX_OFFLINE_DAYS}
        step="1"
        value={offlineDays ?? MAX_OFFLINE_DAYS}
        disabled={offlineDays === null}
        oninput={(event) => {
          dragDays = Number(event.currentTarget.value);
        }}
        onchange={(event) => {
          const days = Number(event.currentTarget.value);
          // Shown at once; the saved copy confirms it once the setting is written.
          mail.offlineDays = days;
          dragDays = null;
          void mail.savedMail.setDays(days);
        }}
        class="flex-1 accent-accent"
      >
    </div>
    <p class={[hint, "mt-2"]}>
      {mail.offlineCount}
      {mail.offlineCount === 1 ? "thread" : "threads"}
      saved{spaceUsed === null
        ? ""
        : `, using ${formatSize(spaceUsed)} on this device`}.
    </p>
  </section>

  <section class={card}>
    <div class="flex items-center gap-4">
      <h2 class={[heading, "flex-1"]}>Theme</h2>
      <div class="w-72"><ThemeSegmented /></div>
    </div>
    <p class={[hint, "mt-1"]}>
      Colors follow your account to every device; light or dark is set per
      device.
    </p>
    <div class="mt-3">
      <ThemePicker
        onError={(message) => {
          error = message;
        }}
      />
    </div>
    <div class="mt-4 flex items-center gap-4">
      <h3 class={[heading, "flex-1"]}>Round buttons</h3>
      <fieldset
        class="w-40 flex p-0.5 rounded-full bg-surface-muted border-0 m-0"
      >
        <legend class="sr-only">Round buttons</legend>
        {#each [
          { side: "left", label: "Left" },
          { side: "right", label: "Right" },
        ] as const as option (option.side)}
          <button
            type="button"
            aria-pressed={cornerSide.side === option.side}
            onclick={() => cornerSide.set(option.side)}
            class={[
              "flex-1 h-7 inline-flex items-center justify-center rounded-full text-xs font-medium transition-colors",
              cornerSide.side === option.side
                ? "bg-surface text-text shadow-pop"
                : "text-text-secondary hover:text-text",
            ]}
          >
            {option.label}
          </button>
        {/each}
      </fieldset>
    </div>
    <p class={[hint, "mt-1"]}>
      The bottom corner a phone's round buttons sit in. Set per device; you can
      also drag a button down, then across to the other corner.
    </p>
  </section>

  {#if error}
    <p role="alert" class="text-[13px] text-danger">{error}</p>
  {/if}

  <section aria-labelledby="delete-title" class={card}>
    <h2 id="delete-title" class={heading}>Delete account</h2>
    <p class={[hint, "mt-1"]}>
      Deletes everything Inbox keeps about you: settings, snooze times,
      scheduled sends, push tokens, your @hafa.cc address and your encryption
      keys. It also ends Inbox's access to your Google account and clears this
      browser. Your mail stays in Gmail; snoozed threads go back to your inbox.
      This can't be undone.
    </p>
    <form
      class="mt-3 flex flex-wrap items-center gap-2"
      onsubmit={(event) => {
        event.preventDefault();
        void deleteAccount();
      }}
    >
      <label class="flex items-center gap-2 text-[13px]">
        Type <strong class="font-semibold">{DELETE_WORD}</strong> to confirm
        <input
          type="text"
          autocomplete="off"
          spellcheck="false"
          bind:value={deleteTyped}
          disabled={deleting}
          class="h-9 w-40 px-3.5 rounded-full border border-border bg-transparent text-sm"
        >
      </label>
      <button
        type="submit"
        disabled={deleteTyped.trim().toLowerCase() !== DELETE_WORD || deleting}
        class="h-9 px-4 rounded-full bg-danger text-on-danger text-sm font-semibold disabled:opacity-40"
      >
        {deleting ? "Deleting…" : "Delete account"}
      </button>
    </form>
    {#if deleteError}
      <p role="alert" class="mt-2 text-[13px] text-danger">{deleteError}</p>
    {/if}
  </section>
</div>
