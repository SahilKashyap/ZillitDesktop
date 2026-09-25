package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.INBOX_STATUS_FILTERS
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReconciliationBadge
import com.zillit.desktop.feature.cardexpenses.domain.inboxBadge
import com.zillit.desktop.feature.cardexpenses.domain.matchesInboxSearch
import com.zillit.desktop.feature.cardexpenses.ui.ALL_STATUSES
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InboxEvent
import com.zillit.desktop.feature.cardexpenses.ui.WorkflowStatusPill
import com.zillit.desktop.feature.cardexpenses.ui.components.ReconciliationBadgePill
import com.zillit.desktop.feature.cardexpenses.ui.components.RowAction
import com.zillit.desktop.feature.cardexpenses.ui.components.RowActionMenu
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * The Receipt Inbox (`pages/ReceiptInboxPage.jsx`): every receipt, in four
 * stacked sections — System Matched, No Match, Duplicate, Personal — each
 * foldable, each with its own count and its own empty line.
 *
 * Rows open the receipt detail; the "⋯" menu holds what the row's match
 * state allows (Attach, Manual Match, Flag Personal) with no confirmation in
 * front, as the web's does. The detail and manual match are drawn at the
 * screen root — see `InboxDialogs`.
 */
@Composable
fun ReceiptInboxPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val rows = state.receipts
        .filter { state.statusFilter == ALL_STATUSES || it.status.wire == state.statusFilter }
        .filter { it.matchesInboxSearch(state.search, holderName(state, it)) }
    val bySection = rows.groupBy { it.inboxSection }

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitSearchField(
            value = state.search,
            onValueChange = { onEvent(CardEvent.Search(it)) },
            placeholder = str(S.desktop_ce_inbox_search_receipts),
            modifier = Modifier.fillMaxWidth(),
        )
        StatusChips(INBOX_STATUS_FILTERS, state.statusFilter, onEvent)

        // Skeleton on the first read only; a refetch swaps the rows in place.
        if (state.loading && state.receipts.isEmpty()) {
            InboxSkeleton()
            return@ZillitScrollColumn
        }

        ActionNeeded(
            (bySection[InboxSection.SystemMatched].orEmpty() + bySection[InboxSection.NoMatch].orEmpty())
                .mapNotNull { it.inboxBadge },
        )
        InboxSection.entries.forEach { section ->
            SectionCard(state, section, bySection[section].orEmpty(), onEvent)
        }
    }
}

/** The fixed status chips (`QuickFilters`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusChips(statuses: List<String>, active: String, onEvent: (CardEvent) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        statuses.forEach { wire ->
            ZillitChoiceChip(
                label = statusChipLabel(wire),
                selected = wire == active,
                onClick = { onEvent(CardEvent.FilterStatus(wire)) },
            )
        }
    }
}

/** A chip's label, in the web's capitalisation. */
internal fun statusChipLabel(wire: String): String = when (wire) {
    ALL_STATUSES -> str(S.all)
    "new" -> str(S.ah_txn_filter_new)
    "pending_receipt" -> str(S.ah_txn_filter_pending_receipt)
    "pending_code" -> str(S.ah_txn_filter_pending_code)
    "awaiting_approval" -> str(S.dm_filter_status_pending)
    "approved" -> str(S.approved)
    "rejected" -> str(S.rejected)
    "queried" -> str(S.ah_queried)
    "under_review" -> str(S.ah_under_review)
    "escalated" -> str(S.ah_escalated)
    "posted" -> str(S.ah_status_posted)
    "personal" -> str(S.personal)
    else -> wire
}

/**
 * "Action needed" (`ReceiptInboxPage.jsx:642-679`): the suggested and
 * unreconciled receipts in the two open sections, counted off the same badge
 * the Status column draws, so the banner cannot name a state the chips don't show.
 */
