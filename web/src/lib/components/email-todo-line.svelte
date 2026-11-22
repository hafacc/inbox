<!-- @component An email's todo as one line: its text, which screen readers hear as a todo. Under the snippet of the email's row it has no actions of its own, as the row's are the email's, and a click falls through to the row. At the top of the open email (`editable`) its text opens to edit on a click; cleared out, the todo is deleted with undo, and a new one never saved just goes. -->
<script lang="ts">
  import { type EmailTodo, suggestTags } from "@inbox/shared/todos";
  import { onDestroy, untrack } from "svelte";
  import type { ClassValue } from "svelte/elements";
  import { getMailState } from "../mail-state.svelte";
  import type { TodoEditorHandle } from "../todos/todo-editor";
  import StyledText from "./styled-text.svelte";
  import TodoEditor from "./todo-editor.svelte";

  let {
    todo,
    editable = false,
    class: className = "",
  }: {
    /** the todo shown */
    todo: EmailTodo;
    /** whether its text opens to edit: the open email's, not a row's */
    editable?: boolean;
    /** classes added to the line */
    class?: ClassValue;
  } = $props();

  const mail = getMailState();
  const editing = $derived(editable && mail.todos.editingId === todo.id);
  // While the editor has focus its text is kept here, so an update can't move the caret.
  let draft = $state("");
  let editorFocused = $state(false);
  let editor = $state<TodoEditorHandle | null>(null);
  const text = $derived(editing && editorFocused ? draft : todo.text);

  $effect(() => {
    const handle = editor;
    if (editing && handle) untrack(() => handle.focus("end"));
  });

  // The open email closing ends its todo's edit; a tap on Back may never blur the editor (iOS).
  onDestroy(() => {
    if (editing) mail.todos.setEditing(null);
  });

  // Leaving the editor ends the edit, as `setEditing` does for every way out. The window losing
  // focus (another tab or app) only blurs it: the edit stays open, its typing saved as the page hides.
  function finishEditing() {
    if (!document.hasFocus()) return;
    editorFocused = false;
    if (mail.todos.editingId === todo.id) mail.todos.setEditing(null);
  }
</script>

<div
  data-email-todo={todo.id}
  class={[
    "flex min-w-0 text-[13px]",
    editable ? "items-baseline" : "items-center",
    className,
  ]}
>
  <span class="sr-only">Todo:</span>
  {#if editing}
    <TodoEditor
      bind:handle={editor}
      value={text}
      onChange={(value) => {
        draft = value;
        mail.todos.edit(todo.id, value);
      }}
      onFocus={() => {
        draft = todo.text;
        editorFocused = true;
      }}
      onBlur={finishEditing}
      onEnter={(view) => view.contentDOM.blur()}
      tagSuggestions={(prefix) =>
        suggestTags(
          mail.todos.tagCounts,
          todo.text,
          prefix,
          undefined,
          mail.labelTagNames,
        )}
      tagColor={mail.tagColor}
      onTagOffer={(offer) => (mail.todos.tagOffer = offer)}
      multiline
      label="Todo"
      class="flex-1 min-w-0 leading-5 text-text"
    />
  {:else if editable}
    <button
      type="button"
      aria-label="Edit todo"
      onclick={() => mail.todos.setEditing(todo.id)}
      class="todo-text flex-1 min-w-0 text-left whitespace-pre-wrap break-words leading-5 text-text cursor-text"
    >
      <StyledText {text} />
    </button>
  {:else}
    <span class="todo-text flex-1 min-w-0 truncate text-text">
      <StyledText {text} />
    </span>
  {/if}
</div>
