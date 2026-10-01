package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** One page inside a tab group. [danger] turns its figure red, as the web's menu does for Damage, Missing and Rentals due. */
data class SyncTabItem(val to: String, val label: String, val count: Int = 0, val danger: Boolean = false)

/** A top-level tab; one with [items] opens a menu of pages (the web's `TabGroup`). */
data class SyncTabModel(val id: String, val label: String, val icon: ImageVector, val start: String, val items: List<SyncTabItem> = emptyList())

/**
 * The tool's tab strip as the web draws it: an icon and a label per tab, a
 * caret and a dropdown of pages (with live figures) on the groups, the lit tab
 * underlined in the accent colour. A group tab shows its pages' total.
 */
@Composable
fun SyncTabBar(
    tabs: List<SyncTabModel>,
    activeId: String?,
    activeItem: String,
    onGo: (String) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier.fillMaxWidth().background(colors.surface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab -> TabCell(tab, active = tab.id == activeId, activeItem = activeItem, onGo = onGo) }
        }
        trailing?.invoke(this)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun TabCell(tab: SyncTabModel, active: Boolean, activeItem: String, onGo: (String) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val total = tab.items.sumOf { it.count }
    val red = tab.items.any { it.danger && it.count > 0 }
    Box {
        Row(
            Modifier
                .clickable { if (tab.items.isEmpty()) onGo(tab.start) else open = true }
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            val ink = if (active) colors.textPrimary else colors.textSecondary
            ZillitIcon(tab.icon, tint = ink, size = ICON)
            ZillitText(
                tab.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                color = ink,
                maxLines = 1,
            )
            if (total > 0) {
                ZillitText(
                    total.toString(),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (red) colors.danger else colors.warning,
                )
            }
            if (tab.items.isNotEmpty()) ZillitIcon(ZillitIcons.ChevronDown, tint = ink, size = CARET)
        }
        if (active) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(colors.accent))
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = tab.items.map { item ->
                ZillitMenuEntry.Action(
                    label = item.label,
                    tone = when {
                        item.to == activeItem -> ZillitMenuTone.Primary
                        item.danger && item.count > 0 -> ZillitMenuTone.Danger
                        else -> ZillitMenuTone.Neutral
                    },
                    badge = item.count,
                    onClick = { onGo(item.to) },
                )
            },
        )
    }
}

private val ICON = 16.dp
private val CARET = 14.dp
