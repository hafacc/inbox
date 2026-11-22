package cc.hafa.inbox.core.todo

import java.time.ZoneId

// Ported from shared/src/todos/pages.ts; both run shared/testdata/todo-page-fixtures.json.

/** How many todos a list shows at first, and how many more each time its end scrolls into view. */
const val TODO_PAGE = 100

/** The most todos a list shows after [pages] pages: [TODO_PAGE] each. */
fun todoLimit(pages: Int): Int = maxOf(1, pages) * TODO_PAGE

/** The todos a list shows, and how many it leaves out until "show more". */
data class PagedTodos(
    /** the todos shown */
    val shown: List<Todo>,
    /** how many are left out */
    val hidden: Int,
)

/** The first [limit] of [todos] a list shows, and how many it leaves out until "show more". */
fun pageTodos(todos: List<Todo>, limit: Int): PagedTodos = PagedTodos(todos.take(limit), maxOf(0, todos.size - limit))

/** The first [limit] rows of [buckets], in order; a bucket left with none is dropped. */
fun limitBuckets(buckets: List<Bucket>, limit: Int): List<Bucket> {
    val shown = mutableListOf<Bucket>()
    var left = limit
    for (bucket in buckets) {
        if (left <= 0) break
        shown += if (bucket.todos.size <= left) bucket else bucket.copy(todos = bucket.todos.take(left))
        left -= bucket.todos.size
    }
    return shown
}

/**
 * The todos a paged list (Done, a search) shows among its threads: while older threads are still to
 * load ([more]), only those no older than the oldest loaded thread ([oldestShown], by [listDate]),
 * so each page of threads brings its own.
 */
fun todosLoaded(todos: List<Todo>, oldestShown: Long, more: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Todo> =
    if (more) todos.filter { it.listDate(zone) >= oldestShown } else todos
