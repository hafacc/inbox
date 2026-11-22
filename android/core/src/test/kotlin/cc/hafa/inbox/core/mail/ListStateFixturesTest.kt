package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private typealias State = ListState<Unit, Unit>

/** Runs `shared/testdata/list-state-fixtures.json` and the randomized event sequences, as `shared/src/list-state.spec.ts` does on web. */
class ListStateFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    private fun layoutThread(value: Any?): LayoutThread<Unit> {
        val thread = value as Map<*, *>
        return LayoutThread(thread["id"] as String, Unit)
    }

    private fun entryOf(value: Any?): LayoutEntry<Unit, Unit> {
        val entry = value as Map<*, *>
        return when (entry["kind"]) {
            "thread" -> LayoutEntry.Thread(layoutThread(entry))
            "todo" -> LayoutEntry.Todo(entry["id"] as String, Unit)
            else -> LayoutEntry.Bundle(
                entry["labelId"] as String,
                (entry["threads"] as List<*>).map(::layoutThread),
                entry["more"] as Boolean,
                entry["loading"] as Boolean,
            )
        }
    }

    private fun sectionOf(value: Any?): LayoutSection<Unit, Unit> {
        val section = value as Map<*, *>
        return LayoutSection(
            section["key"] as String,
            section["title"] as String,
            section["doneAll"] as Boolean,
            (section["entries"] as List<*>).map(::entryOf),
        )
    }

    private fun source(value: Any?): FocusSource = FocusSource.valueOf((value as String).uppercase())

    @Suppress("CyclomaticComplexMethod")
    private fun eventOf(raw: Map<*, *>): ListEvent<Unit, Unit> = when (val type = raw["type"]) {
        "layout" -> ListEvent.Layout((raw["sections"] as List<*>).map(::sectionOf))
        "next" -> ListEvent.Next
        "previous" -> ListEvent.Previous
        "enter" -> ListEvent.Enter
        "back" -> ListEvent.Back
        "dismiss" -> ListEvent.Dismiss
        "openMenu" -> ListEvent.OpenMenu(raw["id"] as String)
        "closeMenu" -> ListEvent.CloseMenu(raw["id"] as String)
        "select" -> ListEvent.Select
        "command" -> ListEvent.Command(raw["action"] as String)
        "focus" -> ListEvent.Focus(raw["key"] as String, source(raw["by"]))
        "tap" -> ListEvent.Tap(raw["key"] as String)
        "reveal" -> ListEvent.Reveal(raw["key"] as String)
        "openThread" -> ListEvent.OpenThread(raw["id"] as String)
        "closeThread" -> ListEvent.CloseThread
        "closeBundle" -> ListEvent.CloseBundle
        "showAll" -> ListEvent.ShowAll
        "pick" -> ListEvent.Pick(raw["key"] as String, raw["range"] as Boolean)
        "pickAll" -> ListEvent.PickAll
        "clearSelection" -> ListEvent.ClearSelection
        "act" -> ListEvent.Act(
            strings(raw["removes"]),
            ActSource.valueOf((raw["from"] as String).uppercase()),
            raw["undoable"] as Boolean,
            raw["clearSelection"] as Boolean,
        )
        "undo" -> ListEvent.Undo
        "undoExpired" -> ListEvent.UndoExpired
        "restore" -> ListEvent.Restore(strings(raw["keys"]))
        "anchor" -> ListEvent.Anchor(raw["key"] as String, raw["offset"] as Int)
        "reset" -> ListEvent.Reset
        else -> error("unknown event $type")
    }

    private fun effectOf(value: Any?): ListEffect? {
        val effect = value as Map<*, *>? ?: return null
        return when (effect["kind"]) {
            "loadMore" -> ListEffect.LoadMore(effect["labelId"] as String)
            "edit" -> ListEffect.Edit(effect["todoId"] as String)
            else -> {
                val target = effect["target"] as Map<*, *>
                ListEffect.Act(
                    effect["action"] as String,
                    ListTarget(
                        TargetSource.valueOf((target["from"] as String).uppercase()),
                        strings(target["threads"]),
                        strings(target["todos"]),
                        target["bundle"] as String?,
                    ),
                )
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun check(name: String, before: State, after: State, want: Map<*, *>) {
        if ("rows" in want) assertEquals("$name rows", strings(want["rows"]), after.rows.map { it.key })
        if ("focus" in want) assertEquals("$name focus", want["focus"], after.focus)
        if ("open" in want) assertEquals("$name open", want["open"], after.open)
        if ("bundle" in want) assertEquals("$name bundle", want["bundle"], after.bundle?.labelId)
        if ("menu" in want) assertEquals("$name menu", want["menu"], after.menu)
        if ("awaiting" in want) assertEquals("$name awaiting", want["awaiting"], after.bundle?.awaiting)
        if ("showAll" in want) assertEquals("$name showAll", want["showAll"], after.bundle?.showAll)
        if ("pendingFocus" in want) assertEquals("$name pendingFocus", want["pendingFocus"], after.pendingFocus)
        if ("selection" in want) assertEquals("$name selection", strings(want["selection"]), after.selection)
        if ("removing" in want) assertEquals("$name removing", strings(want["removing"]), after.removing.sorted())
        if ("anchor" in want) {
            val anchor = want["anchor"] as Map<*, *>?
            assertEquals("$name anchor", anchor?.let { ListAnchor(it["key"] as String, it["offset"] as Int) }, after.anchor)
        }
        if ("effect" in want) assertEquals("$name effect", effectOf(want["effect"]), after.effect)
        if ("revealed" in want) assertEquals("$name revealed", want["revealed"], after.reveal > before.reveal)
        if ("undo" in want) assertEquals("$name undo", want["undo"], after.undo != null)
        if ("neighbors" in want) {
            val neighbors = want["neighbors"] as Map<*, *>
            assertEquals("$name neighbors", OpenNeighbors(neighbors["previous"] as String?, neighbors["next"] as String?), openNeighbors(after))
        }
        if ("tail" in want) {
            val tail = want["tail"] as Map<*, *>?
            assertEquals("$name tail", tail?.let { OpenBundleTail(it["labelId"] as String, it["after"] as Int) }, openBundleTail(after))
        }
    }

    @Test
    fun fixtures() {
        val cases = Fixtures.load("list-state-fixtures.json")["cases"] as List<*>
        for (case in cases) {
            val fixture = case as Map<*, *>
            var state: State = ListState()
            (fixture["steps"] as List<*>).forEachIndexed { index, rawStep ->
                val stepCase = rawStep as Map<*, *>
                val name = "${fixture["name"]}, step $index"
                val next = listStep(state, eventOf(stepCase["event"] as Map<*, *>))
                assertEquals(name, emptyList<String>(), listProblems(next))
                check(name, state, next, stepCase["expect"] as Map<*, *>)
                state = next
            }
        }
    }

    @Test
    fun randomSequences() {
        for (seed in 1..SEEDS) {
            val random = Mulberry(seed)
            var state: State = listStep(ListState(), ListEvent.Layout(randomLayout(random)))
            repeat(STEPS) { count ->
                val event = randomEvent(state, random)
                val after = listStep(state, event)
                val problems = listProblems(after) + stepProblems(state, event, after)
                if (problems.isNotEmpty()) {
                    fail("seed $seed, step $count, $event: ${problems.joinToString("; ")}\nbefore ${summary(state)}\nafter ${summary(after)}")
                }
                state = after
            }
        }
    }

    private companion object {
        const val SEEDS = 400
        const val STEPS = 120
    }
}

// The same small deterministic generator as the web spec (mulberry32), so a failing sequence replays by its seed.
private class Mulberry(seed: Int) {
    private var value = seed

    fun next(): Double {
        value += 0x6d2b79f5
        var mixed = value
        mixed = (mixed xor (mixed ushr 15)) * (mixed or 1)
        mixed = mixed xor (mixed + (mixed xor (mixed ushr 7)) * (mixed or 61))
        return ((mixed xor (mixed ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
    }

    fun <Item> pick(items: List<Item>): Item = items[(next() * items.size).toInt()]
}

private val randomThreads = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")
private val randomTodos = listOf("t1", "t2", "t3")
private val menus = listOf("labels", "snooze")
private val randomLabels = listOf("L", "M")

private fun randomLayout(random: Mulberry): List<LayoutSection<Unit, Unit>> {
    val sections = mutableListOf<LayoutSection<Unit, Unit>>()
    for (key in listOf("pinned", "today", "earlier")) {
        if (random.next() < 0.2) continue
        val entries = mutableListOf<LayoutEntry<Unit, Unit>>()
        repeat((random.next() * 6).toInt()) {
            val roll = random.next()
            entries += when {
                roll < 0.55 -> LayoutEntry.Thread(LayoutThread(random.pick(randomThreads), Unit))
                roll < 0.7 -> LayoutEntry.Todo(random.pick(randomTodos), Unit)
                else -> {
                    val labelId = random.pick(randomLabels)
                    val size = (random.next() * 8).toInt()
                    val threads = List(size) { LayoutThread(random.pick(randomThreads), Unit) }
                    val more = random.next() < 0.4
                    LayoutEntry.Bundle(labelId, threads, more, random.next() < 0.2)
                }
            }
        }
        sections += LayoutSection(key, key, key != "pinned", entries)
    }
    return sections
}

@Suppress("CyclomaticComplexMethod", "MagicNumber")
private fun randomEvent(state: State, random: Mulberry): ListEvent<Unit, Unit> {
    val keys = state.rows.map { it.key }
    fun anyKey() = if (keys.isNotEmpty()) random.pick(keys) else "a"
    return when ((random.next() * 24).toInt()) {
        0, 1 -> ListEvent.Layout(randomLayout(random))
        2 -> ListEvent.Layout(state.layout)
        3, 4 -> ListEvent.Next
        5 -> ListEvent.Previous
        6 -> ListEvent.Enter
        7 -> ListEvent.Back
        8 -> ListEvent.Select
        9 -> ListEvent.Command("done")
        10 -> {
            val key = anyKey()
            ListEvent.Focus(key, if (random.next() < 0.5) FocusSource.KEYBOARD else FocusSource.POINTER)
        }
        11 -> ListEvent.Tap(anyKey())
        12 -> ListEvent.OpenThread(random.pick(randomThreads))
        13 -> if (random.next() < 0.5) ListEvent.CloseThread else ListEvent.CloseBundle
        14 -> ListEvent.ShowAll
        15 -> {
            val key = anyKey()
            ListEvent.Pick(key, random.next() < 0.3)
        }
        16 -> if (random.next() < 0.5) ListEvent.PickAll else ListEvent.ClearSelection
        17, 18 -> {
            val removes = if (state.selection.isNotEmpty() && random.next() < 0.4) state.selection else listOf(state.open ?: state.focus ?: anyKey())
            val from = if (state.selection.isNotEmpty()) ActSource.SELECTION else ActSource.ROW
            val undoable = random.next() < 0.8
            ListEvent.Act(removes, from, undoable, random.next() < 0.5)
        }
        19 -> if (random.next() < 0.8) ListEvent.Undo else ListEvent.UndoExpired
        20 -> {
            val key = anyKey()
            ListEvent.Anchor(key, (random.next() * 300).toInt())
        }
        21 -> if (random.next() < 0.5) ListEvent.Dismiss else ListEvent.Back
        22 -> if (random.next() < 0.6) ListEvent.OpenMenu(random.pick(menus)) else ListEvent.CloseMenu(random.pick(menus))
        else -> if (random.next() < 0.9) ListEvent.Restore(listOf(anyKey())) else ListEvent.Reset
    }
}

// The rules of design.md, "The list", that compare a step's state with the one before it.
@Suppress("CyclomaticComplexMethod", "LongMethod", "ComplexCondition")
private fun stepProblems(before: State, event: ListEvent<Unit, Unit>, after: State): List<String> {
    val problems = mutableListOf<String>()
    val nowFocusable = after.focusableKeys().toSet()
    val wasFocusable = before.focusableKeys()
    if (event is ListEvent.Layout) {
        val landed = before.pendingFocus != null && after.pendingFocus == null
        val paged = before.bundle?.awaiting != null && after.bundle?.awaiting == null
        val closed = before.open != null && after.open == null
        if (before.focus != null && before.focus in nowFocusable && after.focus != before.focus && !landed && !paged && !closed) {
            problems += "a refetch moved focus off a row that is still there"
        }
        val anchor = before.anchor
        if (anchor != null && after.rows.any { it.key == anchor.key } && after.anchor?.key != anchor.key) {
            problems += "a refetch moved the scroll anchor"
        }
    }
    // A focused row that left hands focus to the next surviving row, else the previous one
    // (unless it waits on the open bundle's header for the bundle's next page).
    val waits = before.bundle?.awaiting == null && after.bundle?.awaiting != null
    val focus = before.focus
    if (
        focus != null &&
        focus !in nowFocusable &&
        after.pendingFocus == before.pendingFocus &&
        !waits &&
        event != ListEvent.Back &&
        event != ListEvent.Reset &&
        !(event == ListEvent.Undo && before.undo != null)
    ) {
        val index = wasFocusable.indexOf(focus)
        val following = wasFocusable.drop(index + 1).firstOrNull { it in nowFocusable }
        val preceding = wasFocusable.take(maxOf(index, 0)).lastOrNull { it in nowFocusable }
        val movedItself = event == ListEvent.Next ||
            event == ListEvent.Previous ||
            event is ListEvent.Focus ||
            event is ListEvent.Tap ||
            event == ListEvent.Enter ||
            event is ListEvent.OpenThread ||
            event == ListEvent.CloseBundle ||
            event == ListEvent.Dismiss
        // Inside an open bundle that stays open, focus stays inside it: its next row, else its previous.
        val label = before.bundle?.labelId
        fun insideOf(state: State, key: String?) = state.rows.any { it.key == key && it is ListRow.Thread && it.bundle == label }
        val stays = label != null && after.bundle?.labelId == label && insideOf(before, focus) && insideOf(after, after.focus)
        val expected = if (movedItself || (stays && !insideOf(after, following))) after.focus else following ?: preceding
        if (after.focus != expected) problems += "focus went to ${after.focus}, not the next row $expected"
    }
    // The keyboard leaving an open bundle closes it.
    fun inBundle(state: State, labelId: String): Boolean = when (val row = state.rowOf(state.focus)) {
        is ListRow.Bundle -> row.labelId == labelId
        is ListRow.Thread -> row.bundle == labelId
        else -> false
    }
    val keyboard = event == ListEvent.Next || event == ListEvent.Previous || (event is ListEvent.Focus && event.by == FocusSource.KEYBOARD)
    val bundle = before.bundle
    if (keyboard && bundle != null && inBundle(before, bundle.labelId) && after.bundle?.labelId == bundle.labelId && !inBundle(after, bundle.labelId)) {
        problems += "keyboard focus left an open bundle that stayed open"
    }
    // Escape, a click away or a tap closes only the top layer: the menu, else the thread, else the bundle.
    val closer = event == ListEvent.Dismiss || event == ListEvent.Back || (event is ListEvent.Tap && before.rows.any { it.key == event.key })
    if (closer && before.menu != null) {
        if (after.menu != null || after.open != before.open || after.bundle?.labelId != before.bundle?.labelId) {
            problems += "$event with a menu open closed more than the menu"
        }
    } else if ((event == ListEvent.Dismiss || event == ListEvent.Back) && before.open != null && after.bundle?.labelId != before.bundle?.labelId) {
        problems += "$event with a thread open closed its bundle too"
    }
    // Undo brings focus back once its rows are listed.
    val undo = before.undo
    if (event == ListEvent.Undo && undo != null && undo.keys.isNotEmpty() && after.pendingFocus == null && after.focus == null) {
        problems += "undo left nothing to focus"
    }
    return problems
}

private fun summary(state: State): String =
    "rows=${state.rows.map { it.key }} focus=${state.focus} open=${state.open} " +
        "bundle=${state.bundle?.labelId}/${state.bundle?.awaiting} pending=${state.pendingFocus}"
