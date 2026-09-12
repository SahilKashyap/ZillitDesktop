package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.ChartMode
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.ChartSortKey
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSets
import com.zillit.desktop.feature.accounthub.ui.AccountForm
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.LayerDelete
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HoverRow
import com.zillit.desktop.feature.accounthub.ui.components.HubConfirmDialog
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoChip
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.StatCard
import com.zillit.desktop.feature.accounthub.ui.components.SubCard

/**
 * The Chart of Accounts — the web's `ChartOfAccountsModule`.
 *
 * ## One tree, three views
 *
 * Cost accounts and balance-sheet codes are the same rows filtered by class,
 * not two resources — they share one fetch, and a code that changes class moves
 * between the views without a reload. Layers are the analytical dimensions
 * beside the chart, with their own CRUD.
 *
 * ## Tree or table, and a grid
 *
 * The web draws the chart two ways — an indented tree with hover actions, and
 * a sortable table with a breadcrumb and an inline cost-type select — and
 * adds codes in bulk on a full-page grid that autosaves row by row. All three
 * are here.
 */
@Composable
fun ChartOfAccountsPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    if (chart.bulk != null) {
        BulkAddPage(state, onEvent)
        return
    }

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Chart of Accounts",
            description = "The nominal taxonomy that drives Cost Report. Every line item across the platform — " +
                "purchase orders, invoices, card receipts, cash claims, payroll — codes against this tree. " +
                "(Budgets now live under the Budget menu item.)",
        )

        ZillitTabStrip(
            tabs = ChartView.entries.map { ZillitTab(it.slug, it.label) },
            activeId = chart.view.slug,
            onSelect = { slug ->
                ChartView.entries.firstOrNull { it.slug == slug }?.let { onEvent(AccountHubEvent.SwitchChartView(it)) }
            },
        )

        if (!state.viewer.canActAsAccountant) {
            // Named specifically. The rest of the console *is* editable by an
            // admin, so a silently read-only screen here reads as a bug rather
            // than as the rule it is.
            ZillitNotice(
                text = "The chart is read-only for you — the service restricts changes to the accounts department, " +
                    "and an admin is not exempt.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (chart.view == ChartView.Layers) {
            LayersTab(state, onEvent)
        } else {
            AccountsTab(state, onEvent)
        }
    }

    AccountFormDialog(state, onEvent)
    HubConfirmDialog(
        visible = chart.confirmDeactivate != null,
        title = "Deactivate code",
        message = chart.confirmDeactivate?.let { "Deactivate \"${it.display}\"?" }.orEmpty(),
        confirmLabel = "Deactivate",
        onConfirm = { onEvent(AccountHubEvent.ConfirmDeactivateAccount) },
        onDismiss = { onEvent(AccountHubEvent.DismissDeactivateAccount) },
    )
    LayerDialogs(state, onEvent)
}

// -- the accounts tab ----------------------------------------------------------------

@Composable
private fun ColumnScope.AccountsTab(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val canEdit = state.viewer.canActAsAccountant
    val stats = chart.stats

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        StatCard("Total codes", stats.total.toString(), hint = "across the chart", modifier = Modifier.weight(1f))
        StatCard("Headers", stats.headers.toString(), hint = "top-level groups", modifier = Modifier.weight(1f))
        StatCard("Nominals", stats.nominals.toString(), hint = "mid-level", modifier = Modifier.weight(1f))
        StatCard("Codes", stats.codes.toString(), hint = "postable leaves", modifier = Modifier.weight(1f))
        StatCard(
            "Active",
            stats.active.toString(),
            hint = "in use",
            modifier = Modifier.weight(1f),
            accent = ZillitTheme.colors.success,
        )
    }

    Toolbar(state, onEvent)

    if (chart.isEmpty) {
        EmptyAccounts(canEdit, onEvent)
        return
    }
    val forest = chart.forest
    if (forest.orphans.isNotEmpty() && chart.search.isBlank()) {
        // Surfaced rather than dropped. These are live codes whose parent
        // is inactive — still postable, and invisible on a screen meant to
        // list every code is worse than shown without their ancestry.
        ZillitNotice(
            text = "${forest.orphans.size} code(s) sit under an inactive parent and are listed at the end.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )
    }
    if (chart.loading && chart.accounts.isEmpty()) {
        ZillitSpinner()
        return
    }
    when (chart.mode) {
        ChartMode.Tree -> TreeView(state, onEvent, canEdit)
        ChartMode.Table -> TableView(state, onEvent, canEdit)
    }
}

