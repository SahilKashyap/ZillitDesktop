package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable

@Composable
actual fun NoticeContextMenu(
    items: () -> List<NoticeMenuItem>,
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = { items().map { item -> ContextMenuItem(item.label, item.onClick) } },
        content = content,
    )
}
