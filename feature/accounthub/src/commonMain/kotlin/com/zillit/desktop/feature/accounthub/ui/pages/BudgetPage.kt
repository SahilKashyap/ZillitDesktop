package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BudgetFigures
import com.zillit.desktop.feature.accounthub.domain.BudgetItem
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.asTree
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill

/**
 * The production's budget, by version — the web's `BudgetModule` / `BudgetsTab`.
 *
 * Version cards on the left: the version, a lock on Live and Archived ones,
 * the name, the total, when it was made and from which file. The chosen
 * version on the right: Account, Name, Amount and an allocation bar for every
 * line, as a tree whose top level starts open, each open group closed by its
 * own subtotal, lines whose parent is missing set apart, and the grand total
 * pinned under it all.
 *
 * Read-only, as on the web: a version is created by importing a budget file,
 * and Live and Archived versions cannot be edited at all.
 */
@Composable
fun BudgetPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    /** Whether the host wired storage; false hides the import. */
    canImport: Boolean = false,
    canOpenDocuments: Boolean = false,
) {
    val budget = state.budget
    val canEdit = canImport && state.viewer.canEdit

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Budget",
            description = "Versioned project budgets that hang off the Chart of Accounts and drive Cost Report. " +
                "Import a budget file (PDF / Excel) to extract every code + amount as a draft version.",
        )
        when {
            budget.loading && budget.versions.isEmpty() -> LoadingPanel("Loading budgets…", Modifier.weight(1f))
            budget.versions.isEmpty() -> EmptyBudgets(canEdit, onEvent)
            else -> {
                VersionsTitle(state, canEdit, onEvent)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // A narrow window gives the lines the room, not the cards.
                    val listWidth = if (maxWidth < NARROW_PAGE) NARROW_VERSIONS_WIDTH else VERSIONS_WIDTH
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
                    ) {
                        VersionList(state, onEvent, listWidth)
                        val selected = budget.selected
                        if (selected == null) {
                            SelectPrompt(Modifier.weight(1f))
                        } else {
                            VersionDetail(
                                state = state,
                                version = selected,
                                canOpenDocuments = canOpenDocuments,
                                onEvent = onEvent,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }

    BudgetImportDialog(state, onEvent)
}

/** "Budget Versions · 3 versions · 1 LIVE", a refresh, and the import. */
@Composable
private fun VersionsTitle(state: AccountHubUiState, canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val versions = state.budget.versions
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            text = "Budget Versions",
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        ZillitText(
            text = "${versions.size} version${if (versions.size == 1) "" else "s"}" +
                if (versions.any { it.status == BudgetStatus.Live }) " · 1 LIVE" else "",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitIconButton(
            icon = ZillitIcons.Reload,
            contentDescription = "Refresh",
            onClick = { onEvent(AccountHubEvent.Refresh) },
        )
        if (state.budget.loading) ZillitSpinner(size = SMALL_SPINNER)
        Spacer(Modifier.weight(1f))
        if (canEdit) {
            ZillitButton(
                text = "Import Budget",
                onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                leadingIcon = ZillitIcons.Upload,
            )
        }
    }
}

@Composable
private fun VersionList(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit, width: Dp) {
    val budget = state.budget
    ZillitScrollColumn(
        modifier = Modifier.width(width).fillMaxHeight(),
        // Clear of the scroll rail, which otherwise sits over the cards' edge.
        contentPadding = PaddingValues(end = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        budget.versions.forEach { version ->
            VersionCard(version, version.id == budget.selectedId, state.budgetSymbol(version)) {
                onEvent(AccountHubEvent.SelectBudgetVersion(version.id))
            }
        }
    }
}

/** One version — the web's `VersionCard`, with the selected one marked by an accent rail. */
@Composable
private fun VersionCard(version: BudgetVersion, selected: Boolean, symbol: String, onSelect: () -> Unit) {
    val colors = ZillitTheme.colors
    val shape = ZillitTheme.shapes.large
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent.copy(alpha = SELECTED_RING) else colors.border, shape)
            .clickable(onClick = onSelect),
    ) {
        Box(Modifier.width(RAIL).fillMaxHeight().background(if (selected) colors.accent else Color.Transparent))
        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                VersionChip(version.version)
                if (version.status.isLocked) {
                    ZillitIcon(
                        icon = ZillitIcons.Lock,
                        contentDescription = "Read-only",
                        tint = colors.textMuted,
                        size = LOCK,
                    )
                }
                Spacer(Modifier.weight(1f))
                StatusPill(version.status)
            }
            ZillitText(
                text = version.name.ifBlank { "Untitled budget" },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ZillitText(
                text = BudgetFigures.money(version.total, symbol),
                style = ZillitTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            VersionStamp(version)
        }
    }
}

/** "12 Sep, 14:05 · budget.xlsx" — when the version was made, and from what. */
@Composable
private fun VersionStamp(version: BudgetVersion) {
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(text = BudgetFigures.stamp(version.createdAtMillis), style = META, color = colors.textMuted)
        if (version.sourceFileName.isNotBlank()) {
            Box(Modifier.size(DOT).clip(CircleShape).background(colors.textMuted.copy(alpha = HALF)))
            ZillitText(
                text = version.sourceFileName,
                style = META,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The version label in a small monospace tag — `v3`. */
@Composable
private fun VersionChip(version: String, bordered: Boolean = false) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (bordered) colors.surface else colors.surfaceSunken)
            .then(if (bordered) Modifier.border(1.dp, colors.border, ZillitTheme.shapes.small) else Modifier)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = version.ifBlank { "—" },
            style = ZillitTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusPill(status: BudgetStatus) {
    Pill(
        status.label.uppercase(),
        tone = when (status) {
            BudgetStatus.Live -> StatusTone.Done
            BudgetStatus.Approved -> StatusTone.Progress
            BudgetStatus.Draft -> StatusTone.Pending
            BudgetStatus.Archived -> StatusTone.Neutral
        },
        dot = status == BudgetStatus.Live,
    )
}

/** The chosen version: its header, the column heads, the tree, and the grand total. */
@Composable
private fun VersionDetail(
    state: AccountHubUiState,
    version: BudgetVersion,
    canOpenDocuments: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val budget = state.budget
    val symbol = state.budgetSymbol(version)
    val tree = remember(budget.lines) { budget.lines.asTree() }
    val items = remember(tree, budget.openGroups) { tree.items(budget.openGroups) }
    BoxWithConstraints(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        val columns = BudgetColumns.forWidth(maxWidth)
        Column(modifier = Modifier.fillMaxSize()) {
            DetailHeader(state, version, symbol, canOpenDocuments, onEvent)
            Divider()
            ColumnHeads(columns)
            Divider()
            when {
                budget.linesLoading && tree.isEmpty -> LoadingPanel("Loading budget lines…", Modifier.weight(1f))
                tree.isEmpty -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    FieldHint("This budget has no line items yet.")
                }
                else -> ZillitLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is BudgetItem.Line -> LineRow(item, version.total, symbol, columns) {
                                onEvent(AccountHubEvent.ToggleBudgetGroup(it))
                            }
                            is BudgetItem.Subtotal -> SubtotalRow(item, symbol, columns)
                            is BudgetItem.OrphanHeading -> OrphanHeading(item.count)
                        }
                    }
                }
            }
            if (!tree.isEmpty) GrandTotal(BudgetFigures.moneyOrDash(tree.total, symbol), columns)
        }
    }
}