/** Search, Show inactive, Tree | Table, Refresh, Expand all, Import Budget, New COA Entry — the web's toolbar. */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun Toolbar(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val canEdit = state.viewer.canActAsAccountant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchField(
            value = chart.search,
            onValueChange = { onEvent(AccountHubEvent.SearchChart(it)) },
            placeholder = "Search codes or names…",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitSwitch(
                checked = chart.showInactive,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleInactiveAccounts) },
                label = "Show inactive",
            )
            MonoChip(if (chart.showInactive) "ON" else "OFF", active = chart.showInactive)
        }
        ZillitSegmented(
            options = ChartMode.entries.map { ZillitTab(it.name, it.label) },
            activeId = chart.mode.name,
            onSelect = { name ->
                ChartMode.entries.firstOrNull { it.name == name }?.let { onEvent(AccountHubEvent.SetChartMode(it)) }
            },
        )
        ZillitIconButton(
            icon = ZillitIcons.Reload,
            contentDescription = "Refresh",
            onClick = { onEvent(AccountHubEvent.Refresh) },
        )
        if (chart.mode == ChartMode.Tree) {
            ZillitButton(
                text = if (chart.expandedAll) "Collapse all" else "Expand all",
                onClick = { onEvent(AccountHubEvent.ToggleExpandAll) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        Box(Modifier.weight(1f))
        if (canEdit && chart.view != ChartView.BalanceSheet) {
            ZillitButton(
                text = "Import Budget",
                onClick = {
                    onEvent(AccountHubEvent.Open(com.zillit.desktop.feature.accounthub.domain.HubArea.Budget))
                    onEvent(AccountHubEvent.OpenBudgetImport)
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Upload,
            )
        }
        if (canEdit) {
            ZillitButton(
                text = "New COA Entry",
                onClick = { onEvent(AccountHubEvent.OpenBulkAdd(null)) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

@Composable
private fun ColumnScope.EmptyAccounts(canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    ZillitEmptyState(
        title = "Your Chart of Accounts is empty",
        message = "Import a budget to build the chart from its lines, or build your codes manually from scratch.",
        icon = ZillitIcons.Ledger,
        action = {
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitButton(
                        text = "Import Budget",
                        onClick = {
                            onEvent(AccountHubEvent.Open(com.zillit.desktop.feature.accounthub.domain.HubArea.Budget))
                            onEvent(AccountHubEvent.OpenBudgetImport)
                        },
                        leadingIcon = ZillitIcons.Upload,
                    )
                    ZillitButton(
                        text = "Build from scratch",
                        onClick = { onEvent(AccountHubEvent.OpenBulkAdd(null)) },
                        variant = ButtonVariant.Secondary,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
            }
        },
    )
}

// -- tree view -------------------------------------------------------------------------

/** A node with the indent it should be drawn at. */
private data class ChartRow(val node: CoaNode, val depth: Int, val hasChildren: Boolean)

/**
 * The flat display list.
 *
 * A search replaces the tree entirely rather than filtering it: a match five
 * levels down would otherwise be hidden behind four collapsed ancestors, and
 * auto-expanding to reveal it loses whatever the user had open. Top-level
 * rows start open, as on the web.
 */
private fun rowsFor(chart: ChartState): List<ChartRow> {
    if (chart.search.isNotBlank()) {
        return chart.matches.map { ChartRow(CoaNode(it), depth = 0, hasChildren = false) }
    }
    val out = mutableListOf<ChartRow>()
    fun walk(nodes: List<CoaNode>, depth: Int) {
        nodes.forEach { node ->
            out += ChartRow(node, depth, node.children.isNotEmpty())
            // Top-level rows start open until the first fold, as on the web.
            val untouchedRoot = depth == 0 && !chart.expandedAll && chart.expanded.isEmpty()
            val open = node.id in chart.expanded || untouchedRoot
            if (open) walk(node.children, depth + 1)
        }
    }
    walk(chart.forest.roots, 0)
    walk(chart.forest.orphans, 0)
    return out
}

@Composable
private fun ColumnScope.TreeView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canEdit: Boolean) {
    val chart = state.chart
    val rows = rowsFor(chart)
    SubCard(padded = false, modifier = Modifier.weight(1f)) {
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty()) {
                FieldHint(
                    if (chart.search.isBlank()) "Nothing in this view." else "No code or name contains " +
                        "\"${chart.search}\".",
                    Modifier.padding(ZillitTheme.spacing.lg),
                )
            }
            rows.forEach { row -> TreeRow(row, chart, canEdit, onEvent) }
        }
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun TreeRow(row: ChartRow, chart: ChartState, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val account = row.node.account
    val open = row.node.id in chart.expanded || (row.depth == 0 && chart.expanded.isEmpty())
    val colors = ZillitTheme.colors
    HoverRow(
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md + (row.depth * INDENT).dp,
            end = ZillitTheme.spacing.md,
            top = 2.dp,
            bottom = 2.dp,
        ),
        onClick = if (row.hasChildren) ({ onEvent(AccountHubEvent.ToggleAccountExpanded(row.node.id)) }) else null,
        actions = { hovered ->
            if (canEdit && hovered) {
                if (account.lineType.childType != null) {
                    ZillitIconButton(
                        icon = ZillitIcons.Add,
                        contentDescription = "Add child",
                        onClick = { onEvent(AccountHubEvent.OpenBulkAdd(account)) },
                    )
                }
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit",
                    onClick = { onEvent(AccountHubEvent.ComposeAccount(editing = account)) },
                )
                if (account.isActive) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Deactivate",
                        onClick = { onEvent(AccountHubEvent.AskDeactivateAccount(account)) },
                        tint = colors.danger,
                    )
                }
            }
        },
    ) {
        // The indent guide the web draws down the left of each depth.
        repeat(row.depth) { Box(Modifier.width(1.dp).height(ROW_HEIGHT).background(colors.border)) }
        Box(Modifier.size(CHEVRON), contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                ZillitIconButton(
                    icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    contentDescription = if (open) "Collapse ${account.code}" else "Expand ${account.code}",
                    onClick = { onEvent(AccountHubEvent.ToggleAccountExpanded(row.node.id)) },
                    size = CHEVRON,
                )
            }
        }
        ZillitText(
            text = account.code.ifBlank { "—" },
            style = ZillitTheme.typography.numeric.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.width(CODE_COLUMN),
            maxLines = 1,
        )
        ZillitText(
            text = account.name.ifBlank { "Unnamed" },
            style = ZillitTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (account.lineType.label.isNotBlank()) Pill(account.lineType.label)
        if (account.isFromBudget) Pill("BUDGET", tone = StatusTone.Pending)
        StatusChips(account)
    }
}

/** The web's `StatusChips`: only the states worth a word — Inactive, Non-posting. */
@Composable
private fun StatusChips(account: CoaAccount) {
    if (!account.isActive) Pill("Inactive", tone = StatusTone.Neutral)
    if (!account.isPosting) Pill("Non-posting", tone = StatusTone.Progress)
}

// -- table view ------------------------------------------------------------------------

@Composable
private fun ColumnScope.TableView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, canEdit: Boolean) {
    val chart = state.chart
    val rows = chart.tableRows
    val colors = ZillitTheme.colors
    SubCard(padded = false, modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortHeader(ChartSortKey.Code, chart, onEvent, Modifier.width(CODE_COLUMN))
            SortHeader(ChartSortKey.Type, chart, onEvent, Modifier.width(TYPE_COLUMN))
            SortHeader(ChartSortKey.Name, chart, onEvent, Modifier.weight(1f))
            SortHeader(ChartSortKey.CostType, chart, onEvent, Modifier.width(COST_COLUMN))
            SortHeader(ChartSortKey.Status, chart, onEvent, Modifier.width(STATUS_COLUMN))
            Box(Modifier.width(ACTION_COLUMN))
        }
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (rows.isEmpty()) FieldHint("Nothing matched.", Modifier.padding(ZillitTheme.spacing.lg))
            rows.forEach { account -> TableRow(account, state, canEdit, onEvent) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldHint(if (chart.search.isBlank()) "${rows.size} rows" else "${rows.size} rows · filtered from " +
                "${chart.visibleAccounts.size}")
            Box(Modifier.weight(1f))
            FieldHint("Sorted by ${chart.sort.key.label.lowercase()} ${if (chart.sort.ascending) "↑" else "↓"}")
        }
    }
}

