package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.permissiongrid.domain.AccessKind
import com.zillit.desktop.feature.permissiongrid.domain.DefaultGridRow
import com.zillit.desktop.feature.permissiongrid.domain.GridSection

/**
 * The read-only Default Grid — the web's `Defaultgrid.jsx`, reached from
 * "View Default Grid": what each department or designation is granted by
 * default, every tool a column group of Viewing, Download and Posting. Its
 * only action is the Excel download.
 */
@Composable
fun DefaultGridScreen(
    state: PermissionGridUiState,
    onEvent: (PermissionGridEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = state.defaults
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            DefaultToolbar(d, onEvent, onBack)
            Box(Modifier.weight(1f)) {
                when {
                    !state.viewer.ready -> GridCentred(str(S.desktop_pg_checking_access))
                    !state.viewer.canView -> ZillitEmptyState(
                        title = str(S.dd_publish_no_access_badge),
                        message = str(S.desktop_pg_no_viewing_rights),
                    )
                    d.isBusy -> GridLoading()
                    d.error != null -> ZillitErrorState(
                        message = d.error,
                        onRetry = { onEvent(PermissionGridEvent.Defaults.Open) },
                    )
                    else -> DefaultTable(d, onEvent)
                }
            }
        }
        ZillitToast(
            message = state.toast?.text,
            onDismiss = { onEvent(PermissionGridEvent.DismissToast) },
            tone = if (state.toast?.success == true) ZillitToastTone.Success else ZillitToastTone.Danger,
        )
    }
}

@Composable
private fun DefaultToolbar(d: DefaultGridState, onEvent: (PermissionGridEvent) -> Unit, onBack: () -> Unit) {
    GridToolbar {
        ZillitIconButton(
            icon = ZillitIcons.ArrowLeft,
            contentDescription = str(S.back),
            onClick = onBack,
            tint = ZillitTheme.colors.textOnAccent,
        )
        ZillitSearchField(
            value = d.query,
            onValueChange = { onEvent(PermissionGridEvent.Defaults.Search(it)) },
            placeholder = str(S.search),
            containerColor = ZillitTheme.colors.surface,
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitSelect(
            value = d.section,
            options = GridSection.entries,
            onSelect = { onEvent(PermissionGridEvent.Defaults.SelectSection(it)) },
            label = { it.label },
            showInitials = false,
            modifier = Modifier.width(SECTION_WIDTH),
        )
        ZillitSelect(
            value = d.axis,
            options = DefaultGridState.axes,
            onSelect = { onEvent(PermissionGridEvent.Defaults.SelectAxis(it)) },
            label = { it.label },
            showInitials = false,
            modifier = Modifier.width(TYPE_WIDTH),
        )
        ZillitButton(
            text = str(S.desktop_pg_download_excel),
            onClick = { onEvent(PermissionGridEvent.Defaults.DownloadExcel) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Download,
        )
    }
}

@Composable
private fun DefaultTable(d: DefaultGridState, onEvent: (PermissionGridEvent) -> Unit) {
    val across = rememberScrollState()
    val down = rememberLazyListState()
    val titles = remember(d.grid.columns) { d.grid.columns.map { it to it.localised() } }
    val firstTitle = d.grid.first.localised()

    Column(
        Modifier
            .fillMaxSize()
            .padding(ZillitTheme.spacing.sm)
            .clip(ZillitTheme.shapes.medium)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surface),
    ) {
        GroupedHeader(firstTitle, titles, across)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val rows = d.visible
            if (rows.isEmpty()) {
                GridCentred(str(S.desktop_pg_no_record_found))
            } else {
                ZillitLazyColumn(state = down, modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.key }) { row -> DefaultRow(row, titles.map { it.first }, across) }
                }
            }
        }
        ZillitHorizontalScrollRail(across, Modifier.fillMaxWidth())
        Line()
        GridPager(
            page = d.page.coerceAtMost(d.lastPage),
            pageSize = d.pageSize,
            total = d.matching.size,
            onPage = { onEvent(PermissionGridEvent.Defaults.GoToPage(it)) },
            onPageSize = { onEvent(PermissionGridEvent.Defaults.SetPageSize(it)) },
        )
    }
}

/** Two header bands: the tool over its three rights. */
@Composable
private fun GroupedHeader(firstTitle: String, titles: List<Pair<String, String>>, across: ScrollState) {
    Row(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).height(IntrinsicSize.Min),
    ) {
        HeaderText(firstTitle, FIRST_WIDTH, Modifier.fillMaxHeight())
        Upright()
        Row(Modifier.horizontalScroll(across)) {
            titles.forEach { (_, title) ->
                Column(Modifier.width(GROUP_WIDTH)) {
                    HeaderText(title, GROUP_WIDTH, centred = true)
                    Line()
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        AccessKind.entries.forEachIndexed { index, kind ->
                            HeaderText(kind.label, SUB_WIDTH, centred = true)
                            if (index < AccessKind.entries.lastIndex) Upright()
                        }
                    }
                }
                Upright()
            }
        }
    }
    Line()
}

@Composable
private fun HeaderText(text: String, width: Dp, modifier: Modifier = Modifier, centred: Boolean = false) {
    Box(
        modifier.width(width).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textPrimary,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Composable
private fun DefaultRow(row: DefaultGridRow, units: List<String>, across: ScrollState) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(FIRST_WIDTH).padding(horizontal = 8.dp, vertical = 10.dp)) {
            ZillitText(
                text = row.name,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textPrimary,
            )
        }
        Upright()
        Row(Modifier.horizontalScroll(across).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            units.forEach { unit ->
                val cell = row.byUnit[unit]
                AccessKind.entries.forEachIndexed { index, kind ->
                    Box(Modifier.width(SUB_WIDTH), contentAlignment = Alignment.Center) {
                        ZillitCheckbox(checked = cell?.granted(kind) == true, onCheckedChange = {}, enabled = false)
                    }
                    if (index < AccessKind.entries.lastIndex) Upright()
                }
                Upright()
            }
        }
    }
    Line()
}

@Composable
private fun Upright() {
    Box(Modifier.width(HAIRLINE).fillMaxHeight().background(ZillitTheme.colors.border))
}

@Composable
private fun Line() {
    Box(Modifier.fillMaxWidth().height(HAIRLINE).background(ZillitTheme.colors.border))
}

private val HAIRLINE = 1.dp
private val FIRST_WIDTH = 200.dp
private val SUB_WIDTH = 100.dp
private val GROUP_WIDTH = SUB_WIDTH * 3 + HAIRLINE * 2
private val SEARCH_WIDTH = 320.dp
private val SECTION_WIDTH = 160.dp
private val TYPE_WIDTH = 280.dp