/**
 * The tree's fixed column widths for the room the pane has — the web's
 * `120px 1fr 140px 200px`, narrowed rather than letting the Name column be
 * squeezed to nothing, and dropping the allocation column when even that is
 * not enough.
 */
private data class BudgetColumns(val code: Dp, val amount: Dp, val allocation: Dp?) {
    companion object {
        fun forWidth(width: Dp): BudgetColumns = when {
            width >= WIDE_DETAIL -> BudgetColumns(code = 150.dp, amount = 130.dp, allocation = 170.dp)
            width >= MEDIUM_DETAIL -> BudgetColumns(code = 130.dp, amount = 120.dp, allocation = 120.dp)
            else -> BudgetColumns(code = 110.dp, amount = 110.dp, allocation = null)
        }
    }
}

@Composable
private fun DetailHeader(
    state: AccountHubUiState,
    version: BudgetVersion,
    symbol: String,
    canOpenDocuments: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken.copy(alpha = HEADER_TINT))
            .padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        VersionChip(version.version, bordered = true)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = version.name.ifBlank { "Untitled budget" },
                    style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusPill(version.status)
            }
            if (version.sourceFileName.isNotBlank()) ImportedFrom(version.sourceFileName)
            if (version.status.isLocked) {
                FieldHint(
                    "${version.status.label} versions are read-only — import a new version to change this budget.",
                )
            }
        }
        if (canOpenDocuments && version.attachment != null) {
            ZillitButton(
                text = "View file",
                onClick = { onEvent(AccountHubEvent.OpenBudgetFile) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
                loading = state.budget.openingFile,
            )
        }
        HeaderTotal(BudgetFigures.money(version.total, symbol))
    }
}