@Composable
private fun SortHeader(key: ChartSortKey, chart: ChartState, onEvent: (AccountHubEvent) -> Unit, modifier: Modifier) {
    val active = chart.sort.key == key
    Row(
        modifier = modifier.clickable { onEvent(AccountHubEvent.SortChart(key)) },
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        MonoLabel(key.label, color = if (active) ZillitTheme.colors.accentText else null)
        if (active) MonoLabel(if (chart.sort.ascending) "↑" else "↓", color = ZillitTheme.colors.accentText)
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun TableRow(
    account: CoaAccount,
    state: AccountHubUiState,
    canEdit: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val path = ChartOfAccounts.path(state.chart.accounts, account)
    HoverRow(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        actions = { hovered ->
            Row(
                modifier = Modifier.width(ACTION_COLUMN),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                if (canEdit && hovered) {
                    if (account.lineType.childType != null) {
                        ZillitIconButton(
                            icon = ZillitIcons.Add,
                            contentDescription = "Add child",
                            onClick = { onEvent(AccountHubEvent.OpenBulkAdd(account)) },
                        )
                    }
                    ZillitIconButton(
                        icon = ZillitIcons.Edit,
                        contentDescription = "Edit",
                        onClick = { onEvent(AccountHubEvent.ComposeAccount(editing = account)) },
                    )
                    if (account.isActive) {
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = "Deactivate",
                            onClick = { onEvent(AccountHubEvent.AskDeactivateAccount(account)) },
                            tint = colors.danger,
                        )
                    }
                }
            }
        },
    ) {
        ZillitText(
            text = account.code.ifBlank { "—" },
            style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(CODE_COLUMN),
            maxLines = 1,
        )
        Box(Modifier.width(TYPE_COLUMN)) { Pill(account.lineType.tagLabel) }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = account.name.ifBlank { "Unnamed" },
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (path.isNotEmpty()) FieldHint(path.joinToString(" › ") { it.code.ifBlank { it.name } })
        }
        Box(Modifier.width(COST_COLUMN)) {
            if (canEdit && !account.isFromBudget) {
                // Editable inline unless the row came from a budget, whose class the budget owns.
                ZillitSelect(
                    value = account.costType,
                    options = CoaCostType.entries,
                    onSelect = { onEvent(AccountHubEvent.SetAccountCostTypeInline(account.id, it)) },
                    label = { it.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ZillitText(
                    text = account.costType.label,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        Row(
            modifier = Modifier.width(STATUS_COLUMN),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            if (account.isActive && account.isPosting) FieldHint("Active") else StatusChips(account)
        }
    }
}

// -- the account form -----------------------------------------------------------------------

/**
 * The add / edit form — the web's `AccountFormModal`.
 *
 * On edit a manual row may be re-typed or re-parented — a structural change
 * the server re-walks — and a budget-imported row keeps its cost type. The
 * code is the natural key and stays put.
 */
@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
private fun AccountFormDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val form = state.chart.form

    ZillitDialogShell(
        title = form?.title.orEmpty(),
        visible = form != null,
        onDismiss = { onEvent(AccountHubEvent.DismissAccountForm) },
        icon = ZillitIcons.Ledger,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissAccountForm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (form?.isEdit == true) "Save" else "Create",
                onClick = { onEvent(AccountHubEvent.SaveAccount) },
                loading = form?.saving == true,
            )
        },
    ) {
        if (form == null) return@ZillitDialogShell
        val editing = form.editing

        FieldLabel("Line type", required = true)
        ZillitSelect(
            value = form.draft.lineType,
            options = CoaLineType.entries,
            onSelect = { onEvent(AccountHubEvent.SetAccountLineType(it)) },
            label = { it.tagLabel },
            modifier = Modifier.fillMaxWidth(),
        )
        ParentPicker(state, form, onEvent)

        ZillitTextField(
            value = form.draft.code,
            onValueChange = { onEvent(AccountHubEvent.SetAccountCode(it)) },
            label = "Nominal / Account code",
            helperText = if (form.isEdit) "The natural key — it cannot be changed once the account " +
                "exists." else "Digits, dots and dashes.",
            readOnly = form.isEdit,
        )
        ZillitTextField(
            value = if (form.isEdit) form.name else form.draft.name,
            onValueChange = { onEvent(AccountHubEvent.SetAccountName(it)) },
            label = "Display name",
        )
        FieldLabel("Cost type", required = true)
        if (editing?.isFromBudget == true) {
            ZillitText(text = editing.costType.label, style = ZillitTheme.typography.bodyMedium)
            FieldHint("Budget-imported rows are permanently classified as Expense.")
        } else {
            ZillitSelect(
                value = if (form.isEdit) form.costType else form.draft.costType,
                options = CoaCostType.entries,
                onSelect = { onEvent(AccountHubEvent.SetAccountCostType(it)) },
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitCheckbox(
            checked = if (form.isEdit) form.isPosting else form.draft.isPosting,
            onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountPosting) },
            label = "Posting — line items may code against this",
        )
        if (form.isEdit) {
            ZillitCheckbox(
                checked = form.isActive,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountActive) },
                label = "Active",
            )
            if (form.structureChanged) {
                ZillitNotice(
                    text = "Changing the level or parent moves every code beneath this one. The server re-walks " +
                        "the tree.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Warning,
                )
            }
        }

        val problem = if (form.isEdit) null else form.draft.validationError(state.chart.accounts)
        problem?.let { ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info) }
    }
}

