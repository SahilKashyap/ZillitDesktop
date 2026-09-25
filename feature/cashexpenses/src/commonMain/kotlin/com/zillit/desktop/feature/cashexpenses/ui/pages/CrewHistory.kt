package com.zillit.desktop.feature.cashexpenses.ui.pages

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.HistoryFilter
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.QueryPanel
import com.zillit.desktop.feature.cashexpenses.ui.date

/**
 * Receipts History — "My Claims" (`PCMyClaimsPage.jsx`): the crew member's
 * batches on the left, the open one on the right with its timeline, its
 * receipts, its query thread and its history.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptsHistoryPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val type = state.destination.expenseType
    // The list is asked for by pipeline; the filter stays as a guard on an older service.
    val batches = state.myBatches.filter { it.expenseType == type }.sortedByDescending { it.createdAt ?: 0L }
    val rows = batches.filter { HistoryFilter.matches(state.crew.historyFilter, it) }
    val selected = state.selectedBatch?.takeIf { open -> rows.any { it.id == open.id } }

    FixedPage {
        CrewNotice(
            title = str(
                S.desktop_pc_my_claims_title,
                if (type == ExpenseType.OutOfPocket) str(S.desktop_ce_out_of_pocket) else str(S.desktop_petty_cash),
            ),
            body = str(S.desktop_pc_my_claims_body),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            HistoryFilter.KEYS.forEach { key ->
                ZillitChoiceChip(
                    label = if (key == HistoryFilter.ALL) str(S.all) else BatchStatus.from(key).label(),
                    selected = state.crew.historyFilter == key,
                    onClick = { onEvent(CrewEvent.FilterHistory(key)) },
                )
            }
        }
        when {
            state.loading && batches.isEmpty() -> CenteredNote(str(S.ah_loading), spinner = true)
            rows.isEmpty() -> CenteredNote(
                if (batches.isEmpty()) str(S.desktop_pc_no_receipts_yet) else str(S.desktop_pc_no_receipts_match),
            )
            else -> Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                BatchList(
                    state = state,
                    rows = rows,
                    selectedId = selected?.id,
                    onEvent = onEvent,
                    modifier = if (selected != null) Modifier.width(LIST_WIDTH) else Modifier.fillMaxWidth(),
                )
                if (selected != null) BatchPane(state, selected, onEvent, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, spinner: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinner) ZillitSpinner(size = SPINNER)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
        )
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(CREW_HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large),
    ) { content() }
}

@Composable
private fun BatchList(
    state: CashUiState,
    rows: List<ClaimBatch>,
    selectedId: String?,
    onEvent: (CashEvent) -> Unit,
    modifier: Modifier,
) {
    val level1 = state.destination.batchBadgeKey
    Card(modifier.fillMaxHeight()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.md,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrewHeading(
                    if (rows.size == 1) str(S.desktop_pc_batch_one) else str(S.desktop_ce_batch_count_other, rows.size),
                    Modifier.weight(1f),
                )
                ZillitText(
                    text = str(S.desktop_pc_sort_newest),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            CrewRule()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(rows, key = { it.id }) { batch ->
                    BatchRow(
                        state = state,
                        batch = batch,
                        active = batch.id == selectedId,
                        unread = level1?.let { state.unreadFor(it, batch.id) } ?: 0,
                        onClick = { onEvent(CashEvent.SelectBatch(if (batch.id == selectedId) null else batch.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchRow(state: CashUiState, batch: ClaimBatch, active: Boolean, unread: Int, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) colors.accentSoft else colors.surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(ACCENT_BAR).height(ROW_HEIGHT).background(if (active) colors.accent else colors.surface))
        Row(
            modifier = Modifier.weight(1f).padding(
                horizontal = ZillitTheme.spacing.md,
                vertical = ZillitTheme.spacing.md,
            ),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(active)
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = "#${batch.reference}", style = ZillitTheme.typography.numeric, maxLines = 1)
                ZillitText(
                    text = str(S.docusign_bulk_meta, receiptCount(batch.claimCount), date(batch.createdAt)),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            ZillitBadge(count = unread)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = state.formatMoney(batch.totalGross, batch.currency),
                    style = ZillitTheme.typography.numeric,
                )
                BatchStatusPill(batch.status, accountant = false)
            }
        }
    }
}

@Composable
private fun receiptCount(count: Int): String =
    if (count == 1) str(S.desktop_pc_receipt_one) else str(S.desktop_card_receipt_count_other, count)

@Composable
private fun IconTile(active: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.size(TILE).clip(ZillitTheme.shapes.medium)
            .background(if (active) colors.surface else colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Receipt, tint = if (active) colors.accentText else colors.textMuted, size = GLYPH)
    }
}

/** The open batch — header, timeline, receipts, and the query and history beneath. */
@Suppress("LongMethod") // The web's right pane, top to bottom.
@Composable
private fun BatchPane(state: CashUiState, batch: ClaimBatch, onEvent: (CashEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val panel = state.panel?.takeIf { it.batchId == batch.id }
    val claims = state.panelClaims
    Card(modifier.fillMaxHeight()) {
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(ZillitTheme.spacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(text = "#${batch.reference}", style = ZillitTheme.typography.titleMedium)
                        BatchStatusPill(batch.status, accountant = false)
                    }
                    ZillitText(
                        text = listOfNotNull(
                            date(batch.createdAt),
                            batch.postedAt?.let { str(S.desktop_pc_posted_on, date(it)) },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitButton(
                        text = str(S.ah_cd_query),
                        onClick = { onEvent(CrewEvent.ShowQuery(panel?.query == null)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Chat,
                    )
                    ZillitButton(
                        text = str(S.history),
                        onClick = { onEvent(CashEvent.ShowHistory(panel?.historyOpen != true)) },
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Clock,
                    )
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(start = ZillitTheme.spacing.sm),
                    ) {
                        CrewHeading(str(S.ah_total_label))
                        ZillitText(
                            text = state.formatMoney(batch.totalGross, batch.currency),
                            style = ZillitTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Timeline(batch, Modifier.padding(vertical = ZillitTheme.spacing.lg))
            ZillitText(
                text = str(S.desktop_pc_receipts_n, batch.claimCount.takeIf { it > 0 } ?: claims.size),
                style = ZillitTheme.typography.titleSmall,
                color = colors.textSecondary,
            )
            Column(
                modifier = Modifier.padding(top = ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                when {
                    panel != null && panel.claims == null && !panel.failed ->
                        CenteredNote(str(S.desktop_pc_loading_receipts), spinner = true)
                    claims.isEmpty() -> CenteredNote(str(S.desktop_pc_no_receipts_found))
                    else -> claims.forEach { claim ->
                        ReceiptRow(state, batch, claim, onClick = { onEvent(CrewEvent.OpenClaim(claim.id)) })
                    }
                }
            }
            panel?.query?.let { CrewQueryThread(it, onEvent) }
            if (panel?.historyOpen == true) BatchHistory(panel)
        }
    }
}

/** The five-step horizontal timeline (`PCMyClaimsPage.Timeline`). */
@Suppress("LongMethod") // Five steps and their three states.
@Composable
private fun Timeline(batch: ClaimBatch, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val status = batch.status.wire
    val steps = listOf(
        Step(str(S.txt_submitted), done = true, sub = date(batch.createdAt).takeIf { it != "—" }),
        Step(str(S.ah_step_coordinator), done = status in AFTER_CODING, active = status == BatchStatus.Coding.wire),
        Step(
            str(S.desktop_ce_in_audit),
            done = status in AFTER_AUDIT,
            active = status == BatchStatus.InAudit.wire || status == BatchStatus.Queried.wire,
        ),
        Step(
            str(S.ah_step_approval),
            done = status in AFTER_APPROVAL,
            active = status == BatchStatus.AwaitingApproval.wire,
        ),
        Step(
            str(S.ah_status_posted),
            done = status == BatchStatus.Posted.wire,
            sub = batch.postedAt?.let(::date),
        ),
    )
    Row(
        modifier = modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.surfaceSunken)
            .padding(ZillitTheme.spacing.md),
    ) {
        steps.forEachIndexed { index, step ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(DOT)
                        .clip(CircleShape)
                        .background(
                            when {
                                step.done -> colors.success
                                step.active -> colors.accentSoft
                                else -> colors.surface
                            },
                        )
                        .border(CREW_HAIRLINE, if (step.active) colors.accent else colors.border, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.done) {
                        ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = DOT_GLYPH)
                    } else {
                        ZillitText(
                            text = (index + 1).toString(),
                            style = ZillitTheme.typography.labelSmall,
                            color = if (step.active) colors.accentText else colors.textMuted,
                        )
                    }
                }
                ZillitText(
                    text = step.label,
                    style = if (step.active) ZillitTheme.typography.label else ZillitTheme.typography.labelSmall,
                    color = if (step.active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                step.sub?.let {
                    ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                }
            }
        }
    }
}

private data class Step(val label: String, val done: Boolean, val active: Boolean = false, val sub: String? = null)

@Composable
private fun ReceiptRow(state: CashUiState, batch: ClaimBatch, claim: Claim, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val level1 = state.destination.batchBadgeKey
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(active = true)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = claim.description.ifBlank { "—" }, style = ZillitTheme.typography.label, maxLines = 1)
            ZillitText(
                text = listOfNotNull(date(claim.receiptDate), claim.supplier?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        claim.category?.takeIf { it.isNotBlank() }?.let {
            ZillitStatusPill(label = ExpenseCategory.label(it), tone = StatusTone.Neutral)
        }
        ZillitBadge(count = level1?.let { state.unreadFor(it, claim.id) } ?: 0)
        Column(horizontalAlignment = Alignment.End) {
            ZillitText(
                text = state.formatMoney(claim.grossAmount, batch.currency),
                style = ZillitTheme.typography.numeric,
            )
            if (claim.deductionAmount > 0) {
                ZillitText(
                    text = "−" + state.formatMoney(claim.deductionAmount, batch.currency),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
}

/**
 * The batch's query thread for the person who submitted it — the same
 * `cash_claim` thread the accountant writes to, so both ends read one
 * conversation.
 */
@Composable
private fun CrewQueryThread(query: QueryPanel, onEvent: (CashEvent) -> Unit) {
    val people = LocalCashPeople.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        CrewRule()
        ZillitText(text = str(S.ah_cd_query), style = ZillitTheme.typography.titleSmall)
        val messages = query.thread?.messages.orEmpty()
        when {
            query.loading -> ZillitSpinner(size = SPINNER)
            messages.isEmpty() -> ZillitText(
                text = str(S.av_no_comments_yet),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            else -> messages.forEach { message ->
                Column {
                    ZillitText(
                        text = listOfNotNull(people.nameOf(message.userId), date(message.at).takeIf { it != "—" })
                            .joinToString(" · "),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(text = message.text, style = ZillitTheme.typography.bodyMedium)
                }
            }
        }
        ZillitTextField(
            value = query.draft,
            onValueChange = { onEvent(CashEvent.EditQuery(it)) },
            placeholder = str(S.desktop_pc_write_message),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        ZillitButton(
            text = str(S.desktop_ce_send_query),
            onClick = { onEvent(CrewEvent.SendQuery) },
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Send,
            enabled = query.draft.isNotBlank(),
            loading = query.sending,
        )
    }
}

private val AFTER_CODING = setOf(
    "IN_AUDIT", "AWAITING_APPROVAL", "READY_TO_POST", "ACCT_OVERRIDE", "UNDER_REVIEW", "ESCALATED", "QUERIED",
    "POSTED", "REJECTED",
)
private val AFTER_AUDIT = setOf(
    "AWAITING_APPROVAL",
    "READY_TO_POST",
    "ACCT_OVERRIDE",
    "UNDER_REVIEW",
    "ESCALATED",
    "POSTED",
)
private val AFTER_APPROVAL = setOf("READY_TO_POST", "ACCT_OVERRIDE", "UNDER_REVIEW", "ESCALATED", "POSTED")

private val LIST_WIDTH = 340.dp
private val ACCENT_BAR = 3.dp
private val ROW_HEIGHT = 60.dp
private val TILE = 36.dp
private val GLYPH = 16.dp
private val DOT = 20.dp
private val DOT_GLYPH = 10.dp
private val SPINNER = 14.dp
