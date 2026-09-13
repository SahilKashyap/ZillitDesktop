package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.ChartSortKey
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaStats
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.ChartTreeRow
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.components.CARD_SHAPE
import com.zillit.desktop.feature.accounthub.ui.components.CoaCard
import com.zillit.desktop.feature.accounthub.ui.components.CoaChevron
import com.zillit.desktop.feature.accounthub.ui.components.CoaCostChip
import com.zillit.desktop.feature.accounthub.ui.components.CoaCostSelect
import com.zillit.desktop.feature.accounthub.ui.components.CoaIconWash
import com.zillit.desktop.feature.accounthub.ui.components.CoaIcons
import com.zillit.desktop.feature.accounthub.ui.components.CoaLine
import com.zillit.desktop.feature.accounthub.ui.components.CoaOnOffPill
import com.zillit.desktop.feature.accounthub.ui.components.CoaRowAction
import com.zillit.desktop.feature.accounthub.ui.components.CoaStatusChips
import com.zillit.desktop.feature.accounthub.ui.components.CoaToolbarButton
import com.zillit.desktop.feature.accounthub.ui.components.CoaTypeChip
import com.zillit.desktop.feature.accounthub.ui.components.CoaUnnamed
import com.zillit.desktop.feature.accounthub.ui.components.CoaViewSwitch
import com.zillit.desktop.feature.accounthub.ui.components.TOOLBAR_HEIGHT
import com.zillit.desktop.feature.accounthub.ui.components.TOOLBAR_SHAPE
import com.zillit.desktop.feature.accounthub.ui.components.coaEyebrow
import com.zillit.desktop.feature.accounthub.ui.components.coaMono
import com.zillit.desktop.feature.accounthub.ui.components.coaTypeTone

/**
 * Cost Accounts and Balance Sheet Codes — the web's `AccountsTab`: the stat
 * strip, the toolbar, and the chart as a tree or a table.
 */
@Composable
internal fun ColumnScope.ChartAccountsTab(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canImportBudget: Boolean,
) {
    val chart = state.chart
    val canEdit = state.viewer.canActAsAccountant
    // Import Budget is the cost side's: a budget describes costs, never the balance sheet.
    val importOffered = chart.view != ChartView.BalanceSheet
    val canImport = canEdit && canImportBudget && importOffered

    CoaStatStrip(chart.stats)
    CoaToolbar(chart, canEdit, canImport, onEvent)

    when {
        chart.loading && !chart.loaded -> CoaLoadingCard("Loading chart of accounts…")
        chart.isViewEmpty -> CoaEmptyAccounts(canEdit, canImport, importOffered, onEvent)
        else -> {
            val orphans = remember(chart.accounts, chart.view, chart.showInactive) { chart.forest.orphans.size }
            if (orphans > 0 && chart.search.isBlank()) {
                // Surfaced rather than dropped. These are live codes whose parent
                // is hidden — still postable, and invisible on a screen meant to
                // list every code is worse than shown without their ancestry.
                ZillitNotice(
                    text = "$orphans code${if (orphans == 1) "" else "s"} sit under a parent that is not shown " +
                        "and are listed at the end.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Warning,
                )
            }
            when (chart.mode) {
                ChartMode.Tree -> CoaTreeView(chart, canEdit, onEvent)
                ChartMode.Table -> CoaTableView(chart, canEdit, onEvent)
            }
        }
    }
}

// -- stat strip ------------------------------------------------------------------------