@Composable
private fun ParentPicker(state: AccountHubUiState, form: AccountForm, onEvent: (AccountHubEvent) -> Unit) {
    val options = ChartOfAccounts.parentOptions(state.chart.accounts, form.draft.lineType).filter {
        it.id != form.editing?.id
    }
    if (form.draft.lineType == CoaLineType.Header) {
        FieldHint("A header sits at the top and has no parent.")
        return
    }
    HubSelect(
        value = options.firstOrNull { it.id == form.draft.parentId },
        options = options,
        label = { it.display },
        onSelect = { onEvent(AccountHubEvent.SetAccountParent(it?.id)) },
        placeholder = "Choose the ${form.draft.lineType.parentType?.tagLabel ?: "parent"} row this sits under…",
        fieldLabel = "Parent",
        clearable = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

// -- bulk add ("New COA Entry") -----------------------------------------------------------------

/**
 * The full-page grid — the web's `CoaBulkAddPage`.
 *
 * Every row autosaves two seconds after its last edit once it has a code; a
 * duplicate is marked rather than created; removing a saved row retires it.
 * Done flushes anything still mid-debounce, then returns to the chart.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun BulkAddPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val bulk = state.chart.bulk ?: return
    val colors = ZillitTheme.colors
    HubPage {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel("Chart of Accounts")
                ZillitText(
                    text = if (bulk.parent == null) "New COA Entry" else "Add under ${bulk.parent.display}",
                    style = ZillitTheme.typography.titleLarge,
                )
                FieldHint("Each row saves on its own, two seconds after you stop typing. Give a row a code and it " +
                    "is created; edit a saved row's code and it is re-created under the new one.")
            }
            if (bulk.saveLabel.isNotBlank()) {
                Pill(
                    bulk.saveLabel,
                    tone = when {
                        bulk.anySaving -> StatusTone.Progress
                        bulk.anyError -> StatusTone.Rejected
                        else -> StatusTone.Done
                    },
                    dot = true,
                )
            }
            ZillitButton(
                text = "Done",
                onClick = { onEvent(AccountHubEvent.FinishBulkAdd) },
                leadingIcon = ZillitIcons.Tick,
            )
        }
        SubCard(padded = false, modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MonoLabel("Line type", Modifier.width(BULK_TYPE))
                MonoLabel("Code", Modifier.width(CODE_COLUMN))
                MonoLabel("Cost type", Modifier.width(COST_COLUMN))
                MonoLabel("Display name", Modifier.weight(1f))
                MonoLabel("Active", Modifier.width(BULK_FLAG))
                MonoLabel("Posting", Modifier.width(BULK_FLAG))
                Box(Modifier.width(BULK_STATUS))
                Box(Modifier.width(CHEVRON))
            }
            ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                bulk.rows.forEach { row -> BulkRow(row, bulk.rows, state, onEvent) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                GhostAddButton("Add entry", onClick = { onEvent(AccountHubEvent.AddBulkRows(1)) })
                GhostAddButton(
                    "Add ${CoaBulk.BATCH_ROWS} rows",
                    onClick = { onEvent(AccountHubEvent.AddBulkRows(CoaBulk.BATCH_ROWS)) },
                )
            }
        }
    }
}

@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun BulkRow(
    row: CoaBulkRow,
    rows: List<CoaBulkRow>,
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    fun edit(next: CoaBulkRow) = onEvent(AccountHubEvent.EditBulkRow(next))
    val duplicate = CoaBulk.isDuplicate(row, rows, state.chart.accounts)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSelect(
            value = row.lineType,
            options = CoaLineType.entries,
            onSelect = { edit(row.copy(lineType = it)) },
            label = { it.tagLabel },
            enabled = !row.isSaved,
            modifier = Modifier.width(BULK_TYPE),
        )
        ZillitTextField(
            value = row.code,
            onValueChange = { text ->
                edit(row.copy(code = text.filter { it.isDigit() || it == '-' || it == '.' || it.isLetter() }))
            },
            placeholder = "Code",
            errorText = if (duplicate) "Already used" else null,
            modifier = Modifier.width(CODE_COLUMN),
        )
        ZillitSelect(
            value = row.costType,
            options = CoaCostType.entries,
            onSelect = { edit(row.copy(costType = it)) },
            label = { it.label },
            modifier = Modifier.width(COST_COLUMN),
        )
        ZillitTextField(
            value = row.name,
            onValueChange = { edit(row.copy(name = it)) },
            placeholder = "Display name",
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(BULK_FLAG), contentAlignment = Alignment.Center) {
            ZillitCheckbox(checked = row.isActive, onCheckedChange = { edit(row.copy(isActive = it)) })
        }
        Box(Modifier.width(BULK_FLAG), contentAlignment = Alignment.Center) {
            ZillitCheckbox(checked = row.isPosting, onCheckedChange = { edit(row.copy(isPosting = it)) })
        }
        Box(Modifier.width(BULK_STATUS)) {
            when (row.status) {
                CoaBulkStatus.Idle -> Unit
                CoaBulkStatus.Saving -> Pill("Saving…", tone = StatusTone.Progress)
                CoaBulkStatus.Saved -> Pill("Saved", tone = StatusTone.Done)
                CoaBulkStatus.Error -> Pill(row.error.ifBlank { "Couldn't save" }, tone = StatusTone.Rejected)
                CoaBulkStatus.Duplicate -> Pill("Duplicate code", tone = StatusTone.Rejected)
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Remove row",
            onClick = { onEvent(AccountHubEvent.RemoveBulkRow(row.localId)) },
        )
    }
}

// -- layers ------------------------------------------------------------------------------------

/**
 * The Layers tab — the web's `TrackingCodesTab`: analytical dimensions
 * (Locations, Episodes, Funding…) each with a colour, a prefix and a status,
 * and their codes beneath, all editable by an accountant.
 */
@Composable
private fun ColumnScope.LayersTab(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val canEdit = state.viewer.canActAsAccountant
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = "Layers — analytical dimensions", style = ZillitTheme.typography.titleSmall)
            FieldHint(
                "A layer is a tagging dimension beside the nominal chart — Locations, Episodes, Funding Source. A " +
                    "layer holds " +
                    "codes, and a line item can carry one code from each layer.",
            )
        }
        if (canEdit) ZillitButton(
            text = "New layer",
            onClick = { onEvent(AccountHubEvent.ComposeLayerSet(null)) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Add,
        )
    }
    if (chart.loading && chart.trackingSets.isEmpty()) {
        ZillitSpinner()
        return
    }
    if (chart.trackingSets.isEmpty()) {
        ZillitEmptyState(
            title = "No layers yet",
            // Named for where they come from: a production with none has not
            // set any up, which is not the same as this screen failing.
            message = "This project has no tracking dimensions configured.",
            action = { if (canEdit) ZillitButton(
                text = "New layer",
                onClick = { onEvent(AccountHubEvent.ComposeLayerSet(null)) },
                leadingIcon = ZillitIcons.Add,
            ) },
        )
        return
    }
    ZillitScrollColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        chart.trackingSets.forEachIndexed { index, set -> LayerCard(set, index, state, canEdit, onEvent) }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun LayerCard(
    set: com.zillit.desktop.feature.accounthub.domain.TrackingSet,
    index: Int,
    state: AccountHubUiState,
    canEdit: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val chart = state.chart
    val open = set.id in chart.openLayers || chart.openLayers.isEmpty() && index == 0
    val rows = chart.rows(set)
    val colors = ZillitTheme.colors
    val tint = parseHex(set.color.ifBlank { TrackingSets.colorFor(index) }) ?: colors.accent
    SubCard(padded = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(AccountHubEvent.ToggleLayerOpen(set.id)) }
                .padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = if (open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                contentDescription = if (open) "Collapse" else "Expand",
                onClick = { onEvent(AccountHubEvent.ToggleLayerOpen(set.id)) },
            )
            Box(Modifier.size(COLOUR_CHIP).clip(ZillitTheme.shapes.medium).background(tint))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(text = set.name.ifBlank { "Unnamed layer" }, style = ZillitTheme.typography.titleSmall)
                    MonoChip("${set.nodes.size} code${if (set.nodes.size == 1) "" else "s"}")
                    if (!set.isActive) Pill("Inactive")
                }
                FieldHint("Prefix ${set.shownPrefix.ifBlank { "—" }}")
            }
            if (canEdit) {
                ZillitButton(
                    text = "New code",
                    onClick = { onEvent(AccountHubEvent.ComposeLayerNode(set.id, null)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit set",
                    onClick = { onEvent(AccountHubEvent.ComposeLayerSet(set)) },
                )
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete set",
                    onClick = { onEvent(AccountHubEvent.AskDeleteLayer(LayerDelete.WholeSet(set))) },
                    tint = colors.danger,
                )
            }
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            if (rows.isEmpty()) {
                FieldHint("No codes yet — add the first one.", Modifier.padding(ZillitTheme.spacing.lg))
            }
            rows.forEach { row ->
                HoverRow(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
                    actions = { hovered ->
                        if (canEdit && hovered) {
                            ZillitIconButton(
                                icon = ZillitIcons.Edit,
                                contentDescription = "Edit",
                                onClick = { onEvent(AccountHubEvent.ComposeLayerNode(set.id, row.node)) },
                            )
                            ZillitIconButton(
                                icon = ZillitIcons.Trash,
                                contentDescription = "Delete",
                                onClick = { onEvent(AccountHubEvent.AskDeleteLayer(LayerDelete.OneNode(
                                    set.id,
                                    row.node,
                                ))) },
                                tint = colors.danger,
                            )
                        }
                    },
                ) {
                    Box(Modifier.padding(start = (row.depth * INDENT).dp).size(8.dp).clip(CircleShape).background(tint))
                    ZillitText(
                        text = row.node.code,
                        style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.width(CODE_COLUMN),
                        maxLines = 1,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ZillitText(text = row.node.name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
                        if (row.node.description.isNotBlank()) FieldHint(row.node.description)
                    }
                    if (row.orphaned) Pill("No parent", tone = StatusTone.Pending)
                    if (!row.node.isActive) Pill("Inactive")
                }
            }
        }
    }
}

