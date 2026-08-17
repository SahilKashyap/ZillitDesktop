package com.zillit.desktop.core.datastore

/**
 * Saved window size and position.
 *
 * Extracted from `main.kt` so it can be tested. It previously lived inside a
 * private `@Composable` that took a Compose `WindowState`, which meant the
 * save/restore round trip — including the "no saved position" sentinel — could
 * only be exercised by launching the app and closing it by hand.
 *
 * A null [x] or [y] means "no saved position", and the caller should let the
 * platform place the window. Represented as null rather than a magic -1 at this
 * level; the sentinel exists only in storage, where the preference type is Int.
 */
data class WindowGeometry(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
) {
    val hasPosition: Boolean get() = x != null && y != null

    init {
        require(width > 0 && height > 0) { "window size must be positive, got ${width}x$height" }
    }
}

/**
 * Persists window geometry.
 *
 * Position is only written when present: overwriting a good saved position with
 * the sentinel would lose it, and a window that forgets where it was on every
 * restart is a small daily annoyance.
 */
suspend fun PreferenceStore.saveWindowGeometry(geometry: WindowGeometry) {
    set(ZillitPreferences.WindowWidth, geometry.width)
    set(ZillitPreferences.WindowHeight, geometry.height)
    if (!geometry.hasPosition) return
    set(ZillitPreferences.WindowX, requireNotNull(geometry.x))
    set(ZillitPreferences.WindowY, requireNotNull(geometry.y))
}

/**
 * Reads back saved geometry, falling back to the declared defaults.
 *
 * A partially-saved position (one axis set, the other not) is treated as no
 * position at all — placing a window at a half-known coordinate can put it
 * somewhere unreachable, for instance off the edge of a smaller display than
 * the one it was last used on.
 */
suspend fun PreferenceStore.loadWindowGeometry(): WindowGeometry {
    val x = get(ZillitPreferences.WindowX)
    val y = get(ZillitPreferences.WindowY)
    val hasPosition = x != ZillitPreferences.UNSET_POSITION && y != ZillitPreferences.UNSET_POSITION

    return WindowGeometry(
        width = get(ZillitPreferences.WindowWidth),
        height = get(ZillitPreferences.WindowHeight),
        x = x.takeIf { hasPosition },
        y = y.takeIf { hasPosition },
    )
}
