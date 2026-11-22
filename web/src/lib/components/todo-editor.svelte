<!-- @component A todo's text field, as latr's: styles and tags marked in place, ⌘B/⌘I, known tags offered for the `#tag` at the caret (a dropdown; at phone width a row above the keyboard, through `onTagOffer`), Enter to finish and Shift+Enter for a new line. -->
<script lang="ts">
  import {
    history,
    historyKeymap,
    insertNewline,
    standardKeymap,
  } from "@codemirror/commands";
  import {
    Annotation,
    Compartment,
    EditorSelection,
    EditorState,
    Prec,
    Transaction,
  } from "@codemirror/state";
  import {
    EditorView,
    keymap,
    placeholder as placeholderText,
  } from "@codemirror/view";
  import { typingTag } from "@inbox/shared/todos";
  import { onMount } from "svelte";
  import {
    completeTagAt,
    inlineStyles,
    recolorTags,
    singleLine,
    type TagOffer,
    type TodoEditorHandle,
    tagCompletion,
    todoEditorTheme,
    toggleMarker,
  } from "../todos/todo-editor";
  import { phoneWidth } from "./phone";

  let {
    handle = $bindable(null),
    value,
    onChange,
    onFocus,
    onBlur,
    onEnter,
    multiline = false,
    placeholder,
    tagSuggestions,
    tagColor = () => null,
    onTagOffer,
    label,
    enterKeyHint = "done",
    class: className = "",
  }: {
    /** set once the editor exists, so its owner can focus it */
    handle?: TodoEditorHandle | null;
    /** the text, markers included */
    value: string;
    /** called after each edit the user makes */
    onChange: (value: string) => void;
    /** called when it takes focus */
    onFocus?: () => void;
    /** called when it loses focus */
    onBlur?: () => void;
    /** plain Enter */
    onEnter: (view: EditorView) => void;
    /** whether Shift+Enter starts a new line */
    multiline?: boolean;
    /** text shown while empty */
    placeholder?: string;
    /** known tags starting with the name typed after a `#` */
    tagSuggestions: (prefix: string) => string[];
    /** a tag's color (its name without `#`) as CSS, or null for the usual look */
    tagColor?: (name: string) => string | null;
    /** at phone width, the tags to offer above the keyboard, or null for none */
    onTagOffer?: (offer: TagOffer | null) => void;
    /** accessible name */
    label: string;
    /** what the on-screen keyboard's Enter key shows: "next" while Enter starts another todo */
    enterKeyHint?: "done" | "next";
    /** classes for the wrapper */
    class?: string;
  } = $props();

  const fromOutside = Annotation.define<boolean>();
  const hint = new Compartment();
  let host: HTMLDivElement;
  let view: EditorView | null = null;
  let offered = false;

  // At phone width the tags go to a row above the keyboard instead of the dropdown.
  function offerTags(target: EditorView): void {
    if (!onTagOffer) return;
    const { head, empty } = target.state.selection.main;
    const typed =
      phoneWidth.current && target.hasFocus && empty
        ? typingTag(target.state.doc.toString(), head)
        : null;
    const names = typed ? tagSuggestions(typed.prefix) : [];
    if (names.length > 0) {
      offered = true;
      onTagOffer({ names, pick: (name) => completeTagAt(target, name) });
    } else if (offered) {
      offered = false;
      onTagOffer(null);
    }
  }

  // `tagColor` changes when the labels do (they may load after the editor opens); the tags follow.
  $effect(() => {
    const colors = tagColor;
    view?.dispatch({ effects: recolorTags((name) => colors(name)) });
  });

  // The view is made once; later changes of `value` come through the effect below.
  onMount(() => {
    const created = new EditorView({
      parent: host,
      state: EditorState.create({
        doc: value,
        extensions: [
          history(),
          // Before the keymap below, so Enter and Escape reach an open dropdown first.
          tagCompletion(
            (prefix) =>
              phoneWidth.current && onTagOffer ? [] : tagSuggestions(prefix),
            (name) => tagColor(name),
          ),
          Prec.highest(
            keymap.of([
              {
                key: "Enter",
                run: (target) => {
                  onEnter(target);
                  return true;
                },
              },
              {
                key: "Escape",
                run: (target) => {
                  target.contentDOM.blur();
                  return true;
                },
              },
              {
                key: "Shift-Enter",
                run: (target) => (multiline ? insertNewline(target) : true),
              },
              { key: "Mod-b", run: toggleMarker("*") },
              { key: "Mod-i", run: toggleMarker("_") },
            ]),
          ),
          keymap.of([...historyKeymap, ...standardKeymap]),
          EditorView.lineWrapping,
          inlineStyles((name) => tagColor(name)),
          todoEditorTheme,
          multiline ? [] : singleLine,
          placeholder ? placeholderText(placeholder) : [],
          EditorView.contentAttributes.of({
            "aria-label": label,
            "aria-multiline": multiline ? "true" : "false",
            spellcheck: "true",
            autocapitalize: "sentences",
          }),
          hint.of(
            EditorView.contentAttributes.of({ enterkeyhint: enterKeyHint }),
          ),
          EditorView.updateListener.of((update) => {
            if (
              update.docChanged &&
              !update.transactions.some((transaction) =>
                transaction.annotation(fromOutside),
              )
            ) {
              onChange(update.state.doc.toString());
            }
            if (update.focusChanged) {
              if (update.view.hasFocus) onFocus?.();
              else onBlur?.();
            }
            if (
              update.docChanged ||
              update.selectionSet ||
              update.focusChanged
            ) {
              offerTags(update.view);
            }
          }),
        ],
      }),
    });
    view = created;
    handle = {
      focus: (caret) => {
        created.focus();
        if (caret === undefined) return;
        const pos =
          caret === "end"
            ? created.state.doc.length
            : Math.min(caret, created.state.doc.length);
        created.dispatch({ selection: EditorSelection.cursor(pos) });
      },
      blur: () => created.contentDOM.blur(),
      posAtCoords: (x, y) => created.posAtCoords({ x, y }, false),
      select: (anchor, head) =>
        created.dispatch({ selection: EditorSelection.range(anchor, head) }),
      focusAtEdge: (x, edge) => {
        created.focus();
        const box = created.contentDOM.getBoundingClientRect();
        const lineMiddle = created.defaultLineHeight / 2;
        const y =
          edge === "first" ? box.top + lineMiddle : box.bottom - lineMiddle;
        const pos =
          created.posAtCoords({ x, y }, false) ??
          (edge === "first" ? 0 : created.state.doc.length);
        // A bare range would be read as {anchor, head}, dropping the column.
        created.dispatch({
          selection: EditorSelection.create([
            EditorSelection.cursor(pos, 0, undefined, x - box.left),
          ]),
        });
      },
    };
    return () => {
      view = null;
      handle = null;
      if (offered) onTagOffer?.(null);
      created.destroy();
    };
  });

  $effect(() => {
    const shown = enterKeyHint;
    // A changed hint restarts the on-screen keyboard's input, so only a real change is applied.
    if (view?.contentDOM.getAttribute("enterkeyhint") === shown) return;
    view?.dispatch({
      effects: hint.reconfigure(
        EditorView.contentAttributes.of({ enterkeyhint: shown }),
      ),
    });
  });

  $effect(() => {
    const next = value;
    if (!view || view.state.doc.toString() === next) return;
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: next },
      annotations: [fromOutside.of(true), Transaction.addToHistory.of(false)],
    });
  });
</script>

<div bind:this={host} class={["todo-text", className]}></div>