@Composable
private fun ImportedFrom(fileName: String) {
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = colors.textMuted, size = SMALL_ICON)
        ZillitText(
            text = "Imported from $fileName",
            style = META,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The server's total for the version, set off by a rule. */
@Composable
private fun HeaderTotal(total: String) {
    Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(ZillitTheme.colors.border))
        Column(
            modifier = Modifier.padding(start = ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.End,
        ) {
            MonoLabel("Total")
            ZillitText(
                text = total,
                style = ZillitTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ColumnHeads(columns: BudgetColumns) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel("Account", Modifier.width(columns.code))
        MonoLabel("Name", Modifier.weight(1f))
        Box(Modifier.width(columns.amount), contentAlignment = Alignment.CenterEnd) { MonoLabel("Amount") }
        columns.allocation?.let { MonoLabel("Allocation", Modifier.width(it)) }
    }
}

/**
 * One budget line — the web's `BudgetRow`. A row with children opens and
 * closes on click; its code sits behind a chevron, a leaf's behind a dot.
 */
@Suppress("LongMethod") // A row, read left to right; the order is the reading order.
@Composable
private fun LineRow(
    item: BudgetItem.Line,
    versionTotal: Double,
    symbol: String,
    columns: BudgetColumns,
    onToggle: (String) -> Unit,
) {
    val colors = ZillitTheme.colors
    val node = item.node
    val line = node.line
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val header = line.lineType == CoaLineType.Header
    val section = line.lineType == CoaLineType.Section
    val total = line.shownTotal
    val share = BudgetFigures.share(total, versionTotal)
    val background = when {
        hovered -> colors.surfaceHover
        header -> colors.accent.copy(alpha = HEADER_WASH)
        section -> colors.accent.copy(alpha = SECTION_WASH)
        else -> Color.Transparent
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .hoverable(interaction)
                .then(
                    if (node.hasChildren) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = if (item.open) "Collapse" else "Expand",
                        ) { onToggle(node.id) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = ROW_PADDING, vertical = ROW_VERTICAL),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CodeCell(item, columns.code)
            ZillitText(
                text = if (header) line.nameLabel.uppercase() else line.nameLabel,
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontWeight = when {
                        header -> FontWeight.Bold
                        section -> FontWeight.SemiBold
                        else -> FontWeight.Medium
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(columns.amount), contentAlignment = Alignment.CenterEnd) {
                ZillitText(
                    text = BudgetFigures.moneyOrDash(total, symbol),
                    style = CODE.copy(fontWeight = if (header) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (total == 0.0) colors.textMuted else colors.textPrimary,
                    maxLines = 1,
                )
            }
            columns.allocation?.let { AllocationCell(share, line.lineType, it) }
        }
        Divider()
    }
}

/** The code behind a chevron for a group, or behind a dot for a leaf, indented by depth. */
@Composable
private fun CodeCell(item: BudgetItem.Line, width: Dp) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.width(width).padding(start = INDENT * item.depth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(Modifier.size(TOGGLE), contentAlignment = Alignment.Center) {
            if (item.node.hasChildren) {
                ZillitIcon(
                    icon = if (item.open) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    tint = colors.textSecondary,
                    size = SMALL_ICON,
                )
            } else {
                Box(Modifier.size(LEAF_DOT).clip(CircleShape).background(colors.borderStrong))
            }
        }
        ZillitText(
            text = item.node.line.codeLabel,
            style = CODE,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The line's share of the version: a bar in the accent — strongest for a header — and the percentage. */
@Composable
private fun AllocationCell(share: Double, type: CoaLineType, width: Dp) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.width(width),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(BAR_HEIGHT)
                .clip(ZillitTheme.shapes.pill)
                .background(colors.surfaceSunken),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(BudgetFigures.bar(share))
                    .clip(ZillitTheme.shapes.pill)
                    .background(
                        when (type) {
                            CoaLineType.Header -> colors.accent
                            CoaLineType.Section -> colors.accent.copy(alpha = SECTION_BAR)
                            else -> colors.accent.copy(alpha = LEAF_BAR)
                        },
                    ),
            )
        }
        ZillitText(
            text = BudgetFigures.percent(share),
            style = META,
            color = colors.textMuted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(PERCENT_WIDTH),
        )
    }
}

/** "TOTAL · CAMERA" — closes an open group, after its last child. */
@Composable
private fun SubtotalRow(item: BudgetItem.Subtotal, symbol: String, columns: BudgetColumns) {
    val colors = ZillitTheme.colors
    val line = item.node.line
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken.copy(alpha = HEADER_TINT))
                .padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(columns.code))
            ZillitText(
                text = "Total · ${line.nameLabel}".uppercase(),
                style = TRACKED_LABEL,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = INDENT * (item.depth + 1)),
            )
            Box(Modifier.widthIn(min = columns.amount), contentAlignment = Alignment.CenterEnd) {
                ZillitText(
                    text = BudgetFigures.moneyOrDash(line.shownTotal, symbol),
                    style = CODE.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
            }
            columns.allocation?.let { Spacer(Modifier.width(it)) }
        }
        Divider()
    }
}

