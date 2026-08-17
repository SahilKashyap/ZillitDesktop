package com.zillit.desktop.feature.home.ui

import androidx.compose.runtime.Composable

/** One entry in a right-click menu. */
data class NoticeMenuItem(val label: String, val onClick: () -> Unit)

/**
 * Right-click opens a menu over [content]; an empty item list opens nothing.
 *
 * An expect wrapper because the platform menu (`ContextMenuArea`) is
 * desktop-only API — and it is the platform's own popup deliberately, native
 * hover and dismissal included, rather than a hand-drawn imitation.
 */
@Composable
expect fun NoticeContextMenu(
    items: () -> List<NoticeMenuItem>,
    content: @Composable () -> Unit,
)
