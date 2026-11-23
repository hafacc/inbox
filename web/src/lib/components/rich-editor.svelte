<!-- @component The composer's text field: rich text that markdown typed into it turns into (Tiptap), held as this app's markdown. A long touch or right click on a link (or the menu key with the caret in one) opens the round menu with Open, Edit and Remove; a tap or click still places the caret. -->
<script lang="ts">
  import {
    markdownToRich,
    richToHtml,
    richToMarkdown,
  } from "@inbox/shared/compose";
  import { rowHoldWedges } from "@inbox/shared/hold-menu";
  import { safeHref } from "@inbox/shared/web-links";
  import ExternalLink from "@lucide/svelte/icons/external-link";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Unlink from "@lucide/svelte/icons/unlink";
  import { Editor } from "@tiptap/core";
  import { onDestroy, onMount } from "svelte";
  import type { FormatCommand } from "../compose/markdown-edit";
  import {
    docToRich,
    type LinkRequest,
    linkAround,
    linkRequest,
    removeLink,
    richExtensions,
    richToDoc,
    saveLink,
  } from "../compose/rich-doc";
  import { HoldPress } from "../hold-menu";
  import { type HoldMenuItem, holdMenu } from "../hold-menu.svelte";
  import LinkDialog from "./link-dialog.svelte";

  let {
    value = $bindable(""),
    rendered = $bindable(null),
    readonly = false,
    label,
    placeholder = "",
    grow = false,
    class: className = "",
    oninput,
    onkeydown,
    onpaste,
  }: {
    /** the text as this app's markdown, the message's plain-text part */
    value?: string;
    /** the text as the message's HTML, null until it is edited here */
    rendered?: string | null;
    /** shown but not editable */
    readonly?: boolean;
    /** accessible name */
    label: string;
    /** text shown while empty */
    placeholder?: string;
    /** grow with the text up to a cap and scroll past it, rather than fill the space given */
    grow?: boolean;
    /** classes for the wrapper */
    class?: string;
    /** called after each edit the user makes */
    oninput?: () => void;
    /** called before the editor handles a key; prevent its default to take the key over */
    onkeydown?: (event: KeyboardEvent) => void;
    /** called before the editor pastes; prevent its default to take the paste over */
    onpaste?: (event: ClipboardEvent) => void;
  } = $props();

  let host: HTMLDivElement;
  let editor: Editor | undefined;
  // The markdown this editor last handed out, to tell its own changes from the composer's.
  let written: string | null = null;
  // A touch on a link that may become a hold, opening the link's menu.
  const linkPress = new HoldPress();
  // The link menu is open (or opening) from a touch, so the browser's own menu stays away.
  let linkHeld = false;
  // The link the dialog is for, while it is open.
  let editingLink = $state<LinkRequest | null>(null);

  onDestroy(() => linkPress.cancel());

  // Once a touch opened the link's menu, the finger slides to a wedge rather than scrolling.
  function holdTouch(event: TouchEvent) {
    if (linkHeld && event.cancelable) event.preventDefault();
  }

  // The document position of a link element in the field, or null.
  function linkElementPos(target: EventTarget | null): number | null {
    const element = (target as Element | null)?.closest?.("a[href]");
    if (!element || !host.contains(element) || !editor) return null;
    try {
      return editor.view.posAtDOM(element, 0) + 1;
    } catch {
      return null;
    }
  }

  function openLinkMenu(
    pos: number,
    center: { x: number; y: number },
    pointerId: number | null,
    focusFirst: boolean,
  ) {
    if (!editor) return false;
    const link = linkAround(editor, pos);
    if (link === null) return false;
    const labels = {
      open: { label: "Open", icon: ExternalLink },
      edit: { label: "Edit", icon: Pencil },
      remove: { label: "Remove", icon: Unlink },
    } as const;
    const items: HoldMenuItem[] = rowHoldWedges(
      ["open", "edit", "remove"] as const,
      center.x,
      center.y,
      window.innerWidth,
    ).map((wedge) => ({ ...wedge, ...labels[wedge.id] }));
    holdMenu.open({
      x: center.x,
      y: center.y,
      items,
      pill: link.href.replace(/^https?:\/\//, ""),
      label: "Link actions",
      pointerId,
      press: center,
      focusFirst,
      onPick: (action) => {
        if (action === "open") {
          const href = safeHref(link.href);
          if (href !== null) window.open(href, "_blank", "noopener,noreferrer");
        } else if (action === "edit") {
          editingLink = { mode: "edit", ...link };
        } else if (editor) {
          removeLink(editor, link);
        }
      },
      onClose: () => {
        linkHeld = false;
      },
    });
    return true;
  }

  function onSaveLink(text: string, href: string) {
    const request = editingLink;
    editingLink = null;
    if (request !== null && editor) saveLink(editor, request, text, href);
  }

  function onPointerDown(event: PointerEvent) {
    if (event.pointerType === "mouse" || readonly) return;
    const pos = linkElementPos(event.target);
    if (pos === null) return;
    const { pointerId, clientX: x, clientY: y } = event;
    linkPress.start(pointerId, x, y, () => {
      linkHeld = openLinkMenu(pos, { x, y }, pointerId, false);
    });
  }

  function onContextMenu(event: MouseEvent) {
    if (readonly) return;
    if (linkHeld || linkPress.pending) {
      // A long touch is opening the link's menu, not asking for the browser's.
      event.preventDefault();
      return;
    }
    // The menu key sends no position: it opens on the link the caret is in.
    const fromKeyboard = event.clientX === 0 && event.clientY === 0;
    const pos = fromKeyboard
      ? (editor?.state.selection.from ?? null)
      : linkElementPos(event.target);
    if (pos === null || !editor) return;
    const coords = editor.view.coordsAtPos(pos);
    const center = fromKeyboard
      ? { x: coords.left, y: (coords.top + coords.bottom) / 2 }
      : { x: event.clientX, y: event.clientY };
    if (openLinkMenu(pos, center, null, fromKeyboard)) event.preventDefault();
  }

  /**
   * Toggle bold or italic on the selection, as ⌘B and ⌘I do, or open the link dialog, as ⌘K does:
   * to edit the link the caret is in, link the selected text, or add a link at the caret.
   */
  export function format(command: FormatCommand): void {
    if (command === "bold") editor?.chain().focus().toggleBold().run();
    else if (command === "italic") editor?.chain().focus().toggleItalic().run();
    else linkCommand();
  }

  function linkCommand() {
    if (editor && !readonly) editingLink = linkRequest(editor);
  }

  /** Insert lines of markdown where the caret is. */
  export function insert(markdown: string): void {
    if (editor === undefined) {
      value = `${value}${markdown}`;
      return;
    }
    const doc = richToDoc(markdownToRich(markdown, false));
    editor
      .chain()
      .focus()
      .insertContent(doc.content ?? [])
      .run();
  }

  /** Focus the field, with the caret at its start when `atStart`. */
  export function focus(atStart = false): void {
    editor?.commands.focus(atStart ? "start" : undefined);
  }

  /** Whether the node is inside the field. */
  export function contains(node: Node | null): boolean {
    return node !== null && host.contains(node);
  }

  onMount(() => {
    host.addEventListener("touchmove", holdTouch, { passive: false });
    written = value;
    editor = new Editor({
      element: host,
      extensions: richExtensions(placeholder),
      content: richToDoc(markdownToRich(value, false)),
      editable: !readonly,
      editorProps: {
        attributes: {
          "aria-label": label,
          "aria-multiline": "true",
          role: "textbox",
          spellcheck: "true",
          autocapitalize: "sentences",
          class: "tiptap flex-1 px-4 pt-3.5 pb-2",
        },
        handleKeyDown(_view, event) {
          onkeydown?.(event);
          return event.defaultPrevented;
        },
        handlePaste(_view, event) {
          onpaste?.(event);
          return event.defaultPrevented;
        },
        handleDrop(_view, event) {
          // Files go to the composer, which attaches them; the editor would paste their bytes.
          return (event.dataTransfer?.files.length ?? 0) > 0;
        },
      },
      onUpdate: ({ editor: changed }) => {
        const blocks = docToRich(changed.getJSON());
        written = richToMarkdown(blocks);
        value = written;
        rendered = richToHtml(blocks);
        oninput?.();
      },
    });
    return () => {
      host.removeEventListener("touchmove", holdTouch);
      editor?.destroy();
      editor = undefined;
    };
  });

  $effect(() => {
    const next = value;
    if (editor === undefined || next === written) return;
    written = next;
    rendered = null;
    editor.commands.setContent(richToDoc(markdownToRich(next, false)), {
      emitUpdate: false,
    });
  });

  $effect(() => {
    editor?.setEditable(!readonly, false);
  });
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<!-- biome-ignore lint/a11y/noStaticElementInteractions: the editor inside is the textbox; this only watches presses on its links -->
<div
  bind:this={host}
  onpointerdown={onPointerDown}
  onpointermove={(event) =>
    linkPress.move(event.pointerId, event.clientX, event.clientY)}
  onpointerup={() => linkPress.cancel()}
  onpointercancel={() => linkPress.cancel()}
  oncontextmenu={onContextMenu}
  class={[
    "rich-editor flex flex-col text-sm leading-[22px] text-text caret-accent [overflow-wrap:anywhere]",
    grow && "min-h-0 overflow-auto",
    className,
  ]}
></div>
{#if editingLink !== null}
  <LinkDialog
    text={editingLink.text}
    href={editingLink.href}
    asksText={editingLink.mode !== "selection"}
    title={editingLink.mode === "edit" ? "Edit link" : "Add link"}
    onCancel={() => {
      editingLink = null;
      editor?.commands.focus();
    }}
    onSave={onSaveLink}
  />
{/if}