/** The band above lines whose parent is not in this version — shown, never dropped. */
@Composable
private fun OrphanHeading(count: Int) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.warningSoft)
                .padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(icon = ZillitIcons.Warning, tint = colors.warning, size = SMALL_ICON)
            ZillitText(
                text = "ORPHANED ($count)",
                style = TRACKED_LABEL,
                color = colors.warning,
            )
            FieldHint("Lines whose parent is not in this version.")
        }
        Divider()
    }
}

/** The whole budget, pinned under the tree. */
@Composable
private fun GrandTotal(total: String, columns: BudgetColumns) {
    val colors = ZillitTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.borderStrong))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.accentSoft)
                .padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(columns.code))
            ZillitText(
                text = "TOTAL",
                style = TRACKED_LABEL.copy(
                    fontSize = ZillitTheme.typography.label.fontSize,
                    fontWeight = FontWeight.ExtraBold,
                ),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.widthIn(min = columns.amount), contentAlignment = Alignment.CenterEnd) {
                ZillitText(
                    text = total,
                    style = ZillitTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                    maxLines = 1,
                )
            }
            columns.allocation?.let { Spacer(Modifier.width(it)) }
        }
    }
}

@Composable
private fun EmptyBudgets(canEdit: Boolean, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = EMPTY_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier.size(EMPTY_TILE).clip(ZillitTheme.shapes.large).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.BarChart, tint = colors.accent, size = EMPTY_ICON)
        }
        ZillitText(
            text = "No budget versions yet",
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        ZillitText(
            text = "Upload a budget file (PDF / Excel) and we'll extract every Chart of Accounts code + amount, " +
                "creating a draft version you can review.",
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EMPTY_TEXT_WIDTH),
        )
        if (canEdit) {
            ZillitButton(
                text = "Import Budget",
                onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                leadingIcon = ZillitIcons.Upload,
            )
        }
    }
}

@Composable
private fun SelectPrompt(modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(vertical = EMPTY_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        FieldHint("Select a version to inspect.")
    }
}

@Composable
internal fun LoadingPanel(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSpinner()
        FieldHint(message)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

/**
 * The symbol a version's figures carry: its own currency, else the
 * production's default, else pounds — the web's `symbol(code)` fallback chain.
 */
private fun AccountHubUiState.budgetSymbol(version: BudgetVersion): String =
    Money.symbol(
        version.currencyCode.ifBlank { setup.currencies.saved.defaultCode.orEmpty() }.ifBlank { DEFAULT_CURRENCY },
    )

private val META
    @Composable get() = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)

private val CODE
    @Composable get() = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** Small capitals with a little air between them — the web's uppercase table labels. */
private val TRACKED_LABEL
    @Composable get() = ZillitTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = LABEL_TRACKING,
    )

private const val DEFAULT_CURRENCY = "GBP"
private const val SELECTED_RING = 0.45f
private const val HALF = 0.5f
private const val HEADER_TINT = 0.6f
private const val HEADER_WASH = 0.05f
private const val SECTION_WASH = 0.025f
private const val SECTION_BAR = 0.55f
private const val LEAF_BAR = 0.3f
private val LABEL_TRACKING = 0.6.sp
private val VERSIONS_WIDTH = 320.dp
private val RAIL = 3.dp
private val LOCK = 12.dp
private val DOT = 3.dp
private val SMALL_ICON = 12.dp
private val SMALL_SPINNER = 14.dp
private val ROW_PADDING = 20.dp
private val ROW_VERTICAL = 10.dp
private val WIDE_DETAIL = 760.dp
private val MEDIUM_DETAIL = 600.dp
private val NARROW_PAGE = 980.dp
private val NARROW_VERSIONS_WIDTH = 260.dp
private val PERCENT_WIDTH = 46.dp
private val INDENT = 16.dp
private val TOGGLE = 16.dp
private val LEAF_DOT = 4.dp
private val BAR_HEIGHT = 6.dp
private val EMPTY_PADDING = 64.dp
private val EMPTY_TILE = 56.dp
private val EMPTY_ICON = 28.dp
private val EMPTY_TEXT_WIDTH = 540.dp
