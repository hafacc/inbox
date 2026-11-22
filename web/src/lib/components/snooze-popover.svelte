<!-- @component The snooze menu floating by its button (a bottom sheet at phone width); 1–5 pick a suggestion, d opens the picker, r a todo's repeat rule, Esc closes. -->
<script lang="ts">
  import type { SnoozeSource } from "@inbox/shared/snooze-suggest";
  import type { Repeat, RepeatDraft } from "@inbox/shared/todos";
  import { getMailState } from "../mail-state.svelte";
  import MenuSheet from "./menu-sheet.svelte";
  import SnoozeMenu from "./snooze-menu.svelte";

  let {
    onPick,
    onClose,
    repeat = null,
    onRepeat,
  }: {
    onPick: (
      epochMillis: number,
      source: SnoozeSource,
      pickedKey?: string,
    ) => void;
    onClose: () => void;
    /** a todo's repeat rule */
    repeat?: Repeat | null;
    /** save a todo's repeat rule; without it the menu offers none */
    onRepeat?: (draft: RepeatDraft) => void;
  } = $props();

  const mail = getMailState();
  let highlight = $state(0);

  function pickHighlighted() {
    const rows = mail.snoozeRows;
    const last = mail.snoozeLastRow;
    if (highlight < rows.length) {
      const row = rows[highlight]!;
      onPick(row.time, "suggestion", row.keyId);
    } else if (last && highlight === rows.length) {
      onPick(last.time, "last");
    } else if (highlight === rows.length + (last ? 1 : 0)) {
      mail.snoozeMenuStep = "custom";
    } else {
      mail.snoozeMenuStep = "repeat";
    }
  }

  function onKey(event: KeyboardEvent) {
    if (event.key === "Escape") {
      if (mail.snoozeMenuStep !== "menu") {
        event.preventDefault();
        event.stopPropagation();
        mail.snoozeMenuStep = "menu";
      }
      return;
    }
    if (mail.snoozeMenuStep !== "menu") return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const choices =
      mail.snoozeRows.length +
      (mail.snoozeLastRow ? 1 : 0) +
      (onRepeat ? 2 : 1);
    const digit = Number.parseInt(event.key, 10);
    let handled = true;
    if (digit >= 1 && digit <= mail.snoozeRows.length) {
      const row = mail.snoozeRows[digit - 1]!;
      onPick(row.time, "suggestion", row.keyId);
    } else if (event.key === "d") {
      mail.snoozeMenuStep = "custom";
    } else if (event.key === "r" && onRepeat) {
      mail.snoozeMenuStep = "repeat";
    } else if (event.key === "ArrowDown" || event.key === "j") {
      highlight = (highlight + 1) % choices;
    } else if (event.key === "ArrowUp" || event.key === "k") {
      highlight = (highlight - 1 + choices) % choices;
    } else if (event.key === "Enter") {
      pickHighlighted();
    } else {
      handled = false;
    }
    // The page's own shortcuts must not see keys the menu used.
    if (handled) {
      event.preventDefault();
      event.stopPropagation();
    } else if (event.key.length === 1) {
      event.stopPropagation();
    }
  }
</script>

<MenuSheet
  label="Snooze until"
  role="menu"
  {onClose}
  {onKey}
  onkeydown={(event) => event.stopPropagation()}
  anchored={(above) => [
    "absolute z-30 right-0 rounded-[20px] bg-surface-raised shadow-pop animate-rise",
    // Sized by its content, not by the button it hangs from (its containing block).
    mail.snoozeMenuStep === "menu"
      ? "w-[296px]"
      : "w-max max-w-[calc(100vw-32px)]",
    above ? "bottom-full mb-1" : "top-full mt-1",
  ]}
>
  <SnoozeMenu
    {onPick}
    {repeat}
    {onRepeat}
    bind:step={mail.snoozeMenuStep}
    bind:highlight
  />
</MenuSheet>
