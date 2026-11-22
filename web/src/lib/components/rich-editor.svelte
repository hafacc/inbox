<!-- @component The composer's text field: rich text that markdown typed into it turns into (Tiptap), held as this app's markdown. -->
<script lang="ts">
  import {
    markdownToRich,
    richToHtml,
    richToMarkdown,
  } from "@inbox/shared/compose";
  import { Editor } from "@tiptap/core";
  import { onMount } from "svelte";
  import type { FormatCommand } from "../compose/markdown-edit";
  import { docToRich, richExtensions, richToDoc } from "../compose/rich-doc";

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

  /** Toggle bold or italic on the selection, as ⌘B and ⌘I do. */
  export function format(command: FormatCommand): void {
    if (command === "bold") editor?.chain().focus().toggleBold().run();
    else editor?.chain().focus().toggleItalic().run();
  }

  /** Insert lines of markdown where the caret is. */
  export function insert(markdown: string): void {
    if (editor === undefined) {
      value = `${value}${markdown}`;
      return;
    }
    const doc = richToDoc(markdownToRich(markdown));
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
    written = value;
    editor = new Editor({
      element: host,
      extensions: richExtensions(placeholder),
      content: richToDoc(markdownToRich(value)),
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
      editor?.destroy();
      editor = undefined;
    };
  });

  $effect(() => {
    const next = value;
    if (editor === undefined || next === written) return;
    written = next;
    rendered = null;
    editor.commands.setContent(richToDoc(markdownToRich(next)), {
      emitUpdate: false,
    });
  });

  $effect(() => {
    editor?.setEditable(!readonly, false);
  });
</script>

<div
  bind:this={host}
  class={[
    "rich-editor flex flex-col text-sm leading-[22px] text-text caret-accent [overflow-wrap:anywhere]",
    grow && "min-h-0 overflow-auto",
    className,
  ]}
></div>