/** Five cells ruled apart by the divider showing through their gaps (`StatStrip`). */
@Composable
private fun CoaStatStrip(stats: CoaStats) {
    val colors = ZillitTheme.colors
    val cells = listOf(
        Triple("Total codes", stats.total, "across the chart"),
        Triple("Headers", stats.headers, "top-level groups"),
        Triple("Nominals", stats.nominals, "mid-level"),
        Triple("Codes", stats.codes, "postable"),
        Triple("Active", stats.active, "enabled for posting"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(CARD_SHAPE)
            .background(colors.divider)
            .border(1.dp, colors.border, CARD_SHAPE),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.forEach { (label, value, hint) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(colors.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ZillitText(label.uppercase(), style = coaEyebrow(), color = colors.textMuted, maxLines = 1)
                ZillitText(
                    value.toString(),
                    style = ZillitTheme.typography.titleLarge.copy(
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = colors.textPrimary,
                )
                ZillitText(hint, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
            }
        }
    }
}

// -- toolbar ---------------------------------------------------------------------------

/** Search, Show inactive, Tree | Table, Refresh, Expand all — then Import Budget and New COA Entry (`Toolbar`). */
@Composable
private fun CoaToolbar(chart: ChartState, canEdit: Boolean, canImport: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ToolbarControls(chart, onEvent, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canImport) {
                ZillitButton(
                    text = "Import Budget",
                    onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = CoaIcons.Upload,
                    modifier = Modifier.height(TOOLBAR_HEIGHT),
                )
            }
            if (canEdit) {
                ZillitButton(
                    text = "New COA Entry",
                    onClick = { onEvent(AccountHubEvent.OpenBulkAdd(null)) },
                    leadingIcon = CoaIcons.Plus,
                    modifier = Modifier.height(TOOLBAR_HEIGHT),
                )
            }
        }
    }
}

/** The left cluster, which wraps before it crowds the create and import actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarControls(chart: ChartState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitSearchField(
            value = chart.search,
            onValueChange = { onEvent(AccountHubEvent.SearchChart(it)) },
            placeholder = "Search codes or names…",
            modifier = Modifier.width(SEARCH_WIDTH).height(TOOLBAR_HEIGHT),
        )
        CoaToolbarButton(
            label = "Show inactive",
            icon = CoaIcons.Eye,
            onClick = { onEvent(AccountHubEvent.ToggleInactiveAccounts) },
            selected = chart.showInactive,
            trailing = { CoaOnOffPill(chart.showInactive) },
        )
        CoaViewSwitch(
            options = listOf(
                Triple(ChartMode.Tree, "Tree", CoaIcons.Tree),
                Triple(ChartMode.Table, "Table", CoaIcons.Table),
            ),
            value = chart.mode,
            onChange = { onEvent(AccountHubEvent.SetChartMode(it)) },
        )
        CoaSquareButton(tooltip = "Refresh", onClick = { onEvent(AccountHubEvent.Refresh) }) {
            if (chart.loading) {
                ZillitSpinner(size = 14.dp)
            } else {
                ZillitIcon(
                    CoaIcons.Refresh,
                    contentDescription = "Refresh",
                    tint = ZillitTheme.colors.textSecondary,
                    size = 14.dp,
                )
            }
        }
        // Tree only: the table's rows are flat, with nothing to fold.
        if (chart.mode == ChartMode.Tree) {
            CoaToolbarButton(
                label = chart.foldLabel,
                icon = CoaIcons.Expand,
                onClick = { onEvent(AccountHubEvent.ToggleExpandAll) },
            )
        }
    }
}

/** A square bordered icon button the toolbar's height. */
@Composable
private fun CoaSquareButton(tooltip: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(TOOLBAR_HEIGHT)
                .clip(TOOLBAR_SHAPE)
                .background(if (hovered) colors.surfaceHover else colors.surface)
                .border(1.dp, colors.border, TOOLBAR_SHAPE)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

// -- loading and empty -----------------------------------------------------------------

@Composable
internal fun CoaLoadingCard(message: String) {
    CoaCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSpinner(size = 16.dp)
            ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textMuted)
        }
    }
}