@Composable
private fun ActionNeeded(badges: List<ReconciliationBadge>) {
    val suggested = badges.count { it == ReconciliationBadge.MatchSuggested }
    val unreconciled = badges.count { it == ReconciliationBadge.Unreconciled }
    val total = suggested + unreconciled
    if (total == 0) return
    val parts = listOfNotNull(
        suggested.takeIf { it > 0 }?.let { str(S.desktop_ce_inbox_count_match_suggested, it) },
        unreconciled.takeIf { it > 0 }?.let { str(S.desktop_ce_inbox_count_unreconciled, it) },
    )
    val joined = if (parts.size == 2) str(S.desktop_docdist_x_and_y, parts[0], parts[1]) else parts.single()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.accentSoft)
            .border(1.dp, ZillitTheme.colors.accent.copy(alpha = NOTICE_RIM), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(ZillitIcons.Warning, tint = ZillitTheme.colors.accent, size = NOTICE_GLYPH)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(str(S.desktop_pc_tl_action_needed), style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = str(
                    if (total == 1) S.desktop_ce_inbox_action_needed_one else S.desktop_ce_inbox_action_needed_many,
                    joined,
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

/** One of the four sections: its header, and its rows or its empty line. */
@Suppress("LongMethod") // Header, empty state and table of one section card.
@Composable
private fun SectionCard(
    state: CardUiState,
    section: InboxSection,
    rows: List<CardReceipt>,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val collapsed = section in state.inbox.collapsed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(InboxEvent.ToggleSection(section)) }
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIcon(
                if (collapsed) ZillitIcons.ChevronRight else ZillitIcons.ChevronDown,
                size = CHEVRON,
                tint = colors.textMuted,
            )
            ZillitIcon(section.icon, size = SECTION_GLYPH, tint = colors.accent)
            ZillitText(
                text = section.title,
                style = ZillitTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = str(
                    if (rows.size == 1) S.desktop_card_receipt_count_one else S.desktop_card_receipt_count_other,
                    rows.size,
                ),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            // Re-run Match lives on the System Matched header, runs over every
            // statement, and asks nothing first (`ReceiptInboxPage.jsx:258-279`).
            if (section == InboxSection.SystemMatched) {
                ZillitButton(
                    text = if (state.inbox.rerunning) {
                        str(S.desktop_matching_ellipsis)
                    } else {
                        str(S.desktop_ce_inbox_rerun_match)
                    },
                    onClick = { onEvent(InboxEvent.RerunMatch) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    enabled = !state.inbox.rerunning,
                )
            }
        }
        if (collapsed) return@Column
        ZillitDivider()
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Box(
                    modifier = Modifier.size(EMPTY_WELL).clip(ZillitTheme.shapes.large).background(colors.surfaceHover),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(section.icon, size = EMPTY_GLYPH, tint = colors.borderStrong)
                }
                ZillitText(section.emptyText, style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            }
            return@Column
        }
        InboxHeader()
        // The web caps each section at 60vh and scrolls inside it.
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().heightIn(max = SECTION_MAX)) {
            rows.forEach { receipt ->
                InboxRow(state, section, receipt, onEvent)
                ZillitDivider()
            }
        }
    }
}

@Composable
private fun InboxHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    ) {
        HeaderText(str(S.date), Modifier.width(DATE_COLUMN))
        HeaderText(str(S.ah_receipt_details), Modifier.weight(1f))
        HeaderText(str(S.desktop_card_card_holder), Modifier.width(HOLDER_COLUMN))
        HeaderText(str(S.code), Modifier.width(CODE_COLUMN))
        HeaderText(str(S.amount), Modifier.width(AMOUNT_COLUMN), TextAlign.End)
        Spacer(Modifier.width(ZillitTheme.spacing.md))
        HeaderText(str(S.status), Modifier.width(STATUS_COLUMN))
        Spacer(Modifier.width(MENU_COLUMN))
    }
}

