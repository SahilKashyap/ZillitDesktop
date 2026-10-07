package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.DesignationFilter
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection
import com.zillit.desktop.feature.permissiongrid.domain.SubjectColumn

/**
 * The Viewing & Posting Rights Grid — the web's `AccessGrid.jsx`, the one
 * screen Film Tools and Admin Settings both open.
 *
 * The amber toolbar (search, Home/Tools, the four types, the hired-designations
 * filter, View Default Grid), the crew-list banner for admins, then a bordered
 * table: frozen subject columns on the left and a tool per column after them,
 * each cell Viewing, Download and Posting stacked, with the pager under it.
 */
@Composable
fun PermissionGridScreen(
    state: PermissionGridUiState,
    onEvent: (PermissionGridEvent) -> Unit,
    modifier: Modifier = Modifier,
    onViewDefaultGrid: () -> Unit = {},
    onOpenListingOrder: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Toolbar(state, onEvent, onViewDefaultGrid)
            if (state.showsListingOrderBanner) ListingOrderBanner(onOpenListingOrder)
            Box(Modifier.weight(1f)) { Body(state, onEvent) }
        }
        ZillitToast(
            message = state.toast?.text,
            onDismiss = { onEvent(PermissionGridEvent.DismissToast) },
            tone = if (state.toast?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
        )
    }
}

@Composable
private fun Toolbar(
    state: PermissionGridUiState,
    onEvent: (PermissionGridEvent) -> Unit,
    onViewDefaultGrid: () -> Unit,
) {
    GridToolbar {
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(PermissionGridEvent.Search(it)) },
            placeholder = str(S.search),
            containerColor = ZillitTheme.colors.surface,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitSelect(
            value = state.section,
            options = GridSection.entries,
            onSelect = { onEvent(PermissionGridEvent.SelectSection(it)) },
            label = { it.label },
            showInitials = false,
            modifier = Modifier.width(SECTION_WIDTH),
        )
        ZillitSelect(
            value = state.axis,
            options = GridAxis.entries,
            onSelect = { onEvent(PermissionGridEvent.SelectAxis(it)) },
            label = { it.label },
            showInitials = false,
            modifier = Modifier.width(TYPE_WIDTH),
        )
        if (state.axis == GridAxis.Designations) {
            ZillitSelect(
                value = state.designations,
                options = DesignationFilter.entries,
                onSelect = { onEvent(PermissionGridEvent.SelectDesignations(it)) },
                label = { it.label },
                subtitle = { it.hint },
                showInitials = false,
                modifier = Modifier.width(FILTER_WIDTH),
            )
        }
        ZillitButton(
            text = str(S.desktop_pg_view_default_grid),
            onClick = onViewDefaultGrid,
            variant = ButtonVariant.Secondary,
        )
    }
}

/**
 * ZL-16967 / ZL-17040: on the crew-list axis an admin is told where the order
 * comes from, with a Click Here that opens the department listing order.
 */
@Composable
private fun ListingOrderBanner(onOpenListingOrder: (() -> Unit)?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.infoSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Info, tint = colors.info, size = 16.dp)
        ZillitText(
            text = "${str(S.desktop_cl_header_text)} ${str(S.or)}",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textPrimary,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onOpenListingOrder != null) {
            ZillitText(
                text = str(S.desktop_pg_click_here),
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                modifier = Modifier.clip(ZillitTheme.shapes.small).clickable(onClick = onOpenListingOrder),
            )
        }
    }
}

/** Which face the middle of the page wears — access, then load, then content. */
@Composable
private fun Body(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    when {
        !state.viewer.ready -> GridCentred(str(S.desktop_pg_checking_access))

        !state.viewer.canView -> ZillitEmptyState(
            title = str(S.dd_publish_no_access_badge),
            message = str(S.desktop_pg_no_viewing_rights),
        )

        state.error != null && state.grid.columns.isEmpty() -> ZillitErrorState(
            message = state.error,
            onRetry = { onEvent(PermissionGridEvent.Reload) },
        )

        // The web swaps the table for its loader on every read.
        state.isBusy -> GridLoading()

        else -> Table(state, onEvent)
    }
}

@Composable
private fun Table(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    val across = rememberScrollState()
    val down = rememberLazyListState()
    val subjects = state.grid.subjects.ifEmpty { SubjectColumn.defaultFor(state.axis) }
    val titles = remember(state.columns) { state.columns.associateWith { it.localised() } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface),
    ) {
        HeaderRow(subjects, state.columns, titles, across)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.rows.isEmpty()) {
                EmptyRows(state, onEvent)
            } else {
                ZillitLazyColumn(state = down, modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.subject.id }) { row ->
                        BodyRow(row, subjects, state, titles, across, onEvent)
                    }
                }
            }
        }
        ZillitHorizontalScrollRail(across, Modifier.fillMaxWidth())
        Divider()
        GridPager(
            page = state.page,
            pageSize = state.pageSize,
            total = state.grid.total,
            onPage = { onEvent(PermissionGridEvent.GoToPage(it)) },
            onPageSize = { onEvent(PermissionGridEvent.SetPageSize(it)) },
        )
    }
}

