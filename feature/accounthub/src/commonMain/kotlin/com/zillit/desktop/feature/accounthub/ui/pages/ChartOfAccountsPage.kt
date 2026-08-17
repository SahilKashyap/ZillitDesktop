package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.CoaNode
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.ChartView
import com.zillit.desktop.feature.accounthub.ui.HubPage

/**
 * The Chart of Accounts.
 *
 * ## One tree, three views
 *
 * Cost accounts and balance-sheet codes are the same rows filtered by class,
 * not two resources — they share one fetch, and a code that changes class moves
 * between the views without a reload.
 *
 * ## Rows are indented, not nested
 *
 * The tree is flattened for display with a depth per row, because a table gives
 * the codes a column alignment that a nested layout cannot. Expanding collapses
 * subtrees out of that flat list rather than unmounting anything.
 */
@Suppress("LongMethod") // Header, tabs, filters and one table: read as a single screen.
@Composable
fun ChartOfAccountsPage(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val chart = state.chart

    HubPage {
        ZillitPageHeader(
            eyebrow = "Configuration",
            title = "Chart of Accounts",
            description = "The nominal taxonomy every line item codes against — purchase " +
                "orders, invoices, card receipts, cash claims and payroll.",
            actions = {
                if (state.viewer.canActAsAccountant) {
                    ZillitButton(
                        text = "New account",
                        onClick = { onEvent(AccountHubEvent.ComposeAccount()) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                    )
                }
                ZillitButton(
                    text = "Refresh",
                    onClick = { onEvent(AccountHubEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = chart.loading,
                )
            },
        )

        ZillitTabStrip(
            tabs = ChartView.entries.map { ZillitTab(it.slug, it.label) },
            activeId = chart.view.slug,
            onSelect = { slug ->
                ChartView.entries.firstOrNull { it.slug == slug }
                    ?.let { onEvent(AccountHubEvent.SwitchChartView(it)) }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = chart.search,
                onValueChange = { onEvent(AccountHubEvent.SearchChart(it)) },
                placeholder = "Search code or name",
                modifier = Modifier.width(SEARCH_WIDTH.dp),
            )
            ZillitCheckbox(
                checked = chart.showInactive,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleInactiveAccounts) },
                label = "Show inactive",
            )
        }

        if (!state.viewer.canActAsAccountant) {
            // Named specifically. The rest of the console *is* editable by an
            // admin, so a silently read-only screen here reads as a bug rather
            // than as the rule it is.
            ZillitNotice(
                text = "The chart is read-only for you — the service restricts changes to the " +
                    "accounts department, and an admin is not exempt.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        }

        if (chart.view == ChartView.Layers) {
            LayersNotice()
            return@HubPage
        }

        val forest = chart.forest
        if (forest.orphans.isNotEmpty()) {
            // Surfaced rather than dropped. These are live codes whose parent
            // is inactive — still postable, and invisible on a screen meant to
            // list every code is worse than shown without their ancestry.
            ZillitNotice(
                text = "${forest.orphans.size} code(s) sit under an inactive parent and are " +
                    "listed at the end.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Warning,
            )
        }

        ZillitSectionCard(
            title = chart.view.label,
            icon = ZillitIcons.Ledger,
            padded = false,
            modifier = Modifier.weight(1f),
        ) {
            ZillitDataTable(
                rows = rowsFor(chart),
                key = { it.node.id },
                loading = chart.loading,
                columns = chartColumns(state, onEvent),
                emptyTitle = if (chart.search.isBlank()) "No accounts yet" else "Nothing matched",
                emptyMessage = if (chart.search.isBlank()) {
                    "The chart is built from headers down to codes. Start with a header."
                } else {
                    "No code or name contains \"${chart.search}\"."
                },
            )
        }
    }

    AccountFormDialog(state, onEvent)
}

/** A node with the indent it should be drawn at. */
private data class ChartRow(val node: CoaNode, val depth: Int, val hasChildren: Boolean)

/**
 * The flat display list.
 *
 * A search replaces the tree entirely rather than filtering it: a match five
 * levels down would otherwise be hidden behind four collapsed ancestors, and
 * auto-expanding to reveal it loses whatever the user had open.
 */
private fun rowsFor(chart: com.zillit.desktop.feature.accounthub.ui.ChartState): List<ChartRow> {
    if (chart.search.isNotBlank()) {
        return chart.matches.map { ChartRow(CoaNode(it), depth = 0, hasChildren = false) }
    }
    val out = mutableListOf<ChartRow>()
    fun walk(nodes: List<CoaNode>, depth: Int) {
        nodes.forEach { node ->
            out += ChartRow(node, depth, node.children.isNotEmpty())
            if (node.id in chart.expanded) walk(node.children, depth + 1)
        }
    }
    walk(chart.forest.roots, 0)
    walk(chart.forest.orphans, 0)
    return out
}

@Suppress("LongMethod") // A table of columns; splitting it separates each from its width.
private fun chartColumns(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
): List<TableColumn<ChartRow>> = listOf(
    TableColumn(
        header = "Code",
        width = ColumnWidth.Fixed(CODE_COLUMN.dp),
        cell = { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = (row.depth * INDENT).dp),
            ) {
                if (row.hasChildren) {
                    ZillitIconButton(
                        icon = ZillitIcons.ChevronDown,
                        contentDescription = "Expand ${row.node.account.code}",
                        onClick = { onEvent(AccountHubEvent.ToggleAccountExpanded(row.node.id)) },
                    )
                }
                ZillitText(
                    text = row.node.account.code.ifBlank { "—" },
                    style = ZillitTheme.typography.numeric,
                    maxLines = 1,
                )
            }
        },
    ),
    TableColumn(
        header = "Name",
        width = ColumnWidth.Weight(NAME_WEIGHT),
        cell = { row ->
            Column {
                ZillitText(text = row.node.account.name.ifBlank { "Unnamed" }, maxLines = 1)
                if (!row.node.account.isPosting) {
                    // A code that exists but cannot be coded against. Worth
                    // saying: it looks identical to a usable one otherwise, and
                    // people raise a purchase order against it and wonder why.
                    ZillitText(
                        text = "Not postable",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        },
    ),
    TableColumn(
        header = "Type",
        width = ColumnWidth.Fixed(TYPE_COLUMN.dp),
        cell = { row ->
            ZillitStatusPill(
                label = row.node.account.lineType.tagLabel,
                tone = StatusTone.Neutral,
            )
        },
    ),
    TableColumn(
        header = "Class",
        width = ColumnWidth.Fixed(TYPE_COLUMN.dp),
        cell = { row -> ZillitText(text = row.node.account.costType.label, maxLines = 1) },
    ),
    TableColumn(
        header = "",
        width = ColumnWidth.Fixed(ACTION_COLUMN.dp),
        cell = { row ->
            if (!state.viewer.canActAsAccountant) return@TableColumn
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                ZillitIconButton(
                    icon = ZillitIcons.Add,
                    contentDescription = "Add under ${row.node.account.code}",
                    onClick = {
                        onEvent(AccountHubEvent.ComposeAccount(parent = row.node.account))
                    },
                    enabled = row.node.account.lineType.childType != null,
                )
                ZillitIconButton(
                    icon = ZillitIcons.Edit,
                    contentDescription = "Edit ${row.node.account.code}",
                    onClick = {
                        onEvent(AccountHubEvent.ComposeAccount(editing = row.node.account))
                    },
                )
                if (row.node.account.isActive) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = "Deactivate ${row.node.account.code}",
                        onClick = { onEvent(AccountHubEvent.DeactivateAccount(row.node.id)) },
                    )
                }
            }
        },
    ),
)

@Composable
private fun LayersNotice() {
    ZillitNotice(
        text = "Layers are the analytical dimensions that sit beside the nominal chart — " +
            "locations, episodes, units. Reading them is wired; editing them is not in " +
            "this build.",
        tone = StatusTone.Neutral,
        icon = ZillitIcons.Info,
    )
}

/**
 * The add / edit form.
 *
 * Edit shows fewer fields than add on purpose: the code, the line type and the
 * parent are immutable once the row exists, because changing any of them
 * cascades through every descendant's breadcrumb and the server does not do it
 * in place. The workflow for a structural change is deactivate-and-recreate,
 * and the form says so rather than offering disabled fields.
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

        if (form.isEdit) {
            ZillitText(
                text = "${form.editing?.code} · ${form.editing?.lineType?.tagLabel}",
                style = ZillitTheme.typography.numeric,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitNotice(
                text = "The code, level and parent cannot change — they are the row's place " +
                    "in the tree. Deactivate and create a new code instead.",
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
            )
        } else {
            AccountStructureFields(state, form, onEvent)
        }

        ZillitTextField(
            value = if (form.isEdit) form.name else form.draft.name,
            onValueChange = { onEvent(AccountHubEvent.SetAccountName(it)) },
            label = "Name",
        )
        ZillitSelect(
            value = if (form.isEdit) form.costType else form.draft.costType,
            options = CoaCostType.entries,
            onSelect = { onEvent(AccountHubEvent.SetAccountCostType(it)) },
            label = { it.label },
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitCheckbox(
            checked = if (form.isEdit) form.isPosting else form.draft.isPosting,
            onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountPosting) },
            label = "Line items may code against this",
        )
        if (form.isEdit) {
            ZillitCheckbox(
                checked = form.isActive,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountActive) },
                label = "Active",
            )
        }

        val problem = if (form.isEdit) null else form.draft.validationError(state.chart.accounts)
        problem?.let {
            ZillitNotice(text = it, tone = StatusTone.Pending, icon = ZillitIcons.Info)
        }
    }
}

@Composable
private fun AccountStructureFields(
    state: AccountHubUiState,
    form: com.zillit.desktop.feature.accounthub.ui.AccountForm,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitTextField(
        value = form.draft.code,
        onValueChange = { onEvent(AccountHubEvent.SetAccountCode(it)) },
        label = "Code",
        helperText = "The natural key. It cannot be changed once the account exists.",
    )
    ZillitSelect(
        value = form.draft.lineType,
        options = CoaLineType.entries,
        onSelect = { onEvent(AccountHubEvent.SetAccountLineType(it)) },
        label = { it.tagLabel },
        modifier = Modifier.fillMaxWidth(),
    )

    val options = ChartOfAccounts.parentOptions(state.chart.accounts, form.draft.lineType)
    if (options.isNotEmpty()) {
        val selected = options.firstOrNull { it.id == form.draft.parentId } ?: NO_PARENT
        ZillitSelect(
            value = selected,
            options = listOf(NO_PARENT) + options,
            onSelect = { onEvent(AccountHubEvent.SetAccountParent(it.id.takeIf(String::isNotBlank))) },
            label = { if (it.id.isBlank()) "Choose a parent…" else it.display },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A sentinel so the parent picker can render "none chosen" without a nullable value. */
private val NO_PARENT = CoaAccount(id = "", name = "Choose a parent…")

private const val SEARCH_WIDTH = 280
private const val CODE_COLUMN = 220
private const val TYPE_COLUMN = 110
private const val ACTION_COLUMN = 130
private const val NAME_WEIGHT = 2f
private const val INDENT = 18
