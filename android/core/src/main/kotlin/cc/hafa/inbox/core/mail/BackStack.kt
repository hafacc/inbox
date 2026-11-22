package cc.hafa.inbox.core.mail

/**
 * A layer over a view that Back closes before it leaves the view: [MENU] is the side menu, a
 * menu, a sheet or one of its steps, or a dialog; [TODO] a todo being edited. As
 * `shared/src/back-stack.ts`.
 */
enum class BackLayer(
    /** name, as in the fixtures */
    val key: String,
) {
    /** the side menu, a menu, a sheet or one of its steps, or a dialog */
    MENU("menu"),

    /** the open thread */
    THREAD("thread"),

    /** a todo being edited */
    TODO("todo"),

    /** selected rows */
    SELECTION("selection"),

    /** the open bundle */
    BUNDLE("bundle"),

    /** search */
    SEARCH("search"),
}

/** What Back goes back through. */
data class BackStack(
    /** view keys in the order shown, the current one last */
    val views: List<String>,
    /** the open layers in the order they opened, the top one last */
    val layers: List<BackLayer> = emptyList(),
)

/** Something that changes what Back goes back through. */
sealed interface BackEvent {
    /** A layer opened; one already open keeps its place (another bundle opening in its stead). */
    data class Open(
        /** the layer */
        val layer: BackLayer,
    ) : BackEvent

    /** A layer closed by other means than Back (its X, an action that ends it). */
    data class Close(
        /** the layer */
        val layer: BackLayer,
    ) : BackEvent

    /** The app shows another view, by key; the app closes the layers the move ends itself. */
    data class ShowView(
        /** the view's key */
        val view: String,
    ) : BackEvent

    /** The user pressed Back. */
    data object Back : BackEvent
}

/** What Back does: close a layer, show the view before, or leave the app. */
sealed interface BackEffect {
    /** Close the layer on top. */
    data class Close(
        /** the layer */
        val layer: BackLayer,
    ) : BackEffect

    /** Show the view before. */
    data class ShowView(
        /** the view's key */
        val view: String,
    ) : BackEffect

    /** Leave the app. */
    data object Leave : BackEffect
}

/** How the views move from one to the next; see [viewStep]. */
enum class ViewStep {
    /** nothing changes */
    NONE,

    /** back to the view before */
    BACK,

    /** the current view is rewritten */
    REPLACE,

    /** a new view on top */
    PUSH,
}

// Views of this kind replace one another rather than stacking: searches.
private const val REPLACING_KIND = "search"

private fun kindOf(view: String): String = view.substringBefore(':')

/** What Back goes back through when the app starts on [view]. */
fun startBack(view: String): BackStack = BackStack(listOf(view))

/**
 * How the views follow a move from [current] to [next]: back to the view before ([previous]) when
 * it is that one, a rewrite of the current one from one search to another, else a new one on top
 * (settings pages stack like any other view).
 */
fun viewStep(previous: String?, current: String, next: String): ViewStep = when {
    current == next -> ViewStep.NONE
    previous == next -> ViewStep.BACK
    kindOf(current) == REPLACING_KIND && kindOf(next) == REPLACING_KIND -> ViewStep.REPLACE
    else -> ViewStep.PUSH
}

/** The views after a move to [next] (see [viewStep]). */
fun viewsAfter(views: List<String>, next: String): List<String> {
    val current = views.lastOrNull() ?: return listOf(next)
    return when (viewStep(views.getOrNull(views.size - 2), current, next)) {
        ViewStep.NONE -> views
        ViewStep.BACK -> views.dropLast(1)
        ViewStep.REPLACE -> views.dropLast(1) + next
        ViewStep.PUSH -> views + next
    }
}

/**
 * Back as both apps follow it: it closes the layer opened last, then returns to the view shown
 * before, and at the first view with nothing open it leaves the app. Returns the stack after
 * [event], and for Back what it does (null for any other event).
 */
fun backStep(stack: BackStack, event: BackEvent): Pair<BackStack, BackEffect?> = when (event) {
    is BackEvent.Open ->
        if (event.layer in stack.layers) stack to null else stack.copy(layers = stack.layers + event.layer) to null
    is BackEvent.Close -> stack.copy(layers = stack.layers - event.layer) to null
    is BackEvent.ShowView -> stack.copy(views = viewsAfter(stack.views, event.view)) to null
    BackEvent.Back -> {
        val top = stack.layers.lastOrNull()
        val before = stack.views.getOrNull(stack.views.size - 2)
        when {
            top != null -> stack.copy(layers = stack.layers.dropLast(1)) to BackEffect.Close(top)
            before != null -> stack.copy(views = stack.views.dropLast(1)) to BackEffect.ShowView(before)
            else -> stack to BackEffect.Leave
        }
    }
}