@Composable
private fun EmptyRows(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = if (state.search.isNotBlank()) {
                "${str(S.desktop_drive_no_results_found)} — ${state.query.trim()}"
            } else {
                str(S.desktop_pg_no_record_found)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        if (state.search.isNotBlank()) {
            ZillitButton(
                text = str(S.txt_clear),
                onClick = { onEvent(PermissionGridEvent.ClearSearch) },
                variant = ButtonVariant.Tertiary,
            )
        }
    }
}

/** The sticky header: subject titles frozen, tool titles scrolling with the body. */
@Composable
private fun HeaderRow(
    subjects: List<SubjectColumn>,
    columns: List<String>,
    titles: Map<String, String>,
    across: ScrollState,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .height(IntrinsicSize.Min),
    ) {
        subjects.forEach { column ->
            HeaderCell(column.title, subjectWidth(column))
            ColumnLine()
        }
        Row(Modifier.horizontalScroll(across)) {
            columns.forEach { unit ->
                val title = titles[unit] ?: unit
                HeaderCell(title, headerWidth(title))
                ColumnLine()
            }
        }
    }
    Divider()
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Box(
        Modifier.width(width).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun BodyRow(
    row: GridRow,
    subjects: List<SubjectColumn>,
    state: PermissionGridUiState,
    titles: Map<String, String>,
    across: ScrollState,
    onEvent: (PermissionGridEvent) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (hovered) ZillitTheme.colors.surfaceHover else ZillitTheme.colors.surface)
            .hoverable(interaction)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        subjects.forEach { column ->
            SubjectCell(row, column, isPerson = state.axis.isPeople)
            ColumnLine()
        }
        Row(Modifier.horizontalScroll(across).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            state.columns.forEach { unit ->
                RightsCell(
                    row = row,
                    unitName = unit,
                    width = headerWidth(titles[unit] ?: unit),
                    canEdit = state.canEdit,
                    onToggle = { kind, enable ->
                        onEvent(PermissionGridEvent.Toggle(row.subject.id, unit, kind, enable))
                    },
                )
                ColumnLine()
            }
        }
    }
    Divider()
}

@Composable
private fun SubjectCell(row: GridRow, column: SubjectColumn, isPerson: Boolean) {
    val subject = row.subject
    Box(Modifier.width(subjectWidth(column)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (column) {
            SubjectColumn.User -> PersonCell(row, showFace = isPerson)
            SubjectColumn.Department -> PlainCell(subject.department ?: subject.name.takeIf { !isPerson })
            SubjectColumn.Designation -> PlainCell(subject.designation ?: subject.name.takeIf { !isPerson })
        }
    }
}

/** Face, name with its Admin chip, and the job title under a hairline. */
@Composable
private fun PersonCell(row: GridRow, showFace: Boolean) {
    val subject = row.subject
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitAvatar(name = subject.name, userId = subject.id.takeIf { showFace }, size = AVATAR)
        Column(Modifier.weight(1f)) {
            ZillitTooltip(subject.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitText(
                        text = subject.name,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = ZillitTheme.colors.textPrimary,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (subject.isAdmin) AdminChip()
                }
            }
            subject.designation?.let { role ->
                Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
                ZillitTooltip(role) {
                    ZillitText(
                        text = role,
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminChip() {
    ZillitText(
        text = str(S.admin).uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = ZillitTheme.colors.accent,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(ZillitTheme.shapes.small)
            .background(ZillitTheme.colors.accentSoft)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun PlainCell(text: String?) {
    ZillitText(
        text = text.orEmpty(),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textPrimary,
    )
}

/**
 * One tool's three rights for one subject — Viewing, Download, Posting,
 * stacked as the web stacks them. A tool the row did not carry renders a
 * dash rather than three boxes that would invite a click going nowhere.
 */
@Composable
private fun RightsCell(
    row: GridRow,
    unitName: String,
    width: Dp,
    canEdit: Boolean,
    onToggle: (AccessKind, Boolean) -> Unit,
) {
    Column(
        Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (row.cells[unitName] == null) {
            ZillitText(text = "—", style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            return@Column
        }
        AccessKind.entries.forEach { kind ->
            ZillitCheckbox(
                checked = row.shownGranted(unitName, kind),
                onCheckedChange = { onToggle(kind, it) },
                enabled = canEdit && row.editable(unitName, kind),
                label = kind.label,
            )
        }
    }
}

@Composable
private fun ColumnLine() {
    Box(Modifier.width(HAIRLINE).fillMaxHeight().background(ZillitTheme.colors.border))
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
}

private val SubjectColumn.title: String
    get() = when (this) {
        SubjectColumn.User -> str(S.invitees_tab_users)
        SubjectColumn.Department -> str(S.departments)
        SubjectColumn.Designation -> str(S.designations)
    }

private fun subjectWidth(column: SubjectColumn): Dp = when (column) {
    SubjectColumn.User -> USER_WIDTH
    else -> headerWidth(column.title)
}

private val HAIRLINE = 1.dp
private val AVATAR = 44.dp
private val USER_WIDTH = 260.dp
private val SEARCH_WIDTH = 320.dp
private val SECTION_WIDTH = 160.dp
private val TYPE_WIDTH = 280.dp
private val FILTER_WIDTH = 240.dp