@Suppress("LongMethod") // One row's seven cells.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InboxRow(
    state: CardUiState,
    section: InboxSection,
    receipt: CardReceipt,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val holder = state.people.firstOrNull { it.id == receipt.holderId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(InboxEvent.OpenDetail(receipt.id)) }
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitText(
            text = date(receipt.date),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.width(DATE_COLUMN),
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.Center,
            ) {
                ZillitText(
                    text = receipt.description.ifBlank { str(S.desktop_ce_cards_unknown_merchant) },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                // The row's own unread (`ReceiptInboxPage.jsx:354-357`).
                ZillitBadge(
                    count = state.unreadRow("receipt_inbox", receipt.id),
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                receipt.matchScore?.let { ScorePill(it) }
                if (receipt.urgent) {
                    ZillitStatusPill(str(S.ah_topup_filter_urgent), tone = StatusTone.Rejected, dot = true)
                }
            }
            receipt.codeDescription?.takeIf { it.isNotBlank() }?.let {
                ZillitText(it, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
            }
            if (!receipt.transactionId.isNullOrBlank()) LinkedTransaction(receipt, holder?.name)
            Flags(receipt, onEvent)
        }

        Column(modifier = Modifier.width(HOLDER_COLUMN)) {
            if (holder == null) {
                ZillitText(EM_DASH, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            } else {
                ZillitText(
                    text = holder.name,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
                holder.designation.takeIf { it.isNotBlank() }?.let {
                    ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                }
            }
        }

        Column(modifier = Modifier.width(CODE_COLUMN)) {
            ZillitText(
                text = receipt.nominalCode?.takeIf { it.isNotBlank() } ?: EM_DASH,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (receipt.nominalCode.isNullOrBlank()) colors.textMuted else colors.gold,
            )
            // Episode is a television field (`useIsTelevisionProject`).
            receipt.episode?.takeIf { it.isNotBlank() && state.viewer.isTelevision }?.let {
                ZillitText(
                    text = str(S.desktop_episode_abbrev_list, it),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }

        ZillitText(
            text = money(receipt.amount, receipt.currency),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.accent,
            textAlign = TextAlign.End,
            modifier = Modifier.width(AMOUNT_COLUMN),
        )
        Spacer(Modifier.width(ZillitTheme.spacing.md))
        Box(Modifier.width(STATUS_COLUMN)) {
            receipt.inboxBadge?.let { ReconciliationBadgePill(it) } ?: WorkflowStatusPill(receipt.status)
        }
        Box(Modifier.width(MENU_COLUMN), contentAlignment = Alignment.CenterEnd) {
            RowActionMenu(items = rowActions(state, section, receipt, onEvent))
        }
    }
}

/** "Linked Txn:" merchant, then holder · amount · card · date (`ReceiptInboxPage.jsx:387-433`). */
@Composable
private fun LinkedTransaction(receipt: CardReceipt, holderName: String?) {
    val colors = ZillitTheme.colors
    Column(modifier = Modifier.padding(top = ZillitTheme.spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                text = str(S.desktop_ce_cards_linked_txn),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = receipt.transactionMerchant?.takeIf { it.isNotBlank() } ?: receipt.description,
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
        }
        val line = listOfNotNull(
            holderName,
            receipt.transactionAmount?.let { money(it, receipt.currency) },
            receipt.transactionCardLastFour?.takeIf { it.isNotBlank() }?.let { "···· $it" },
            receipt.transactionDate?.let { date(it) },
        )
        if (line.isNotEmpty()) {
            ZillitText(
                text = line.joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * The duplicate and personal suspicions, each with its Dismiss link and no
 * confirmation (`ReceiptInboxPage.jsx:435-495`). Personal is hidden once the
 * receipt already is personal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Flags(receipt: CardReceipt, onEvent: (CardEvent) -> Unit) {
    val duplicate = receipt.duplicateScore?.takeIf { it > 0 && !receipt.duplicateDismissed }
    val personal = receipt.personalScore?.takeIf {
        it > 0 && !receipt.personalDismissed && receipt.status.wire != PERSONAL
    }
    if (duplicate == null && personal == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        duplicate?.let { score ->
            FlagChip(
                label = if (score >= CERTAIN) str(S.dd_csv_status_duplicate) else str(S.desktop_possible_duplicate),
                strong = score >= CERTAIN,
                strongColor = ZillitTheme.colors.violet,
                onDismiss = { onEvent(InboxEvent.DismissDuplicate(receipt.id)) },
            )
        }
        personal?.let { score ->
            FlagChip(
                label = if (score >= CERTAIN) str(S.personal) else str(S.desktop_ce_inbox_may_be_personal),
                strong = score >= CERTAIN,
                strongColor = ZillitTheme.colors.danger,
                onDismiss = { onEvent(InboxEvent.DismissPersonal(receipt.id)) },
            )
        }
    }
}

@Composable
private fun FlagChip(
    label: String,
    strong: Boolean,
    strongColor: Color,
    onDismiss: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val tint = if (strong) strongColor else colors.warning
    Row(
        modifier = Modifier
            .padding(top = ZillitTheme.spacing.xs)
            .clip(ZillitTheme.shapes.small)
            .background(tint.copy(alpha = FLAG_WASH))
            .border(1.dp, tint.copy(alpha = FLAG_RIM), ZillitTheme.shapes.small)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(label, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = tint)
        ZillitText(
            text = str(S.sync_action_dismiss),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

/**
 * The row menu (`ReceiptInboxPage.jsx:569-631`):
 *
 *  - Attach, only on a suggested match — it confirms the machine's guess;
 *  - Manual Match on an unmatched or suggested row, or one with no match
 *    status and no transaction;
 *  - Flag Personal in the two open sections only; the transaction endpoint
 *    when the receipt is linked, the receipt's own otherwise.
 */
private fun rowActions(
    state: CardUiState,
    section: InboxSection,
    receipt: CardReceipt,
    onEvent: (CardEvent) -> Unit,
): List<RowAction> = buildList {
    if (receipt.matchStatus == MatchStatus.Suggested) {
        val attaching = state.inbox.attachingId == receipt.id
        add(
            RowAction(
                label = if (attaching) str(S.desktop_ce_inbox_attaching) else str(S.dd_action_attach),
                description = str(S.desktop_ce_inbox_attach_desc),
                icon = ZillitIcons.Link,
                tone = ZillitMenuTone.Info,
                enabled = !attaching,
                onClick = { onEvent(InboxEvent.Attach(receipt.id)) },
            ),
        )
    }
    // `MatchStatus` reads a missing status as unmatched, so "no status and no
    // transaction" is covered by the unmatched test.
    if (receipt.matchStatus == MatchStatus.Unmatched || receipt.matchStatus == MatchStatus.Suggested) {
        add(
            RowAction(
                label = str(S.desktop_manual_match),
                description = str(S.desktop_ce_inbox_manual_match_desc),
                icon = ZillitIcons.Search,
                tone = ZillitMenuTone.Primary,
                onClick = { onEvent(InboxEvent.OpenManualMatch(receipt)) },
            ),
        )
    }
    if (section == InboxSection.SystemMatched || section == InboxSection.NoMatch) {
        add(
            RowAction(
                label = str(S.ah_flag_personal),
                description = str(S.desktop_ce_inbox_flag_personal_desc),
                icon = ZillitIcons.Warning,
                danger = true,
                onClick = { onEvent(InboxEvent.FlagPersonal(receipt)) },
            ),
        )
    }
}

/** Two section-shaped skeletons — a header strip over rows — not "Loading…" text. */
@Composable
private fun InboxSkeleton() {
    listOf(SKELETON_ROWS_FIRST, SKELETON_ROWS_SECOND).forEach { count ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.surface)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitSkeletonBar(Modifier.width(SKELETON_TITLE))
            repeat(count) { ZillitSkeletonBar() }
        }
    }
}

@Composable
internal fun HeaderText(text: String, modifier: Modifier, align: TextAlign? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textSecondary,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

private fun holderName(state: CardUiState, receipt: CardReceipt): String =
    state.people.firstOrNull { it.id == receipt.holderId }?.name.orEmpty()

private val InboxSection.icon: ImageVector
    get() = when (this) {
        InboxSection.SystemMatched -> ZillitIcons.Link
        InboxSection.NoMatch -> ZillitIcons.Close
        InboxSection.Duplicate -> ZillitIcons.Copy
        InboxSection.Personal -> ZillitIcons.User
    }

private val InboxSection.title: String
    get() = when (this) {
        InboxSection.SystemMatched -> str(S.desktop_card_inbox_system_matched)
        InboxSection.NoMatch -> str(S.desktop_ce_inbox_no_match)
        InboxSection.Duplicate -> str(S.dd_csv_status_duplicate)
        InboxSection.Personal -> str(S.personal)
    }

private val InboxSection.emptyText: String
    get() = when (this) {
        InboxSection.SystemMatched -> str(S.desktop_ce_inbox_empty_system_matched)
        InboxSection.NoMatch -> str(S.desktop_ce_inbox_empty_no_match)
        InboxSection.Duplicate -> str(S.desktop_ce_inbox_empty_duplicates)
        InboxSection.Personal -> str(S.desktop_ce_inbox_empty_personal)
    }

private const val PERSONAL = "personal"

/** ≥ 85% is named outright; below that it is a suspicion (`ReceiptInboxPage.jsx:436-472`). */
private const val CERTAIN = 85
private const val NOTICE_RIM = 0.35f
private const val FLAG_WASH = 0.10f
private const val FLAG_RIM = 0.30f
private const val SKELETON_ROWS_FIRST = 5
private const val SKELETON_ROWS_SECOND = 3
private val NOTICE_GLYPH = 17.dp
private val CHEVRON = 12.dp
private val SECTION_GLYPH = 16.dp
private val EMPTY_WELL = 48.dp
private val EMPTY_GLYPH = 22.dp
private val SECTION_MAX = 480.dp
private val SKELETON_TITLE = 180.dp
private val DATE_COLUMN = 96.dp
private val HOLDER_COLUMN = 160.dp
private val CODE_COLUMN = 96.dp
private val AMOUNT_COLUMN = 110.dp
private val STATUS_COLUMN = 150.dp
private val MENU_COLUMN = 40.dp
