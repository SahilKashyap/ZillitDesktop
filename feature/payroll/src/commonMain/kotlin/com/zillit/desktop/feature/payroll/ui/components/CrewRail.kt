package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The producer surfaces' crew rail — the web's sidebar: one paper card holding
 * the week's crew, grouped by department, each group headed and each row
 * carrying an avatar, a name, a designation and the week's status.
 *
 * [enabled] is off while a fill is computing on Production Report Payroll:
 * that board prices against one engine at a time, so selecting another crew
 * member mid-fill would finish the remaining days on the wrong agreement's.
 */
@Composable
internal fun CrewRail(
    rows: List<RailRow>,
    selectedKey: String?,
    loading: Boolean,
    emptyTitle: String,
    emptyMessage: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) {
        when {
            loading -> repeat(RAIL_SKELETONS) { RailSkeletonRow() }
            rows.isEmpty() -> ZillitEmptyState(title = emptyTitle, message = emptyMessage, icon = ZillitIcons.Users)
            else -> ZillitLazyColumn(Modifier.fillMaxWidth().fillMaxHeight()) {
                // Grouped in the order the rows arrive: the caller has already
                // sorted them by department and then by name.
                rows.groupBy { it.department }.forEach { (department, members) ->
                    item(key = "dept:$department") { DepartmentHeader(department) }
                    items(members, key = { "row:${it.key}" }) { row ->
                        RailEntry(row, row.key == selectedKey, enabled) { onSelect(row.key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartmentHeader(department: String) {
    ZillitText(
        text = department.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        maxLines = 1,
    )
}

@Composable
private fun RailEntry(row: RailRow, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (selected) colors.surfaceSelected else colors.surface)
                .clickable(enabled = enabled, onClick = onSelect)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The selected row wears an accent edge, as the web's does.
            Column(
                Modifier.width(SELECTION_EDGE).fillMaxHeight()
                    .background(if (selected) colors.accent else colors.surface),
            ) {}
            ZillitAvatar(name = row.name, userId = row.userId, size = RAIL_AVATAR)
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = row.name,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) colors.accentText else colors.textPrimary,
                    maxLines = 1,
                )
                ZillitText(
                    text = row.designation.ifBlank { DASH },
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitStatusPill(label = row.statusLabel, tone = row.statusTone)
        }
        ZillitDivider()
    }
}

@Composable
private fun RailSkeletonRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSkeletonBar(Modifier.width(RAIL_AVATAR), height = RAIL_AVATAR)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_NAME))
            ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_ROLE))
        }
    }
}

/**
 * The hero above a selected crew member's week — avatar, name, designation and
 * department, the week, the status, and whatever actions the surface offers.
 */
@Composable
internal fun CrewHero(
    name: String,
    userId: String,
    designation: String,
    department: String,
    weekLabel: String,
    statusLabel: String,
    statusTone: StatusTone,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitAvatar(name = name, userId = userId, size = HERO_AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = name,
                style = ZillitTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            ZillitText(
                text = listOf(designation, department).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { DASH },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitText(
            text = weekLabel,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = colors.textSecondary,
            modifier = Modifier.clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            maxLines = 1,
        )
        ZillitStatusPill(label = statusLabel, tone = statusTone)
        actions()
    }
}

private const val DASH = "—"
private const val RAIL_SKELETONS = 6
private const val SKELETON_NAME = 0.6f
private const val SKELETON_ROLE = 0.4f
private val RAIL_AVATAR: Dp = 32.dp
private val HERO_AVATAR: Dp = 48.dp
private val SELECTION_EDGE: Dp = 3.dp

/** The rail's width — the web's `w-[280px]`. */
internal val RAIL_WIDTH: Dp = 280.dp