/** `#FB923C` → a colour; anything else is null and the palette's accent stands in. */
private fun parseHex(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != HEX_LENGTH) return null
    val value = clean.toLongOrNull(HEX_RADIX) ?: return null
    return Color((value or ALPHA_MASK).toInt())
}

/** The set editor, the code editor, the delete confirmation and the "in use" refusal. */
@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun LayerDialogs(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart
    val setDraft = chart.layerSetDraft
    ZillitDialogShell(
        title = if (setDraft?.isNew == true) "New layer" else "Edit \"${setDraft?.set?.name?.ifBlank { "layer" }}\"",
        icon = ZillitIcons.Grid,
        visible = setDraft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerSet) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissLayerSet) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (setDraft?.isNew == true) "Create" else "Save",
                onClick = { onEvent(AccountHubEvent.SaveLayerSet) },
                loading = setDraft?.saving == true,
            )
        },
    ) {
        val set = setDraft?.set ?: return@ZillitDialogShell
        fun update(next: com.zillit.desktop.feature.accounthub.domain.TrackingSet) =
            onEvent(AccountHubEvent.EditLayerSet(next))
        ZillitTextField(
            value = set.name,
            onValueChange = { update(set.copy(name = it)) },
            label = "Name",
            placeholder = "e.g. Locations, Episodes, Funding Source",
        )
        ZillitTextField(
            value = set.prefix,
            onValueChange = { update(set.copy(prefix = TrackingSets.normalisePrefix(it))) },
            label = "Prefix",
            placeholder = "Auto",
            helperText = "2–10 letters/digits. Used on every code (LOC-EUR-LON). " +
                "Leave blank to auto-derive from name.",
        )
        FieldLabel("Colour")
        FieldHint("Drives the chip on every line-item picker.")
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            TrackingSets.DEFAULT_COLORS.forEach { hex ->
                val chosen = set.color.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(COLOUR_CHIP)
                        .clip(CircleShape)
                        .background(parseHex(hex) ?: ZillitTheme.colors.accent)
                        .border(if (chosen) 3.dp else 0.dp, ZillitTheme.colors.textPrimary, CircleShape)
                        .clickable { update(set.copy(color = hex)) },
                )
            }
        }
        FieldLabel("Status")
        ZillitSwitch(
            checked = set.isActive,
            onCheckedChange = { update(set.copy(isActive = it)) },
            label = if (set.isActive) "Active" else "Inactive",
        )
    }

    val nodeDraft = chart.layerNodeDraft
    val parentSet = chart.trackingSets.firstOrNull { it.id == nodeDraft?.node?.setId }
    ZillitDialogShell(
        title = if (nodeDraft?.isNew == true) "New code" else "Edit code",
        subtitle = parentSet?.name,
        icon = ZillitIcons.Grid,
        visible = nodeDraft != null,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerNode) },
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.DismissLayerNode) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (nodeDraft?.isNew == true) "Add code" else "Save",
                onClick = { onEvent(AccountHubEvent.SaveLayerNode) },
                loading = nodeDraft?.saving == true,
            )
        },
    ) {
        val node = nodeDraft?.node ?: return@ZillitDialogShell
        fun update(next: com.zillit.desktop.feature.accounthub.domain.TrackingNode) =
            onEvent(AccountHubEvent.EditLayerNode(next))
        ZillitTextField(
            value = node.code,
            onValueChange = { update(node.copy(code = it.uppercase())) },
            label = "Code",
            helperText = "Convention: ${parentSet?.shownPrefix?.ifBlank { "PFX" } ?: "PFX"}-…",
        )
        ZillitTextField(
            value = node.name,
            onValueChange = { update(node.copy(name = it)) },
            label = "Label",
            placeholder = "London",
        )
        ZillitTextField(
            value = node.description,
            onValueChange = { update(node.copy(description = it)) },
            label = "Description",
            placeholder = "Soundstage hire + studio support",
            helperText = "Optional notes shown on hover in the picker.",
        )
        FieldLabel("Status")
        ZillitSwitch(
            checked = node.isActive,
            onCheckedChange = { update(node.copy(isActive = it)) },
            label = if (node.isActive) "Active" else "Inactive",
        )
    }

    val delete = chart.layerDelete
    HubConfirmDialog(
        visible = delete != null,
        title = "Confirm delete",
        message = when (delete) {
            is LayerDelete.WholeSet -> {
                val count = delete.set.nodes.size
                if (count > 0) "Delete \"${delete.set.name}\" and its $count code${if (count == 1) "" else "s"}? " +
                    "This cannot be undone." else "Delete \"${delete.set.name}\"?"
            }
            is LayerDelete.OneNode -> "Delete \"${delete.node.code}\"?"
            null -> ""
        },
        confirmLabel = "Delete",
        onConfirm = { onEvent(AccountHubEvent.ConfirmDeleteLayer) },
        onDismiss = { onEvent(AccountHubEvent.DismissDeleteLayer) },
    )
    ZillitDialogShell(
        title = "In use",
        icon = ZillitIcons.Warning,
        visible = chart.layerInUse != null,
        onDismiss = { onEvent(AccountHubEvent.DismissLayerInUse) },
        actions = { ZillitButton(text = "OK", onClick = { onEvent(AccountHubEvent.DismissLayerInUse) }) },
    ) {
        ZillitText(text = chart.layerInUse.orEmpty(), style = ZillitTheme.typography.bodyMedium)
        FieldHint("Line items still carry this code. Deactivate it instead, or re-code them first.")
    }
}

private val SEARCH_WIDTH = 280.dp
private val CODE_COLUMN = 150.dp
private val TYPE_COLUMN = 90.dp
private val COST_COLUMN = 140.dp
private val STATUS_COLUMN = 120.dp
private val ACTION_COLUMN = 110.dp
private val CHEVRON = 22.dp
private val ROW_HEIGHT = 20.dp
private val COLOUR_CHIP = 24.dp
private val BULK_TYPE = 130.dp
private val BULK_FLAG = 60.dp
private val BULK_STATUS = 130.dp
private const val INDENT = 18
private const val HEX_LENGTH = 6
private const val HEX_RADIX = 16
private const val ALPHA_MASK = 0xFF000000L
