<!-- @component A label's threads as a bundle: a row like a thread's, which opens in place into a raised card listing them while the rest of the list dims. Closed, on touch, a swipe left marks them all done and right snoozes them all; a long press opens a round menu around the finger with Pin all and Delete all, placed as a thread's. -->
<script lang="ts">
  import { bundleCounts } from "@inbox/shared/bundles";
  import { rowHoldWedges } from "@inbox/shared/hold-menu";
  import {
    type BundleSwipeAction,
    bundleSwipeAction,
    bundleSwipeLabel,
  } from "@inbox/shared/swipe";
  import { formatListDate } from "@inbox/shared/time-format";
  import Check from "@lucide/svelte/icons/check";
  import CheckCheck from "@lucide/svelte/icons/check-check";
  import Clock from "@lucide/svelte/icons/clock";
  import Pin from "@lucide/svelte/icons/pin";
  import Trash2 from "@lucide/svelte/icons/trash-2";
  import { holdMenu } from "../hold-menu.svelte";
  import { inputMode } from "../input-mode.svelte";
  import { isEditableTarget } from "../keyboard";
  import { bundleFooter } from "../list-status";
  import { getMailState, type MailList } from "../mail-state.svelte";
  import { displayName } from "../thread-summary";
  import LabelMark from "./label-mark.svelte";
  import LabelSettingsButton from "./label-settings-button.svelte";
  import { closingThread } from "./open-motion.svelte";
  import OpenThread from "./open-thread.svelte";
  import { grow, ListedRows } from "./row-motion";
  import { RowSwipe } from "./row-swipe.svelte";
  import SnoozePopover from "./snooze-popover.svelte";
  import { stuck } from "./sticky";
  import SwipeBackdrop, { type SwipeLook } from "./swipe-backdrop.svelte";
  import ThreadRow from "./thread-row.svelte";

  type ListRow = MailList["rows"][number];
  type ThreadItem = Extract<ListRow, { kind: "thread" }>;

  let {
    bundle,
    shown,
    more,
    expanded,
    closing: shrinking = false,
    class: className = "",
  }: {
    /** its row in the list: its label and all its threads, newest first */
    bundle: Extract<ListRow, { kind: "bundle" }>;
    /** the thread rows it shows while open */
    shown: readonly ThreadItem[];
    /** its foot while open: loaded threads not shown yet, and whether Gmail has more */
    more: Extract<ListRow, { kind: "more" }> | null;
    /** whether it is open, listing its threads */
    expanded: boolean;
    /** whether it is open but shrinking back into its row */
    closing?: boolean;
    /** classes added to the collapsed row */
    class?: string;
  } = $props();

  // The height of a thread's row in here, which an opened one grows out of.
  const COMPACT_ROW_HEIGHT = 65;
  const mail = getMailState();
  const labelId = $derived(bundle.labelId);
  const rows = $derived(bundle.threads.map((thread) => thread.data));
  const label = $derived(mail.userLabels.find((entry) => entry.id === labelId));
  const name = $derived(label?.name ?? labelId);
  const unread = $derived(rows.filter((row) => row.thread.unread).length);
  // Gmail has more of the label's inbox threads than were fetched, so counts are "N+".
  const counts = $derived(bundleCounts(rows.length, unread, bundle.more));
  const newest = $derived(
    rows.reduce((latest, row) => Math.max(latest, row.thread.lastDate), 0),
  );
  const focused = $derived(mail.focusKey === bundle.key && inputMode.keyboard);
  const openShown = $derived(
    shown.find((item) => item.id === mail.openId) ?? null,
  );
  // A thread just closed in here, still shown open while it shrinks back into its row.
  const closing = closingThread(mail, () =>
    openShown === null || !expanded ? null : openShown.id,
  );
  // An open thread lifts out of the bundle's card as it does out of a day's, splitting the rows around it.
  type Piece =
    | { kind: "rows"; key: string; rows: ThreadItem[] }
    | { kind: "open"; key: string; row: ThreadItem };
  const pieces = $derived.by(() => {
    let current: Piece & { kind: "rows" } = {
      kind: "rows",
      key: "top",
      rows: [],
    };
    const out: Piece[] = [current];
    for (const item of shown) {
      if (item.id === mail.openId || closing.has(item.id)) {
        out.push({ kind: "open", key: `open:${item.id}`, row: item });
        current = { kind: "rows", key: `after:${item.id}`, rows: [] };
        out.push(current);
      } else {
        current.rows.push(item);
      }
    }
    return out;
  });
  const footer = $derived(
    more === null
      ? null
      : bundleFooter(
          more.hidden,
          more.more,
          more.loading,
          mail.bundleError(labelId),
        ),
  );
  const senders = $derived.by(() => {
    const seen = new Map<string, string>();
    for (const row of rows) {
      const sender = row.thread.senders[0];
      if (sender && !seen.has(sender.email)) {
        seen.set(sender.email, displayName(sender, mail.email));
      }
    }
    return [...seen.values()].join(", ");
  });
  const subjects = $derived(
    rows
      .map((row) => row.thread.subject)
      .filter((subject) => subject !== "")
      .slice(0, 3)
      .join(" · "),
  );
  const summary = $derived(
    [
      `${counts.threads} ${rows.length === 1 && !bundle.more ? "thread" : "threads"}`,
      counts.unread,
      senders !== "" && `from ${senders}`,
    ]
      .filter(Boolean)
      .join(", "),
  );
  // Threads joining or leaving the shown ones grow in or collapse out, those "Show more" adds too;
  // one only moving to another piece, as an open thread splits them, stays as it is.
  const listed = new ListedRows();
  const { enter: rowIn, leave: rowOut } = listed;
  $effect.pre(() => {
    listed.note(new Set(shown.map((item) => item.key)));
  });

  // The end of the open list coming into view fetches more of the bundle, if Gmail has more.
  function endWatch(node: HTMLElement) {
    const observer = new IntersectionObserver(
      (entries) => {
        mail.noteBundleEnd(
          labelId,
          entries.some((entry) => entry.isIntersecting),
        );
      },
      { rootMargin: "400px" },
    );
    observer.observe(node);
    return () => {
      observer.disconnect();
      mail.noteBundleEnd(labelId, false);
    };
  }

  // "Show more" shows every loaded thread, and fetches the next page when they all show already.
  function showMore() {
    mail.dispatch({ type: "showAll" });
    if (footer !== "all") void mail.loadMoreBundle(labelId);
  }

  let button = $state<HTMLButtonElement>();

  // Done all, Snooze all, Pin all and Delete all act on every thread in it, as the open bundle's
  // Done all does.
  const threadIds = $derived(rows.map((row) => row.thread.id));
  const snoozeOpen = $derived(mail.snoozeMenuFor === bundle.key);
  function doneAll() {
    void mail.doneGroup(labelId, threadIds);
  }
  function pinAll() {
    void mail.pinGroup(labelId, threadIds);
  }
  function deleteAll() {
    void mail.trashGroup(labelId, threadIds);
  }
  function runBundleAction(action: BundleSwipeAction) {
    if (action === "done") doneAll();
    else mail.openSnoozeMenu(bundle.key);
  }
  // A closed bundle's row swipes as a thread's does: left marks every thread done, right snoozes
  // them all. A long press opens a round menu around the finger, as a thread's does, with Pin all
  // and Delete all.
  const swipe = new RowSwipe<BundleSwipeAction>(
    () => ({
      start: bundleSwipeAction("start", expanded),
      end: bundleSwipeAction("end", expanded),
    }),
    runBundleAction,
    (pointerId, press) => openBundleMenu(pointerId, press),
  );
  function openBundleMenu(pointerId: number, press: { x: number; y: number }) {
    if (expanded) return;
    holdMenu.open({
      x: press.x,
      y: press.y,
      items: rowHoldWedges(
        ["pin", "delete"] as const,
        press.x,
        press.y,
        window.innerWidth,
        window.innerHeight,
      ).map((wedge) =>
        wedge.id === "pin"
          ? { ...wedge, label: "Pin all", icon: Pin }
          : { ...wedge, label: "Delete all", icon: Trash2, tone: "danger" },
      ),
      pill: name,
      label: "Bundle actions",
      pointerId,
      press,
      focusFirst: false,
      onPick: (picked) => (picked === "pin" ? pinAll() : deleteAll()),
      onClose: () => swipe.endHold(),
    });
  }
  const swipeLook = $derived<Record<BundleSwipeAction, SwipeLook>>({
    snooze: {
      soft: "bg-snooze-soft text-on-snooze-soft",
      strong: "bg-snooze text-on-snooze",
      label: bundleSwipeLabel("snooze", counts.threads),
      icon: Clock,
    },
    done: {
      soft: "bg-done-soft text-done",
      strong: "bg-done text-on-done",
      label: bundleSwipeLabel("done", counts.threads),
      icon: Check,
    },
  });
  // While the open bundle's header is held at the top, its threads are cut off at the header's
  // bottom edge, so they go into it rather than on behind it. Not with a thread open in here,
  // whose own headers stick above the bundle's.
  function clipUnderHead(section: HTMLElement) {
    const cut = openShown === null;
    let frame: number | null = null;
    const pieceElements = () =>
      section.querySelectorAll<HTMLElement>(":scope > [data-bundle-piece]");
    const clip = () => {
      frame = null;
      const head = section.querySelector<HTMLElement>(
        `:scope > [data-row-key="${CSS.escape(bundle.key)}"]`,
      );
      const headRect = head?.getBoundingClientRect();
      const held =
        headRect !== undefined &&
        headRect.top > section.getBoundingClientRect().top + 0.5;
      for (const piece of pieceElements()) {
        const top =
          headRect === undefined
            ? 0
            : headRect.bottom - piece.getBoundingClientRect().top;
        piece.style.clipPath =
          cut && held && top > 0 ? `inset(${top}px -100vw -100vh -100vw)` : "";
      }
    };
    const schedule = () => {
      if (frame === null) frame = requestAnimationFrame(clip);
    };
    clip();
    // Threads coming and going move the pieces without a scroll.
    const resized = new ResizeObserver(schedule);
    resized.observe(section);
    window.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    return () => {
      if (frame !== null) cancelAnimationFrame(frame);
      resized.disconnect();
      window.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      for (const piece of pieceElements()) piece.style.clipPath = "";
    };
  }

  // DOM focus follows the list's, without scrolling: only the keyboard scrolls, by the list.
  $effect(() => {
    if (mail.focusKey !== bundle.key || !button) return;
    if (!isEditableTarget(document.activeElement)) {
      button.focus({ preventScroll: true });
    }
  });
