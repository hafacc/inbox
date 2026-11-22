package cc.hafa.inbox.data

import android.util.Log
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.RepeatDraft
import cc.hafa.inbox.core.todo.Tags
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.TodoState
import cc.hafa.inbox.core.todo.editEnded
import cc.hafa.inbox.core.todo.emailTodoToEdit
import cc.hafa.inbox.core.todo.emailTodosByThread
import cc.hafa.inbox.core.todo.heldInPlace
import cc.hafa.inbox.core.todo.undone
import cc.hafa.inbox.core.todo.withDeleted
import cc.hafa.inbox.core.todo.withDone
import cc.hafa.inbox.core.todo.withPinToggled
import cc.hafa.inbox.core.todo.withReactivated
import cc.hafa.inbox.core.todo.withRepeat
import cc.hafa.inbox.core.todo.withSnoozed
import cc.hafa.inbox.core.todo.withTyping
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

private const val TAG = "TodoStore"

// Typing is saved once it pauses this long, and at once when the editor closes.
private const val SAVE_DELAY_MS = 400L

// How long a todo opened from its reminder stays highlighted.
private const val HIGHLIGHT_MS = 2_000L

// Firestore's most writes in one batch.
private const val BATCH_LIMIT = 500

/** What undoing a todo action writes back: the todos as they were, and the copies it made. */
data class TodoUndo(
    /** the todos it changed, as they were */
    val before: List<Todo>,
    /** the next copies it made of repeating todos */
    val spawned: List<Todo> = emptyList(),
    /** the email todos it changed, as they were */
    val emailBefore: List<EmailTodo> = emptyList(),
)

/** The todos as [TodoStore] shows them. */
data class TodoSnapshot(
    /** whether todos show: the account's Show todos setting */
    val enabled: Boolean = false,
    /** whether Firestore has answered since todos were turned on */
    val loaded: Boolean = false,
    /** live todos of their own, tombstones left out, with typing not yet saved */
    val todos: List<Todo> = emptyList(),
    /** live todos on emails, tombstones left out, with typing not yet saved */
    val emailTodos: List<EmailTodo> = emptyList(),
    /** the todos as lists place them: the one being edited where it sat when editing began */
    val placed: List<Todo> = emptyList(),
    /** the todo whose text is being edited */
    val editingId: String? = null,
    /** the todo opened from its reminder, highlighted for a moment */
    val highlightId: String? = null,
) {
    /** Every tag in use, for completing one as it's typed. */
    val tagCounts: Map<String, Tags.Known> by lazy { Tags.count(todos.map { it.text } + emailTodos.map { it.text }) }

    /** Per Gmail thread id, its email's todo. */
    val byThread: Map<String, EmailTodo> by lazy { emailTodosByThread(emailTodos) }

    /** The live todo of its own with [id]. */
    fun get(id: String): Todo? = todos.firstOrNull { it.id == id }

    /** The live email todo with [id] (its thread's id). */
    fun getEmail(id: String): EmailTodo? = emailTodos.firstOrNull { it.id == id }

    /** The text of the live todo of either kind with [id]. */
    fun textOf(id: String): String? = get(id)?.text ?: getEmail(id)?.text
}

/**
 * latr's todos in this account (`users/{uid}/todos`), while the Show todos setting is on: the live
 * list, the edits latr makes, and which todo is being written; the same as web's TodoState. A doc
 * with a `threadId` is a todo on an email ([EmailTodo]): only its text is its own.
 *
 * Every change shows at once and is saved whole, stamped with the server's time; Firestore's own
 * listener then confirms it. An action that can be undone returns a [TodoUndo], which [restore]
 * takes back. [onSnoozesChanged] runs when a todo's snooze, or whether it is done, changes.
 * Main-thread only.
 */