/** The web's `EmptyAccounts`: two ways to start on the cost side, one on the balance sheet. */
@Composable
private fun CoaEmptyAccounts(
    canEdit: Boolean,
    canImport: Boolean,
    importOffered: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    CoaCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CoaIconWash(CoaIcons.TreeLarge, box = 56.dp, glyph = 28.dp)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitText(
                    "Your Chart of Accounts is empty",
                    style = ZillitTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                ZillitText(
                    if (importOffered) {
                        "Two ways to start: upload an existing budget (PDF/Excel) and we'll extract the codes " +
                            "for you, or build one manually from scratch."
                    } else {
                        "Build your codes manually from scratch."
                    },
                    style = ZillitTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (canImport) {
                        ZillitButton(
                            text = "Import Budget",
                            onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                            leadingIcon = CoaIcons.Upload,
                        )
                    }
                    ZillitButton(
                        text = "Build from scratch",
                        onClick = { onEvent(AccountHubEvent.OpenBulkAdd(null)) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = CoaIcons.Plus,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoaNoMatches(query: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
        ZillitText(
            "No accounts match “$query”.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

// -- tree view -------------------------------------------------------------------------

@Composable
private fun ColumnScope.CoaTreeView(chart: ChartState, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val fold = Triple(chart.expanded, chart.collapsed, chart.fold)
    val rows = remember(chart.accounts, chart.view, chart.showInactive, chart.search, fold) { chart.treeRows() }
    // Sized to the rows up to the space left, then scrolling — a short chart is not a tall empty card.
    CoaCard(Modifier.weight(1f, fill = false)) {
        if (rows.isEmpty()) {
            CoaNoMatches(chart.search)
        } else {
            ZillitLazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            ) {
                items(rows, key = { it.account.id }) { row -> CoaTreeRow(row, canEdit, onEvent) }
            }
        }
    }
}

/**
 * One tree row (`TreeRow`): indent guides, chevron, code, name, chips, the
 * level tag and — on hover — the row's actions.
 *
 * A retired row fades element by element, never as a whole, so its "Inactive"
 * chip and its actions stay at full strength.
 */
@Suppress("LongMethod") // A row, read left to right; the order is the reading order.
@Composable
private fun CoaTreeRow(row: ChartTreeRow, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val account = row.account
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dim = if (account.isActive) 1f else INACTIVE_ALPHA
    val guide = colors.border
    Box(
        Modifier.fillMaxWidth().drawBehind {
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            repeat(row.depth) { level ->
                val x = (GUIDE_OFFSET + INDENT * level).dp.toPx()
                drawLine(guide, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
        },
    ) {
        Row(
            modifier = Modifier
                .padding(start = (INDENT * row.depth).dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) colors.surfaceHover else Color.Transparent)
                .hoverable(interaction)
                .then(
                    if (row.hasChildren) {
                        Modifier.clickable(interactionSource = interaction, indication = null) {
                            onEvent(AccountHubEvent.ToggleAccountExpanded(account.id, row.depth))
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(16.dp).alpha(dim), contentAlignment = Alignment.Center) {
                if (row.hasChildren) {
                    CoaChevron(open = row.open, tint = colors.textSecondary)
                } else {
                    Box(Modifier.size(4.dp).clip(CircleShape).background(colors.borderStrong))
                }
            }
            ZillitText(
                account.code.uppercase(),
                style = coaMono(12.5.sp, FontWeight.SemiBold, 0.3.sp),
                color = colors.textMuted,
                modifier = Modifier.widthIn(min = 56.dp).alpha(dim),
                maxLines = 1,
            )
            TreeName(account, Modifier.weight(1f).alpha(dim))
            CoaStatusChips(account)
            TreeLevelTag(account, hovered, dim)
            if (account.isFromBudget) {
                // Which rows the budget owns, shown on hover to keep the tree calm.
                Box(
                    Modifier
                        .alpha(if (hovered) dim else 0f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceSunken)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    ZillitText("BUDGET", style = coaMono(9.5.sp, FontWeight.Bold, 0.5.sp), color = colors.textMuted)
                }
            }
            if (canEdit) {
                // A fixed slot, so the level tags line up whether a row offers two actions or three.
                Row(
                    modifier = Modifier.width(TREE_ACTIONS).alpha(if (hovered) 1f else 0f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    RowActions(account, onEvent)
                }
            }
        }
    }
}

@Composable
private fun TreeName(account: CoaAccount, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val style = when (account.lineType) {
        CoaLineType.Header -> ZillitTheme.typography.bodyMedium.copy(
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
        )
        CoaLineType.Section ->
            ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        else -> ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
    val color = if (account.lineType.depth <= CoaLineType.Section.depth) colors.textPrimary else colors.textSecondary
    Box(modifier) {
        if (account.name.isEmpty()) {
            CoaUnnamed(style)
        } else {
            val text = if (account.lineType == CoaLineType.Header) account.name.uppercase() else account.name
            CoaLine(text, style, color)
        }
    }
}

/**
 * The level's word beside the name: always for Headers and Code, on hover for
 * Nominal so the tree stays calm, never for the top level (its label is blank).
 * Faded rather than removed on hover, so the row does not reflow under the pointer.
 */
@Composable
private fun TreeLevelTag(account: CoaAccount, hovered: Boolean, dim: Float) {
    val label = account.lineType.label
    if (label.isEmpty()) return
    val tone = coaTypeTone(account.lineType)
    val nominal = account.lineType == CoaLineType.Category
    val shown = !nominal || hovered
    ZillitText(
        label.uppercase(),
        style = coaMono(9.5.sp, FontWeight.Bold, 0.7.sp),
        color = tone.content,
        modifier = Modifier.alpha(if (shown) (if (nominal) NOMINAL_TAG_ALPHA else 1f) * dim else 0f),
        maxLines = 1,
    )
}

/** Add child (every level but the leaf), Edit, Deactivate — the same three in the tree and the table. */
@Composable
private fun RowActions(account: CoaAccount, onEvent: (AccountHubEvent) -> Unit) {
    account.lineType.addChildLabel?.let { tooltip ->
        CoaRowAction(CoaIcons.Plus, tooltip, onClick = { onEvent(AccountHubEvent.OpenBulkAdd(account)) })
    }
    CoaRowAction(CoaIcons.Edit, "Edit", onClick = { onEvent(AccountHubEvent.ComposeAccount(editing = account)) })
    CoaRowAction(
        CoaIcons.Trash,
        "Deactivate",
        danger = true,
        onClick = { onEvent(AccountHubEvent.AskDeactivateAccount(account)) },
    )
}

// -- table view ------------------------------------------------------------------------

@Suppress("LongMethod") // A table: heading, rows, footer.
@Composable
private fun ColumnScope.CoaTableView(chart: ChartState, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val visible = remember(chart.accounts, chart.view, chart.showInactive) { chart.visibleAccounts }
    val rows = remember(visible, chart.search, chart.sort) { chart.tableRows }
    // The breadcrumb resolves against the rows on screen, as the web's does.
    val byId = remember(visible) { visible.associateBy { it.id } }
    CoaCard(Modifier.weight(1f, fill = false)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader(ChartSortKey.Code, chart, onEvent, Modifier.widthIn(min = CODE_COLUMN, max = CODE_COLUMN))
            SortHeader(ChartSortKey.Type, chart, onEvent, Modifier.widthIn(min = TYPE_COLUMN, max = TYPE_COLUMN))
            SortHeader(ChartSortKey.Name, chart, onEvent, Modifier.weight(1f))
            SortHeader(ChartSortKey.CostType, chart, onEvent, Modifier.widthIn(min = COST_COLUMN, max = COST_COLUMN))
            ZillitText(
                "STATUS",
                style = coaEyebrow(),
                color = colors.textMuted,
                modifier = Modifier.widthIn(min = STATUS_COLUMN, max = STATUS_COLUMN),
            )
            Box(Modifier.widthIn(min = ACTION_COLUMN, max = ACTION_COLUMN))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        if (rows.isEmpty()) {
            CoaNoMatches(chart.search)
        } else {
            ZillitLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                    CoaTableRow(row, byId, last = index == rows.lastIndex, canEdit = canEdit, onEvent = onEvent)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val filtered = if (chart.search.isNotBlank()) " · filtered from ${visible.size}" else ""
            ZillitText("${rows.size} rows$filtered", style = coaMono(12.sp), color = colors.textMuted)
            Box(Modifier.weight(1f))
            ZillitText("Sorted by ", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            ZillitText(
                chart.sort.key.key,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
            )
            ZillitText(
                if (chart.sort.ascending) " ↑" else " ↓",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
    }
}

@Composable
private fun SortHeader(key: ChartSortKey, chart: ChartState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val active = chart.sort.key == key
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.clickable { onEvent(AccountHubEvent.SortChart(key)) },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (active) colors.accent else colors.textMuted
            ZillitText(key.label.uppercase(), style = coaEyebrow(), color = color, maxLines = 1)
            ZillitText(
                if (active && !chart.sort.ascending) "↓" else "↑",
                style = coaEyebrow(),
                color = color,
                modifier = Modifier.alpha(if (active) 1f else INACTIVE_SORT_ALPHA),
            )
        }
    }
}

/** One table row (`TableRow`): code, level chip, name over its breadcrumb, class, status, actions. */
@Suppress("LongMethod") // A row, read left to right; the order is the reading order.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoaTableRow(
    account: CoaAccount,
    byId: Map<String, CoaAccount>,
    last: Boolean,
    canEdit: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dim = if (account.isActive) 1f else INACTIVE_ALPHA
    val path = remember(account, byId) { ChartOfAccounts.breadcrumb(byId, account) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hovered) colors.surfaceHover else colors.surface)
                .hoverable(interaction)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                account.code.uppercase(),
                style = coaMono(12.5.sp, FontWeight.SemiBold, 0.3.sp),
                color = colors.textSecondary,
                modifier = Modifier.widthIn(min = CODE_COLUMN, max = CODE_COLUMN).alpha(dim),
                maxLines = 1,
            )
            Box(Modifier.widthIn(min = TYPE_COLUMN, max = TYPE_COLUMN).alpha(dim)) { CoaTypeChip(account.lineType) }
            Column(Modifier.weight(1f).alpha(dim), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val nameStyle = ZillitTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (account.name.isEmpty()) {
                    CoaUnnamed(nameStyle)
                } else {
                    CoaLine(account.name, nameStyle, colors.textPrimary)
                }
                if (path.isNotEmpty()) {
                    CoaLine(path.joinToString(" / "), coaMono(11.sp, spacing = 0.2.sp), colors.textMuted)
                }
            }
            Box(Modifier.widthIn(min = COST_COLUMN, max = COST_COLUMN).alpha(dim)) {
                if (account.isFromBudget || !canEdit) {
                    // Read-only for a budget row — the server refuses the change — and for a viewer.
                    CoaCostChip(
                        account.costType,
                        tooltip = if (account.isFromBudget) {
                            "Budget-imported rows are permanently classified as Expense."
                        } else {
                            ""
                        },
                    )
                } else {
                    CoaCostSelect(account.costType) {
                        onEvent(AccountHubEvent.SetAccountCostTypeInline(account.id, it))
                    }
                }
            }
            FlowRow(
                modifier = Modifier.widthIn(min = STATUS_COLUMN, max = STATUS_COLUMN),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) { CoaStatusChips(account) }
            Row(
                modifier = Modifier
                    .widthIn(min = ACTION_COLUMN, max = ACTION_COLUMN)
                    .alpha(if (hovered) 1f else IDLE_ACTIONS_ALPHA),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                if (canEdit) RowActions(account, onEvent)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

private const val INDENT = 22
private const val GUIDE_OFFSET = 12
private const val INACTIVE_ALPHA = 0.55f
private const val NOMINAL_TAG_ALPHA = 0.7f
private const val IDLE_ACTIONS_ALPHA = 0.4f
private const val INACTIVE_SORT_ALPHA = 0.35f
private val SEARCH_WIDTH = 300.dp
private val TREE_ACTIONS = 80.dp
private val CODE_COLUMN = 100.dp
private val TYPE_COLUMN = 110.dp
private val COST_COLUMN = 150.dp
private val STATUS_COLUMN = 120.dp
private val ACTION_COLUMN = 96.dp
