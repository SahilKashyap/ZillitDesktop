package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where a Costumes & Set Sync screen is: a path under the tool, as the web
 * writes them (`costumes`, `costumes/<id>`, `scenes/<id>`, `characters/<id>/scenes/<sceneId>`)
 * plus its query (`costumes?status=DAMAGED&new=1`).
 *
 * The web navigates with relative React-Router paths; here every path is
 * relative to the tool's root, so a link reads the same on both and a screen
 * never climbs with `../`.
 */
data class SyncRoute(val path: String, val query: Map<String, String> = emptyMap()) {
    /** The path's segments: `costumes/abc` → `["costumes", "abc"]`. */
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }

    val head: String get() = segments.firstOrNull().orEmpty()

    fun arg(name: String): String = query[name].orEmpty()

    companion object {
        /** `costumes?status=X` → route. Values are not percent-decoded; callers pass plain ids and enums. */
        fun parse(to: String): SyncRoute {
            val path = to.substringBefore('?').trim('/')
            val query = to.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return SyncRoute(path, query)
        }
    }
}

/** The tool's own back stack. [go] pushes, [replace] swaps the top (a filter change), [back] pops. */
@Stable
class SyncNav(start: SyncRoute = SyncRoute("breakdown")) {
    private val stack = mutableStateListOf(start)

    val current: SyncRoute get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    /** A guard a screen with unsaved input can set: returns false to hold the navigation. */
    var leaveGuard: (() -> Boolean)? by mutableStateOf(null)

    fun go(to: String) = go(SyncRoute.parse(to))

    fun go(route: SyncRoute) {
        if (leaveGuard?.invoke() == false) return
        if (route != current) stack.add(route)
    }

    fun replace(route: SyncRoute) {
        stack[stack.lastIndex] = route
    }

    /** Sets or clears one query value in place, as the web's `setSearchParams(…, {replace: true})`. */
    fun setQuery(name: String, value: String?) {
        val next = current.query.toMutableMap()
        if (value.isNullOrEmpty()) next.remove(name) else next[name] = value
        replace(current.copy(query = next))
    }

    fun back() {
        if (leaveGuard?.invoke() == false) return
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Back, or the dashboard when there is nowhere to go back to. */
    fun backOr(fallback: String) = if (canGoBack) back() else go(fallback)
}
