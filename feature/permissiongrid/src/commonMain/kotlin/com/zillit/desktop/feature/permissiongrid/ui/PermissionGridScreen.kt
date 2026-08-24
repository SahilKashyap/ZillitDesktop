package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.GridAxis
import com.zillit.desktop.feature.permissiongrid.domain.GridCell
import com.zillit.desktop.feature.permissiongrid.domain.GridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection

/**
 * The production's viewing & posting rights, as a spreadsheet.
 *
 * One frozen column of subjects on the left and a tool per column across,
 * every cell three checkboxes — view, post, download. The matrix scrolls
 * sideways under a header that scrolls with it, because a production runs
 * forty tools and no window is that wide.
 */
@Composable
fun PermissionGridScreen(
    state: PermissionGridUiState,
    onEvent: (PermissionGridEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
    ) {
        ZillitPageHeader(
            title = "Viewing & Posting Rights Grid",
            description = "Who may see, post to and download from each tool on this production.",
        )

        Controls(state, onEvent)

        state.notice?.let { notice ->
            ZillitNotice(
                text = notice,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Info,
                modifier = Modifier.padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
            )
        }

        if (!state.canEdit && state.viewer.ready && state.viewer.canView) {
            ZillitNotice(
                text = "You can see this grid but not change it — posting rights on the " +
                    "permission grid tool are what allow an edit.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                modifier = Modifier.padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
            )
        }

        Box(Modifier.weight(1f)) { Body(state, onEvent) }

        if (state.viewer.canView && state.grid.rows.isNotEmpty()) Footer(state, onEvent)
    }
}

/** Which face the middle of the page wears — access, then load, then content. */
@Composable
private fun Body(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    when {
        !state.viewer.ready -> Centred("Checking your access…")

        !state.viewer.canView -> ZillitEmptyState(
            title = "No access",
            message = "You do not have viewing rights on the permission grid. " +
                "A coordinator can grant them.",
        )

        state.error != null -> ZillitErrorState(
            message = state.error,
            onRetry = { onEvent(PermissionGridEvent.Reload) },
        )

        state.isBusy && state.grid.rows.isEmpty() -> Centred("Loading the grid…")

        state.grid.rows.isEmpty() -> ZillitEmptyState(
            title = "Nothing to show",
            message = "This production has no ${state.axis.label.lowercase()} to grant rights to.",
        )

        state.rows.isEmpty() -> Centred("No one matches \"${state.query.trim()}\".")

        else -> Matrix(state, onEvent)
    }
}

/** The two selects, the search box — the web's own row of controls. */
@Composable
private fun Controls(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitSelect(
            value = state.section,
            options = GridSection.entries,
            onSelect = { onEvent(PermissionGridEvent.SelectSection(it)) },
            label = { it.label },
            enabled = !state.isBusy,
            modifier = Modifier.width(SELECT_WIDTH),
        )
        ZillitSelect(
            value = state.axis,
            options = GridAxis.entries,
            onSelect = { onEvent(PermissionGridEvent.SelectAxis(it)) },
            label = { it.label },
            enabled = !state.isBusy,
            modifier = Modifier.width(SELECT_WIDTH),
        )
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(PermissionGridEvent.Search(it)) },
            placeholder = "Search ${state.axis.label.lowercase()}…",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
}

@Composable
private fun Matrix(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    // One scroll state for the header and every row, so the frozen column
    // stays put while the tools move together underneath their own titles.
    val across = rememberScrollState()
    val down = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = PAGE_PADDING),
        ) {
            Box(Modifier.width(SUBJECT_WIDTH).height(HEADER_HEIGHT), Alignment.CenterStart) {
                ZillitText(
                    text = state.axis.label,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            Row(Modifier.horizontalScroll(across)) {
                state.columns.forEach { unitName ->
                    Box(Modifier.width(CELL_WIDTH).height(HEADER_HEIGHT), Alignment.Center) {
                        ZillitText(
                            text = unitName.localised(),
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }

        ZillitLazyColumn(
            state = down,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.rows, key = { it.subject.id }) { row ->
                SubjectRow(row, state, across, onEvent)
            }
        }
    }
}

@Composable
private fun SubjectRow(
    row: GridRow,
    state: PermissionGridUiState,
    across: androidx.compose.foundation.ScrollState,
    onEvent: (PermissionGridEvent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(SUBJECT_WIDTH).padding(vertical = ZillitTheme.spacing.sm)) {
            ZillitText(
                text = row.subject.name,
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
            // Only the people axis carries these; a department row would
            // otherwise print its own name twice.
            listOfNotNull(row.subject.department, row.subject.designation)
                .takeIf { it.isNotEmpty() }
                ?.let { extra ->
                    ZillitText(
                        text = extra.joinToString(" · "),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
        }
        Row(Modifier.horizontalScroll(across)) {
            state.columns.forEach { unitName ->
                CellBoxes(
                    cell = row.cells[unitName],
                    enabled = state.canEdit,
                    onToggle = { kind, enable ->
                        onEvent(
                            PermissionGridEvent.Toggle(
                                subjectId = row.subject.id,
                                unitName = unitName,
                                kind = kind,
                                enable = enable,
                            ),
                        )
                    },
                )
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(HAIRLINE)
            .background(ZillitTheme.colors.border),
    )
}

/**
 * One tool's three rights for one subject.
 *
 * A cell the server did not send is a tool this subject cannot be granted at
 * all — it renders empty rather than as three unchecked boxes, which would
 * invite a click that goes nowhere.
 */
@Composable
private fun CellBoxes(
    cell: GridCell?,
    enabled: Boolean,
    onToggle: (AccessKind, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.width(CELL_WIDTH).padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cell == null) {
            ZillitText(
                text = "—",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@Row
        }
        AccessKind.entries.forEach { kind ->
            ZillitCheckbox(
                checked = cell.granted(kind),
                onCheckedChange = { onToggle(kind, it) },
                enabled = enabled && !cell.locked(kind) && !cell.busy,
                // The whole word. "V / P / D" reads as a legend you have to
                // learn, and the three rights are the one thing on this screen
                // nobody should have to guess at.
                label = kind.label,
            )
        }
    }
}

/** Page N of M, and the two steps between them. */
@Composable
private fun Footer(state: PermissionGridUiState, onEvent: (PermissionGridEvent) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = PAGE_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = "${state.grid.total} ${state.axis.label.lowercase()} · page ${state.page} of ${state.lastPage}",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        Spacer(Modifier.weight(1f))
        ZillitButton(
            text = "Previous",
            variant = ButtonVariant.Secondary,
            enabled = state.canGoBack && !state.isBusy,
            onClick = { onEvent(PermissionGridEvent.GoToPage(state.page - 1)) },
        )
        ZillitButton(
            text = "Next",
            variant = ButtonVariant.Secondary,
            enabled = state.canGoForward && !state.isBusy,
            onClick = { onEvent(PermissionGridEvent.GoToPage(state.page + 1)) },
        )
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val PAGE_PADDING = 24.dp
private val SUBJECT_WIDTH = 220.dp
/** Wide enough for "View  Post  Download" on one line, boxes included. */
private val CELL_WIDTH = 250.dp
private val HEADER_HEIGHT = 56.dp
private val SELECT_WIDTH = 180.dp
private val SEARCH_WIDTH = 260.dp
private val HAIRLINE = 1.dp