class TodoStore(
    private val firestore: FirebaseFirestore,
    private val uid: String,
    private val scope: CoroutineScope,
    private val onSnoozesChanged: () -> Unit,
) {
    private val state = MutableStateFlow(TodoSnapshot())

    /** The todos and what is being edited. */
    val snapshot: StateFlow<TodoSnapshot> = state

    private var stored: List<Todo> = emptyList()
    private var storedEmail: List<EmailTodo> = emptyList()
    // Typed text not yet saved, by todo id; laid over each newer stored copy.
    private val unsaved = mutableMapOf<String, String>()
    private val timers = mutableMapOf<String, Job>()
    private var held: Todo? = null
    private var heldPrevious: Todo? = null
    // The todo being edited and its text as editing began, null for one new in this edit.
    private var editStart: Pair<String, String?>? = null

    /** Deletes, with undo, a saved todo whose edit ended with its text cleared out. */
    var onClearedOut: ((String) -> Unit)? = null
    private var snoozes: Set<Pair<String, String?>> = emptySet()
    // A new todo shown and being written, saved once it has text.
    private var fresh: Todo? = null
    private var freshEmail: EmailTodo? = null

    private fun collection() = firestore.collection("users").document(uid).collection("todos")

    private val listener = FirestoreListener<QuerySnapshot>(
        "todos",
        { collection().whereEqualTo("deleted", false).addSnapshotListener(it) },
    ) { snapshot ->
        val now = System.currentTimeMillis()
        val (onEmails, own) = snapshot?.documents.orEmpty().partition { EmailTodo.isDoc(it.data.orEmpty()) }
        stored = own.map { Todo.read(it.id, it.data.orEmpty(), now) }
        storedEmail = onEmails.map { EmailTodo.read(it.id, it.data.orEmpty(), now) }
        // Another device saved this email's todo while the new, still empty one was open: edit theirs.
        if (storedEmail.any { it.id == freshEmail?.id }) freshEmail = null
        show(loaded = true)
    }

    /** Follow the account's todos, or stop and forget them. */
    fun setEnabled(on: Boolean) {
        if (on == state.value.enabled) return
        if (on) {
            state.update { it.copy(enabled = true) }
            listener.start()
        } else {
            stop()
            stored = emptyList()
            storedEmail = emptyList()
            fresh = null
            freshEmail = null
            state.value = TodoSnapshot()
            held = null
            heldPrevious = null
            editStart = null
            noteSnoozes()
        }
    }

    /** Whether the todos shown are current (followed, and the listener answered and hasn't failed or stopped since). */
    val live: Boolean get() = listener.live

    /** Follow again after a listener error. */
    fun reattach() {
        if (state.value.enabled) listener.reattach()
    }

    /** Save any typing and stop listening. */
    fun stop() {
        flush()
        listener.stop()
    }

    private var highlightEnd: Job? = null

    /** Highlight the todo [id] for a moment, as when its reminder is tapped. */
    fun highlight(id: String) {
        state.update { it.copy(highlightId = id) }
        highlightEnd?.cancel()
        highlightEnd = scope.launch {
            delay(HIGHLIGHT_MS)
            state.update { it.copy(highlightId = null) }
        }
    }

    /**
     * Mark the todo [id] done from its reminder, read from Firestore when it isn't shown (the app
     * may have just started). False when it can't be read.
     */
    suspend fun doneFromReminder(id: String): Boolean {
        if (state.value.get(id) != null) {
            done(listOf(id))
            return true
        }
        val snapshot = runSuspendCatching { collection().document(id).get().await() }
            .onFailure { Log.w(TAG, "todo read failed", it) }.getOrNull() ?: return false
        val data = snapshot.data?.takeUnless(EmailTodo::isDoc) ?: return false
        val todo = Todo.read(id, data, System.currentTimeMillis())
        if (todo.deleted || todo.state == TodoState.DONE) return true
        val result = todo.withDone(System.currentTimeMillis(), newId())
        save(listOfNotNull(result.done, result.spawned), emptyList())
        return true
    }

    /** The todo [id], as the list has it or read from Firestore; null when it can't be read. */
    suspend fun read(id: String): Todo? = state.value.get(id) ?: runSuspendCatching { collection().document(id).get().await() }
        .onFailure { Log.w(TAG, "todo read failed", it) }.getOrNull()
        ?.data?.takeUnless(EmailTodo::isDoc)?.let { Todo.read(id, it, System.currentTimeMillis()) }

    /**
     * Edit the todo [id]'s text, or stop editing with null. The one open before ends its edit here,
     * the one place every way of leaving it comes to (focus leaving, Done on the keyboard, opening
     * something else): its text is saved and, when changed, its time stamped once ([editEnded]), so
     * the row moves only now; cleared out, it is deleted with undo ([onClearedOut]), and a new one
     * left empty goes. The app going to the background only saves typing ([flush]).
     */
    fun setEditing(id: String?) {
        val previous = state.value.editingId
        if (previous == id) return
        val start = editStart
        val isNew = id != null && (id == fresh?.id || id == freshEmail?.id)
        editStart = id?.let { it to if (isNew) null else state.value.textOf(it) }
        state.update { it.copy(editingId = id) }
        if (previous != null) endEdit(previous, start?.takeIf { it.first == previous }?.second)
        show()
    }

    private fun endEdit(id: String, startText: String?) {
        val text = state.value.textOf(id)
        when {
            fresh?.id == id -> fresh = null
            freshEmail?.id == id -> freshEmail = null
            // Deleted elsewhere since: the typing is dropped, not saved over its tombstone.
            text == null -> cancelSave(id)
            text.isBlank() && hasSavedText(id) -> {
                discard(id)
                onClearedOut?.invoke(id)
            }
            text.isBlank() -> erase(id)
            else -> saveEnded(id, startText)
        }
    }

    private fun saveEnded(id: String, startText: String?) {
        val now = System.currentTimeMillis()
        val own = state.value.get(id)
        val email = state.value.getEmail(id)
        if (own != null) {
            val ended = own.editEnded(startText, now)
            if (ended != own || id in unsaved) write(listOf(ended))
        } else if (email != null) {
            val ended = email.editEnded(startText, now)
            if (ended != email || id in unsaved) write(emptyList(), savedEmail = listOf(ended))
        }
    }

    private fun show(loaded: Boolean = state.value.loaded) {
        val now = System.currentTimeMillis()
        val shown = stored.withTyping(unsaved, now)
        val todos = fresh?.let { listOf(it) + shown } ?: shown
        val shownEmail = storedEmail.map { todo -> unsaved[todo.id]?.let(todo::withText) ?: todo }
        val emailTodos = freshEmail?.let { listOf(it) + shownEmail } ?: shownEmail
        val editingId = state.value.editingId
        val live = editingId?.let { id -> todos.firstOrNull { it.id == id } }
        val keep = held
        val previous = heldPrevious
        held = when {
            live == null -> null
            keep == null || previous == null || keep.id != live.id -> live
            live != previous -> heldInPlace(keep, previous, live)
            else -> keep
        }
        heldPrevious = live
        val holding = held
        val placed = if (holding == null) todos else todos.map { if (it.id == holding.id) holding else it }
        state.update { it.copy(loaded = loaded, todos = todos, emailTodos = emailTodos, placed = placed) }
        noteSnoozes()
    }

    // Reminders only care about todos not done and when each is snoozed to.
    private fun noteSnoozes() {
        val current = state.value.todos.filter { it.state != TodoState.DONE && it.snoozeUntil != null }
            .map { it.id to it.snoozeUntil }.toSet()
        if (current != snoozes) {
            snoozes = current
            onSnoozesChanged()
        }
    }

    /**
     * Rewrite every todo's tags naming the label [oldName] to name [newName] ([LabelTags.rename]),
     * typing not yet saved included. With todos off, or not loaded yet, they are read once from
     * Firestore's servers to do so (a device copy may be missing some), which throws offline.
     */
    suspend fun retag(oldName: String, newName: String) {
        fun renamed(text: String): String? = LabelTags.rename(text, oldName, newName).takeIf { it != text }
        fun retagged(todo: Todo): Todo? = renamed(todo.text)?.let { todo.copy(text = it) }
        fun retagged(todo: EmailTodo): EmailTodo? = renamed(todo.text)?.let { todo.copy(text = it) }
        if (state.value.enabled && state.value.loaded) {
            flush()
            val own = stored.mapNotNull(::retagged)
            val onEmails = storedEmail.mapNotNull(::retagged)
            if (own.isNotEmpty() || onEmails.isNotEmpty()) write(own, savedEmail = onEmails)
        } else {
            val now = System.currentTimeMillis()
            val found = collection().whereEqualTo("deleted", false).get(Source.SERVER).await()
            val (onEmails, own) = found.documents.partition { EmailTodo.isDoc(it.data.orEmpty()) }
            val ownRetagged = own.map { Todo.read(it.id, it.data.orEmpty(), now) }.mapNotNull(::retagged)
            val emailRetagged = onEmails.map { EmailTodo.read(it.id, it.data.orEmpty(), now) }.mapNotNull(::retagged)
            if (ownRetagged.isNotEmpty() || emailRetagged.isNotEmpty()) save(ownRetagged, emptyList(), emailRetagged)
        }
    }

    // Shown at once; the listener confirms it once Firestore has it.
    private fun write(saved: List<Todo>, erased: List<String> = emptyList(), savedEmail: List<EmailTodo> = emptyList()) {
        for (todo in saved) cancelSave(todo.id)
        for (todo in savedEmail) cancelSave(todo.id)
        for (id in erased) cancelSave(id)
        val gone = erased.toSet()
        stored = merged(stored, saved, gone, Todo::id).filter { !it.deleted }
        storedEmail = merged(storedEmail, savedEmail, gone, EmailTodo::id).filter { !it.deleted }
        show()
        save(saved, erased, savedEmail)
    }

    // [current] with [saved] laid over it, new ones first, and the [gone] ids left out.
    private fun <Item> merged(current: List<Item>, saved: List<Item>, gone: Set<String>, idOf: (Item) -> String): List<Item> {
        val changed = saved.associateBy(idOf)
        val currentIds = current.mapTo(hashSetOf(), idOf)
        val kept = current.filter { idOf(it) !in gone }.map { changed[idOf(it)] ?: it }
        return saved.filter { idOf(it) !in currentIds } + kept
    }

    // A batch at a time; Firestore queues them while offline. An email todo's write is its whole doc.
    private fun save(saved: List<Todo>, erased: List<String>, savedEmail: List<EmailTodo> = emptyList()) {
        val writes = saved.map { it.id to it.fields() } + savedEmail.map { it.id to it.fields() } + erased.map { it to null }
        for (chunk in writes.chunked(BATCH_LIMIT)) {
            val batch = firestore.batch()
            for ((id, fields) in chunk) {
                val ref = collection().document(id)
                if (fields == null) {
                    batch.delete(ref)
                } else {
                    batch.set(ref, fields + ("serverModifiedAt" to FieldValue.serverTimestamp()))
                }
            }
            batch.commit().addOnFailureListener { Log.w(TAG, "todo write failed", it) }
        }
    }

    private fun cancelSave(id: String) {
        timers.remove(id)?.cancel()
        unsaved.remove(id)
    }

    // The saved todos with these ids; a new one not saved yet has nothing to act on.
    private fun found(ids: List<String>): List<Todo> = ids.mapNotNull(state.value::get).filter { it.id != fresh?.id }

    // The saved email todos with these ids.
    private fun foundEmail(ids: List<String>): List<EmailTodo> = ids.mapNotNull(state.value::getEmail).filter { it.id != freshEmail?.id }

    private fun newId(): String = UUID.randomUUID().toString()

    /** Save typing that hasn't been saved yet: one todo's, or everyone's. Blank text is never saved. */
    fun flush(id: String? = null) {
        val typed = (if (id == null) unsaved.keys.toList() else listOf(id)).filter { !unsaved[it].isNullOrBlank() }
        val saved = found(typed)
        val savedEmail = foundEmail(typed)
        // Typing into a todo deleted elsewhere since is dropped, not saved over its tombstone.
        typed.forEach(::cancelSave)
        if (saved.isNotEmpty() || savedEmail.isNotEmpty()) write(saved, savedEmail = savedEmail)
    }

    // Drop the todo's typing not yet saved, showing its stored text again.
    private fun discard(id: String) {
        if (id !in unsaved) return
        cancelSave(id)
        show()
    }

    // Whether the todo's stored copy (typing not yet saved left out) has any text.
    private fun hasSavedText(id: String): Boolean =
        (stored.firstOrNull { it.id == id }?.text ?: storedEmail.firstOrNull { it.id == id }?.text)?.isNotBlank() == true

    /**
     * Show a new empty todo and open it to edit: at [position] (else the top of Active), [pinned]
     * when asked. It is saved once it has text, and gone if left empty. Returns its id.
     */
    fun start(position: String? = null, pinned: Boolean = false, id: String = newId()): String {
        flush()
        setEditing(null)
        val todo = Todo.new(id, "", System.currentTimeMillis()).copy(position = position, pinned = pinned)
        fresh = todo
        setEditing(todo.id)
        return todo.id
    }

    /**
     * Open the todo of an email (its row's Gmail [threadIds], lead first) to edit: the one it has, as
     * an email has one, else a new, empty one, saved once it has text and gone if left empty.
     * Returns its id.
     */
    fun startEmail(threadIds: List<String>): String {
        flush()
        setEditing(null)
        val (todo, isNew) = emailTodoToEdit(emailTodosByThread(storedEmail), threadIds, System.currentTimeMillis())
        if (isNew) freshEmail = todo
        setEditing(todo.id)
        return todo.id
    }

    /** New text for a todo, saved once typing pauses; a new todo is saved as soon as it has text. */
    fun edit(id: String, text: String) {
        val new = fresh
        val newEmail = freshEmail
        if (new?.id == id) {
            if (text.isBlank()) {
                fresh = new.copy(text = text)
                show()
            } else {
                fresh = null
                write(listOf(new.copy(text = text)))
            }
            return
        }
        if (newEmail?.id == id) {
            if (text.isBlank()) {
                freshEmail = newEmail.copy(text = text)
                show()
            } else {
                freshEmail = null
                write(emptyList(), savedEmail = listOf(newEmail.copy(text = text)))
            }
            return
        }
        val existing = state.value.textOf(id)
        if (existing == null || existing == text) return
        unsaved[id] = text
        show()
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            delay(SAVE_DELAY_MS)
            timers.remove(id)
            flush(id)
        }
    }

    /** Mark todos done (those not done yet); a repeating one makes its next copy, snoozed to its next time. */
    fun done(ids: List<String>): TodoUndo? {
        val before = found(ids).filter { it.state != TodoState.DONE }
        if (before.isEmpty()) return null
        val now = System.currentTimeMillis()
        val results = before.map { it.withDone(now, newId()) }
        val spawned = results.mapNotNull { it.spawned }
        write(results.map { it.done } + spawned)
        return TodoUndo(before, spawned)
    }

    private fun change(ids: List<String>, transform: (Todo, Long) -> Todo): TodoUndo? {
        val before = found(ids)
        if (before.isEmpty()) return null
        val now = System.currentTimeMillis()
        write(before.map { transform(it, now) })
        return TodoUndo(before)
    }

    /** Make a done or snoozed todo active again; unsnoozing a repeating one ends its repeat. */
    fun reactivate(ids: List<String>): TodoUndo? = change(ids) { todo, now -> todo.withReactivated(now) }

    /** Snooze todos until [until] (epoch milliseconds). */
    fun snooze(ids: List<String>, until: Long): TodoUndo? = change(ids) { todo, now -> todo.withSnoozed(until, now) }

    /** Pin or unpin a todo. */
    fun togglePin(id: String): TodoUndo? = change(listOf(id)) { todo, now -> todo.withPinToggled(now) }

    /** Pin or unpin todos, each that isn't so already. */
    fun setPinned(ids: List<String>, pinned: Boolean): TodoUndo? =
        change(found(ids).filter { it.pinned != pinned }.map { it.id }) { todo, now -> todo.withPinToggled(now) }

    /** Flip the pin of each of several todos. */
    fun togglePins(ids: List<String>): TodoUndo? = change(ids) { todo, now -> todo.withPinToggled(now) }

    /** Delete todos of either kind, leaving tombstones. */
    fun remove(ids: List<String>): TodoUndo? {
        val before = found(ids)
        val emailBefore = foundEmail(ids)
        if (before.isEmpty() && emailBefore.isEmpty()) return null
        val now = System.currentTimeMillis()
        write(before.map { it.withDeleted(now) }, savedEmail = emailBefore.map { it.withDeleted(now) })
        return TodoUndo(before, emailBefore = emailBefore)
    }

    /** Give a todo the repeat rule [draft]. */
    fun setRepeat(id: String, draft: RepeatDraft) {
        state.value.get(id)?.let { write(listOf(it.withRepeat(draft, System.currentTimeMillis()))) }
    }

    // Delete an abandoned empty todo outright; a new one never saved just goes.
    private fun erase(id: String) {
        if (fresh?.id == id) {
            fresh = null
            show()
        } else if (freshEmail?.id == id) {
            freshEmail = null
            show()
        } else if (state.value.textOf(id) != null) {
            write(emptyList(), listOf(id))
        }
    }

    /** Take an action back: the todos as they were, keeping text typed since, and the copies it made deleted. */
    fun restore(undo: TodoUndo) {
        val now = System.currentTimeMillis()
        val live = state.value
        write(
            undo.before.map { it.undone(live.get(it.id), now) } + undo.spawned.map { it.withDeleted(now) },
            savedEmail = undo.emailBefore.map { before -> live.getEmail(before.id)?.let { before.withText(it.text) } ?: before },
        )
    }
}