</script>

{#snippet head()}
  <div
    data-row-key={bundle.key}
    {@attach expanded ? stuck : undefined}
    class={[
      "group/row flex items-center gap-3.5 h-[72px] px-5 transition-[background-color,box-shadow] duration-150",
      expanded
        ? "sticky top-[calc(4rem+env(safe-area-inset-top))] z-[5] rounded-t-[22px] bg-surface/80 backdrop-blur-xl backdrop-saturate-150 data-[stuck]:rounded-none data-[stuck]:bg-surface data-[stuck]:shadow-[0_6px_18px_-12px_rgb(0_0_0/0.35)]"
        : swipe.dx !== 0
          ? "relative bg-surface-raised shadow-raised"
          : "relative hover:bg-surface-hover",
      focused && "shadow-[inset_0_0_0_2px_var(--color-focus)]",
      !expanded && "rounded-[inherit]",
    ]}
  >
    <button
      bind:this={button}
      type="button"
      data-shortcuts
      class="absolute inset-0 w-full rounded-[inherit] outline-none"
      aria-label={`${expanded ? "Close" : "Open"} ${name}: ${summary}`}
      aria-expanded={expanded}
      onclick={() => mail.dispatch({ type: "tap", key: bundle.key })}
    ></button>
    {#if !expanded}
      <!-- The swipes and the round menu for screen readers, which can't swipe or hold. -->
      <div class="sr-only">
        <button type="button" tabindex="-1" onclick={doneAll}>Done all</button>
        <button
          type="button"
          tabindex="-1"
          onclick={() => mail.openSnoozeMenu(bundle.key)}
        >
          Snooze all
        </button>
        <button type="button" tabindex="-1" onclick={pinAll}>Pin all</button>
        <button type="button" tabindex="-1" onclick={deleteAll}>
          Delete all
        </button>
      </div>
    {/if}
    <span class="pointer-events-none"><LabelMark {label} size={36} /></span>
    <div class="flex-1 min-w-0 flex flex-col pointer-events-none">
      <div class="flex items-center gap-2 min-w-0">
        <span
          class={[
            "truncate text-[14.5px] text-text",
            unread > 0 ? "font-semibold" : "font-medium",
          ]}
          >{name}</span
        >
        <span
          class={[
            "shrink-0 text-xs tabular-nums",
            unread > 0 ? "font-semibold text-accent-strong" : "text-muted",
          ]}
        >
          {counts.unread ?? counts.threads}
        </span>
        <span class="flex-1"></span>
        {#if !expanded && rows.length > 0}
          <span
            class={[
              "text-xs tabular-nums",
              unread > 0 ? "font-semibold text-text" : "text-text-secondary",
            ]}
          >
            {formatListDate(newest, mail.now)}
          </span>
        {/if}
      </div>
      <!-- Open, its threads below already show what this line sums up. -->
      <div
        class={[
          "grid grid-cols-[minmax(0,1fr)] transition-[grid-template-rows,opacity] duration-200 ease-[cubic-bezier(0.33,1,0.68,1)] motion-reduce:transition-none",
          expanded && !shrinking
            ? "grid-rows-[0fr] opacity-0 starting:grid-rows-[1fr] starting:opacity-100"
            : "grid-rows-[1fr]",
        ]}
      >
        <div class="min-h-0 overflow-hidden">
          <div class="truncate pt-[3px] text-sm">
            <span class={unread > 0 ? "text-text" : "text-text-secondary"}
              >{senders}</span
            >
            {#if subjects}
              <span class="text-muted">&ensp;{subjects}</span>
            {/if}
          </div>
        </div>
      </div>
    </div>
    {#if expanded}
      <button
        type="button"
        onclick={() =>
          void mail.doneGroup(
            labelId,
            rows.map((row) => row.thread.id),
          )}
        class="relative h-8 shrink-0 flex items-center gap-1.5 px-3 rounded-full text-[13px] font-medium text-text-secondary hover:bg-surface-hover hover:text-text transition-colors"
      >
        <CheckCheck class="w-4 h-4" aria-hidden="true" />
        Done all
      </button>
      {#if label}
        <LabelSettingsButton {label} {name} />
      {/if}
    {/if}
    {#if snoozeOpen}
      <div class="absolute right-3 top-1/2">
        <SnoozePopover
          onPick={(epoch, source, key) =>
            void mail.snoozeGroup(labelId, threadIds, epoch, source, key)}
          onClose={mail.closeSnoozeMenu}
        />
      </div>
    {/if}
  </div>
{/snippet}

{#if expanded}
  <section
    data-bundle
    aria-label={`${name}: ${summary}`}
    class="relative"
    {@attach clipUnderHead}
  >
    {@render head()}
    {#each pieces as piece, pieceIndex (piece.key)}
      {#if piece.kind === "open"}
        <OpenThread
          row={piece.row.data}
          rowKey={piece.row.key}
          rowHeight={COMPACT_ROW_HEIGHT}
          {closing}
        />
      {:else if piece.key === "top" ||
        piece.rows.length > 0 ||
        (pieceIndex === pieces.length - 1 && footer !== null)}
        {@const last = pieceIndex === pieces.length - 1}
        {@const top = piece.key === "top"}
        <!-- The top piece reaches up behind the header, which sticks over it. -->
        <div
          data-bundle-piece
          class={[
            "rounded-[22px] bg-surface shadow-raised",
            top && "-mt-[72px] pt-[72px]",
          ]}
        >
          {#each piece.rows as item, position (item.key)}
            <div in:rowIn={item.key} out:rowOut={item.key}>
              <ThreadRow
                row={item.data}
                compact
                class={[
                  (top || position > 0) && "border-t border-separator",
                  !top && position === 0 && "rounded-t-[22px]",
                  position === piece.rows.length - 1 &&
                    !(last && footer !== null) &&
                    "rounded-b-[22px]",
                ]}
              />
            </div>
          {/each}
          {#if last && footer !== null}
            {@const footerClass = [
              "w-full h-12 flex items-center justify-center gap-1.5 rounded-b-[22px] text-[13px] font-semibold text-accent-strong hover:bg-surface-hover transition-colors",
              top || piece.rows.length > 0
                ? "border-t border-separator"
                : "rounded-t-[22px]",
            ]}
            {#if footer === "all"}
              <button
                type="button"
                out:grow
                onclick={showMore}
                class={footerClass}
              >
                Show {more?.hidden} more
              </button>
            {:else}
              <button
                type="button"
                disabled={footer === "loading"}
                onclick={showMore}
                class={[footerClass, "disabled:text-muted"]}
              >
                {#if footer === "loading"}
                  Loading…
                {:else if footer === "retry"}
                  <span class="font-normal text-text-secondary" role="alert"
                    >{mail.bundleError(labelId)}</span
                  >
                  Retry
                {:else}
                  Show more
                {/if}
              </button>
            {/if}
          {/if}
        </div>
      {/if}
    {/each}
    <div {@attach endWatch} aria-hidden="true"></div>
  </section>
{:else}
  <!-- svelte-ignore a11y_no_static_element_interactions -->
  <!-- biome-ignore lint/a11y/noStaticElementInteractions: touch swipes and long presses only; every action here is also a button -->
  <div
    onpointerdown={swipe.down}
    onpointermove={swipe.move}
    onpointerup={swipe.up}
    onpointercancel={swipe.cancel}
    oncontextmenu={swipe.contextMenu}
    onclickcapture={swipe.clickCapture}
    class={[
      "relative touch-pan-y pointer-coarse:select-none pointer-coarse:[-webkit-touch-callout:none]",
      swipe.dx !== 0 && "overflow-hidden",
      snoozeOpen && "z-20",
      className,
    ]}
  >
    <SwipeBackdrop
      dx={swipe.dx}
      width={swipe.width}
      start={swipe.shown.start && swipeLook[swipe.shown.start]}
      end={swipe.shown.end && swipeLook[swipe.shown.end]}
    />
    <div
      style:transform={swipe.dx !== 0 ? `translateX(${swipe.dx}px)` : null}
      ontransitionend={() => (swipe.settling = false)}
      class={[
        "relative rounded-[inherit]",
        swipe.settling && "transition-transform duration-200 ease-out",
      ]}
    >
      {@render head()}
    </div>
  </div>
{/if}
