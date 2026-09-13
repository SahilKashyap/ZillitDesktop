// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.AccessPerson
import com.zillit.desktop.feature.callsheet.ui.PermissionEvent
import com.zillit.desktop.feature.callsheet.ui.PermissionState
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.InfoBanner
import com.zillit.desktop.feature.callsheet.ui.components.SheetCheckbox
import com.zillit.desktop.feature.callsheet.ui.components.SheetEmptyState
import com.zillit.desktop.feature.callsheet.ui.components.SheetErrorLine
import com.zillit.desktop.feature.callsheet.ui.components.SheetIconButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.SheetTable
import com.zillit.desktop.feature.callsheet.ui.components.TableColumn
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.pageCount
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * Permission — who else may create call sheets (`PermissionTab.jsx`): the
 * crew axis of the permission grid, one "Enable" per person. Granting gives
 * view, posting and download on the call sheet together.
 */
@Composable
internal fun PermissionPage(state: SheetUiState, onEvent: (SheetEvent) -> Unit) {
    val permission = state.permission
    Column {
        InfoBanner(
            Labels.current.exact("callsheet_permission_hint") ?: "To allow users to create a call sheet.",
            ZillitIcons.Info,
            Modifier.padding(bottom = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetInput(
                value = permission.search,
                onChange = { onEvent(PermissionEvent.Search(it)) },
                placeholder = Labels.current.exact("Search") ?: "Search",
                leadingIcon = ZillitIcons.Search,
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
            )
        }
        permission.error?.let { error ->
            Box(Modifier.padding(bottom = 12.dp)) { SheetErrorLine(error) { onEvent(PermissionEvent.Retry) } }
        }
        val visible = permission.visible
        when {
            permission.loading && !permission.loaded -> LoadingBlock("Loading...")
            visible.isEmpty() -> SheetEmptyState(Labels.current.exact("no_data_found") ?: "No data found")
            else -> {
                PermissionTable(state, visible, onEvent)
                if (permission.search.isBlank()) Pager(permission, onEvent)
            }
        }
    }
}

@Composable
private fun PermissionTable(state: SheetUiState, people: List<AccessPerson>, onEvent: (SheetEvent) -> Unit) {
    val unitTitle = people.firstNotNullOfOrNull { it.unitName.ifBlank { null } }?.localised() ?: "Call Sheet"
    SheetTable(
        columns = listOf(
            TableColumn("User", weight = 1.4f),
            TableColumn("Department", weight = 1f),
            TableColumn(unitTitle, width = 180.dp),
        ),
        rows = people,
        minWidth = 720.dp,
    ) { person, column, _ ->
        when (column) {
            0 -> PersonBlock(person)
            1 -> Text(
                person.department.localised().ifBlank { "-" },
                style = sheetText(12.sp),
                color = SheetTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> EnableCell(state, person, onEvent)
        }
    }
}

@Composable
private fun PersonBlock(person: AccessPerson) {
    val colors = SheetTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Face(person.userId, person.fullName, 44.dp)
        Column(Modifier.padding(end = 8.dp)) {
            Text(
                person.fullName.ifBlank { "-" } + if (person.isAdmin) " - Admin" else "",
                style = sheetText(14.sp, FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 2,
            )
            val designation = person.designation.localised()
            if (designation.isNotBlank()) {
                Text(designation, style = sheetText(12.sp), color = colors.textTertiary, maxLines = 1)
            }
        }
    }
}

/** "Enable" — on for posting access; disabled for admins, without grid posting, or while the write runs. */
@Composable
private fun EnableCell(state: SheetUiState, person: AccessPerson, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val processing = person.userId in state.permission.processing
    val enabled = state.viewer.canEditGrid && !person.isAdmin && !processing
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) colors.hover else Color.Transparent)
            .hoverable(source)
            .plainClick(enabled = enabled, source = source) { onEvent(PermissionEvent.Toggle(person, !person.canPost)) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetCheckbox(person.canPost, enabled = enabled, size = 16.dp)
        Text(
            Labels.current.exact("enable_label") ?: "Enable",
            style = sheetText(12.sp),
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        )
    }
}

/** antd's pager, bottom-left: pages, a page-size choice and "1-20 of 57". */
@Composable
private fun Pager(permission: PermissionState, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val pages = pageCount(permission.total, permission.pageSize)
    val first = if (permission.total == 0) 0 else permission.page * permission.pageSize + 1
    val last = minOf(permission.total, (permission.page + 1) * permission.pageSize)
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetIconButton(
            ZillitIcons.ChevronLeft,
            "Previous page",
            { onEvent(PermissionEvent.Page(permission.page - 1)) },
            enabled = permission.page > 0,
        )
        pageWindow(permission.page, pages).forEach { index ->
            if (index < 0) {
                Text("…", style = sheetText(12.sp), color = colors.textMuted, modifier = Modifier.width(20.dp))
            } else {
                val on = index == permission.page
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                        .border(1.dp, if (on) colors.accent else Color.Transparent, RoundedCornerShape(6.dp))
                        .plainClick { onEvent(PermissionEvent.Page(index)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = sheetText(12.sp, FontWeight.Medium),
                        color = if (on) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
        SheetIconButton(
            ZillitIcons.ChevronRight,
            "Next page",
            { onEvent(PermissionEvent.Page(permission.page + 1)) },
            enabled = permission.page < pages - 1,
        )
        PageSizeChoice(permission.pageSize) { onEvent(PermissionEvent.PageSize(it)) }
        Spacer(Modifier.width(8.dp))
        Text("$first-$last of ${permission.total}", style = sheetText(12.sp), color = colors.textTertiary)
    }
}

/** At most seven page buttons: the ends, the neighbours of the current page, gaps as -1. */
internal fun pageWindow(page: Int, pages: Int): List<Int> {
    if (pages <= WINDOW) return (0 until pages).toList()
    val around = ((page - 1)..(page + 1)).filter { it in 1 until pages - 1 }
    val out = mutableListOf(0)
    if ((around.firstOrNull() ?: 1) > 1) out += -1
    out += around
    if ((around.lastOrNull() ?: (pages - 2)) < pages - 2) out += -1
    out += pages - 1
    return out
}

private const val WINDOW = 7

@Composable
private fun PageSizeChoice(size: Int, onChange: (Int) -> Unit) {
    val colors = SheetTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.borderStrong, RoundedCornerShape(6.dp))
                .plainClick { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$size / page", style = sheetText(12.sp), color = colors.textPrimary)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surface,
        ) {
            Column(Modifier.widthIn(min = 110.dp)) {
                PermissionState.PAGE_SIZES.forEach { option ->
                    Text(
                        "$option / page",
                        style = sheetText(13.sp, if (option == size) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (option == size) colors.accent else colors.textPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .plainClick {
                                open = false
                                onChange(option)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
