package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashPeople
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashBadges
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashPrompt
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.ConfirmAction
import com.zillit.desktop.feature.cashexpenses.ui.FloatExpansion
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import kotlin.time.Clock
import kotlin.math.roundToInt

/**
 * The accountant's float register — the web's `PCFloatsPage`.
 *
 * One card per float, as the web draws them: a status stripe, who holds it,
 * how long it has run, the one transition its status offers, and — while the
 * cash is out — what was issued, spent and is left. A card opens onto the
 * batches spent against the float, and a batch onto its progress and
 * receipts. The eye opens the float's full details; History its audit trail.
 *
 * The lifecycle actions are the accountant's; a coordinator who may view
 * department floats sees the same cards read-only.
 */
@Suppress("LongMethod") // Notice, header, bar and the register: one page.
@Composable
fun ActiveFloatsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    // Fund requests take the page over, as they do on the web's register.
    state.funds?.let {
        FundsPage(state, it, onEvent)
        return
    }
    val people = LocalCashPeople.current
    val floats = state.activeFloats
    val rows = floats.filter { it.matches(state.search, people, state) }

    ScrollingPage {
        PcNotice(
            title = str(S.desktop_ce_active_floats),
            body = str(S.desktop_pc_active_floats_notice),
            tone = NoticeTone.Accent,
            icon = ZillitIcons.Wallet,
        )
        Column {
            ZillitText(text = str(S.desktop_pc_active_floats_register), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(
                    S.desktop_pc_active_outstanding,
                    floats.size,
                    state.describeTotal(floats.map { it.currency to it.requestedAmount }),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = state.search,
                onValueChange = { onEvent(CashEvent.Search(it)) },
                placeholder = str(S.desktop_pc_search_floats_long),
                modifier = Modifier.weight(1f),
            )
            // Accountant-only, as the web's seamless bar is: a coordinator
            // viewing department floats sees them read-only.
            if (state.viewer.isAccountant) {
                ZillitButton(
                    text = str(S.desktop_ce_funds),
                    onClick = { onEvent(CashEvent.ShowFunds(true)) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Bank,
                )
                ZillitButton(
                    text = str(S.desktop_ce_record_cash_return),
                    onClick = {
                        val prompt = CashPrompt.RecordReturn(floatId = null, receivedDate = CashDates.today())
                        onEvent(CashEvent.Ask(prompt))
                    },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.ah_new_float),
                    onClick = { onEvent(CashEvent.RaiseFloatForCrew) },
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }

        when {
            state.loading && floats.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING),
                contentAlignment = Alignment.Center,
            ) { ZillitSpinner(size = SPINNER) }

            rows.isEmpty() -> PcCard(modifier = Modifier.fillMaxWidth()) {
                ZillitText(
                    // An empty search is not an empty register: "no active
                    // floats" with twelve behind the query reads as data loss.
                    text = if (floats.isEmpty()) {
                        str(S.desktop_pc_no_active_floats_found)
                    } else {
                        str(S.desktop_pc_no_floats_match_query, state.search.trim())
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(EMPTY_PADDING),
                )
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                rows.forEach { float -> FloatCard(state, float, onEvent) }
            }
        }
    }
}

@Suppress("LongMethod") // The card top to bottom: who, the action row, the figures, the batches.
@Composable
private fun FloatCard(state: CashUiState, float: CashFloat, onEvent: (CashEvent) -> Unit) {
    val stripe = stripeColor(float.status)
    val expansion = state.floatExpansions[float.id]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .drawBehind { drawRect(stripe, size = Size(STRIPE.toPx(), size.height)) }
            .padding(start = STRIPE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEvent(CashEvent.ToggleFloatBatches(float.id)) }
                .padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                FloatIdentity(state, float, Modifier.weight(1f))
                FloatActions(state, float, expanded = expansion != null, onEvent = onEvent)
            }
            when (float.status) {
                in SPENDING_STATUSES -> SpendFigures(state, float)
                FloatStatus.Closed, FloatStatus.Cancelled -> Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
                ) {
                    PcField(str(S.desktop_pc_float_limit), state.formatMoney(float.requestedAmount, float.currency))
                    PcField(str(S.desktop_pc_returned), state.formatMoney(float.returnAmount, float.currency))
                }

                FloatStatus.ReadyToCollect -> ReadyStrip(state, float)
                else -> Unit
            }
        }
        expansion?.let {
            ZillitDivider()
            FloatBatches(state, float, it, onEvent)
        }
    }
}

@Composable
private fun FloatIdentity(state: CashUiState, float: CashFloat, modifier: Modifier) {
    val designation = state.assignees.firstOrNull { it.userId == float.userId }?.designation?.takeIf { it.isNotBlank() }
    val days = daysActive(float.createdAt)
    val meta = listOfNotNull(
        "#${float.requestNumber}",
        str(S.desktop_pc_submitted_on, EpochDate.date(float.createdAt).ifBlank { "—" }),
        if (days == 1) str(S.desktop_pc_days_active_one, days) else str(S.desktop_pc_days_active_other, days),
        durationLabel(float, str(S.desktop_pc_run_of_show_lower)),
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            CashPerson(userId = float.userId, recordedName = float.holderName, size = AVATAR)
            companyName(state, float)?.let { ZillitStatusPill(label = it, tone = StatusTone.Pending) }
        }
        designation?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
        }
        ZillitText(
            text = meta.joinToString(" · "),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

/** Status, the details eye, History, the one lifecycle action, and the chevron. */
@Suppress("LongMethod") // One branch per status transition.
@Composable
private fun FloatActions(state: CashUiState, float: CashFloat, expanded: Boolean, onEvent: (CashEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitStatusPill(label = pillLabel(float.status), tone = pillTone(float.status), dot = true)
        Box {
            ZillitIconButton(
                icon = ZillitIcons.Eye,
                contentDescription = str(S.desktop_pc_view_float_details),
                onClick = { onEvent(CashEvent.OpenFloatDetail(float.id)) },
            )
            ZillitBadge(
                count = state.unreadFor(CashBadges.PC_FLOAT, float.id),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        if (float.status in HISTORY_STATUSES) {
            ZillitButton(
                text = str(S.history),
                onClick = { onEvent(CashEvent.ShowFloatHistory(float.id, float.requestNumber)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Clock,
            )
        }
        if (state.viewer.isAccountant) {
            when (float.status) {
                FloatStatus.Approved, FloatStatus.AcctOverride -> ZillitButton(
                    text = str(S.desktop_pc_ready_to_collect_ellipsis),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.ReadyToCollect(
                                    floatId = float.id,
                                    companyId = float.companyId.orEmpty(),
                                    bsCode = float.bsCode.orEmpty(),
                                ),
                            ),
                        )
                    },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                FloatStatus.ReadyToCollect -> ZillitButton(
                    text = str(S.desktop_ce_mark_collected),
                    onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.CollectFloat, float.id))) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                FloatStatus.PendingReturn -> ZillitButton(
                    text = str(S.desktop_ce_record_return),
                    onClick = {
                        onEvent(
                            CashEvent.Ask(
                                CashPrompt.RecordReturn(
                                    floatId = float.id,
                                    receivedDate = CashDates.today(),
                                    fixed = true,
                                ),
                            ),
                        )
                    },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                FloatStatus.Spent -> ZillitButton(
                    text = str(S.desktop_ce_close_float),
                    onClick = { onEvent(CashEvent.ActNow(confirm(ConfirmAction.CloseFloat, float.id))) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )

                else -> Unit
            }
        }
        ZillitIcon(
            icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
            tint = ZillitTheme.colors.textMuted,
            size = CHEVRON,
        )
    }
}

/** Issued, spent and left, the bar, and "Spent £x of £y" (`PCFloatsPage.jsx:795-811`). */
@Composable
private fun SpendFigures(state: CashUiState, float: CashFloat) {
    val limit = float.requestedAmount
    val spent = (limit - float.balance).coerceAtLeast(0.0)
    val pct = if (limit > 0) (spent / limit * PERCENT).roundToInt() else 0
    val money = { amount: Double -> state.formatMoney(amount, float.currency) }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            val issued = float.issuedAmount.takeIf { it > 0 } ?: limit
            Metric(str(S.desktop_issued), money(issued), null, Modifier.weight(1f))
            Metric(str(S.desktop_pc_spent_returned), money(spent), ZillitTheme.colors.info, Modifier.weight(1f))
            Metric(
                str(S.ah_balance_label),
                money(float.balance),
                if (float.balance <= 0) ZillitTheme.colors.violet else ZillitTheme.colors.success,
                Modifier.weight(1f),
            )
        }
        ZillitMeter(
            fraction = (pct / PERCENT).toFloat(),
            tone = if (pct >= FULL) StatusTone.Escalated else StatusTone.Pending,
        )
        Row {
            ZillitText(
                text = str(S.desktop_pc_spent_of, money(spent), money(limit)),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = "$pct%", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, tint: Color?, modifier: Modifier) {
    PcField(
        label = label,
        value = value,
        valueColor = tint,
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    )
}

/** "£x ready for collection at production office." (`PCFloatsPage.jsx:827-836`). */
@Composable
private fun ReadyStrip(state: CashUiState, float: CashFloat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.successSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Check, tint = ZillitTheme.colors.success, size = CHEVRON)
        ZillitText(
            text = str(S.desktop_pc_ready_for_collection, state.formatMoney(float.requestedAmount, float.currency)),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.success,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = str(S.desktop_pc_requested_on, EpochDate.date(float.createdAt)),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.success,
        )
    }
}

// -- the batches under an opened card ------------------------------------------------------

@Suppress("LongMethod") // Loading, empty, the grid and the list beside the picked batch.
@Composable
private fun FloatBatches(state: CashUiState, float: CashFloat, open: FloatExpansion, onEvent: (CashEvent) -> Unit) {
    val batches = open.batches
    val picked = batches?.firstOrNull { it.id == open.selectedBatchId }
    Box(modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg)) {
        when {
            batches == null -> ZillitSpinner(size = SPINNER, modifier = Modifier.align(Alignment.Center))
            batches.isEmpty() -> ZillitText(
                text = str(S.desktop_pc_no_receipts_for_float),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                Column(
                    modifier = Modifier.weight(if (picked == null) 1f else 1f / 2),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitText(
                        text = batchCount(batches.size).uppercase(),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    val perRow = if (picked == null) GRID_COLUMNS else 1
                    batches.chunked(perRow).forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                            line.forEach { batch ->
                                BatchTile(state, float, batch, batch.id == picked?.id, Modifier.weight(1f)) {
                                    onEvent(CashEvent.SelectFloatBatch(float.id, batch.id))
                                }
                            }
                            repeat(perRow - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                picked?.let { batch ->
                    PickedBatch(state, float, batch, open.claims[batch.id], Modifier.weight(1f), onEvent)
                }
            }
        }
    }
}

@Composable
private fun BatchTile(
    state: CashUiState,
    float: CashFloat,
    batch: ClaimBatch,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (active) ZillitTheme.colors.accentSoft else ZillitTheme.colors.surface)
            .border(
                HAIRLINE,
                if (active) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                ZillitTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Receipt, tint = ZillitTheme.colors.accentText, size = CHEVRON)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = "#${batch.reference}", style = ZillitTheme.typography.label, maxLines = 1)
            ZillitText(
                text = "${receiptsLabel(batch.claimCount)} · ${EpochDate.date(batch.createdAt)}",
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
        ZillitText(
            text = state.formatMoney(batch.totalGross, float.currency),
            style = ZillitTheme.typography.numeric,
            maxLines = 1,
        )
        BatchStatusPill(batch.status, state.viewer.isAccountant)
    }
}

/** The picked batch: its progress, the queue it waits in, and its receipts. */
@Suppress("LongMethod") // Header, timeline, the queue link and the receipts.
@Composable
private fun PickedBatch(
    state: CashUiState,
    float: CashFloat,
    batch: ClaimBatch,
    claims: List<com.zillit.desktop.feature.cashexpenses.domain.Claim>?,
    modifier: Modifier,
    onEvent: (CashEvent) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column {
            ZillitText(text = "#${batch.reference}", style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = listOf(
                    state.formatMoney(batch.totalGross, float.currency),
                    receiptsLabel(batch.claimCount),
                    EpochDate.dateTime(batch.createdAt),
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        ZillitText(
            text = str(S.status).uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ClaimTimeline(batch)
        queueFor(batch.status)?.takeIf { it.openableBy(state.viewer) }?.let { (destination, label) ->
            ZillitButton(
                text = label,
                onClick = { onEvent(CashEvent.Open(destination)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        ZillitDivider()
        ZillitText(
            text = str(S.desktop_ce_claims).uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        when {
            claims == null -> ZillitSpinner(size = SPINNER)
            claims.isEmpty() -> ZillitText(
                text = str(S.desktop_pc_no_claims),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> claims.forEach { claim ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZillitIcon(icon = ZillitIcons.Receipt, tint = ZillitTheme.colors.accentText, size = SMALL_ICON)
                    ZillitText(
                        text = claim.description.ifBlank { "—" },
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.sm),
                    )
                    ZillitText(
                        text = state.formatMoney(claim.grossAmount, float.currency),
                        style = ZillitTheme.typography.numeric,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Submitted → Coordinator Coding → Accounts Audit → Approval → Post & Ledger
 * → Settlement, the batch's step lit (`PCFloatsPage.jsx:237-322`).
 */
@Composable
private fun ClaimTimeline(batch: ClaimBatch) {
    val steps = timelineSteps(batch)
    val colors = ZillitTheme.colors
    Column {
        steps.forEachIndexed { index, step ->
            val dot = when {
                step.done -> colors.success
                step.active -> colors.accent
                else -> colors.border
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = DOT_TOP).size(DOT).clip(ZillitTheme.shapes.pill).background(dot))
                    if (index < steps.lastIndex) {
                        val line = if (step.done) colors.success else colors.border
                        Box(Modifier.width(1.dp).height(LINE).background(line))
                    }
                }
                Column {
                    ZillitText(
                        text = step.title,
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        color = when {
                            step.done -> colors.success
                            step.active -> colors.accentText
                            else -> colors.textMuted
                        },
                    )
                    ZillitText(text = step.detail, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                }
            }
        }
    }
}

/** One step of [ClaimTimeline], as it reads for this batch. */
private data class TimelineStep(val title: String, val detail: String, val done: Boolean, val active: Boolean)

/** The six steps, with the first renamed on a rejected or queried batch (`ClaimTimeline`, web). */
private fun timelineSteps(batch: ClaimBatch): List<TimelineStep> {
    val stalled = batch.status == BatchStatus.Rejected || batch.status == BatchStatus.Queried
    val current = TIMELINE_STEP[batch.status] ?: 0
    return timelineBase(batch.settlementType).mapIndexed { index, (label, sub, doneSub) ->
        val done = !stalled && index < current
        val (title, detail) = if (index == 0) {
            firstStep(batch, label, if (done) doneSub else sub)
        } else {
            label to if (done) doneSub else sub
        }
        TimelineStep(title, detail, done = done, active = if (stalled) index == 0 else index == current)
    }
}

/** Submitted — or Rejected / Queried, which is where such a batch actually is. */
private fun firstStep(batch: ClaimBatch, label: String, detail: String): Pair<String, String> = when (batch.status) {
    BatchStatus.Rejected -> str(S.rejected) to str(S.desktop_pc_tl_resubmit)
    BatchStatus.Queried -> str(S.ah_queried) to str(S.desktop_pc_tl_action_needed)
    else -> label to (batch.reference.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_tl_batch_ref, it) } ?: detail)
}

/** Each step's label, its detail while waiting and its detail once done. */
private fun timelineBase(settlementType: String?): List<Triple<String, String, String>> {
    val settlement = settlementStep(settlementType)
    return listOf(
        Triple(S.txt_submitted, S.desktop_pc_tl_submitted_sub, S.desktop_pc_tl_submitted_done),
        Triple(S.desktop_pc_tl_coding, S.desktop_pc_tl_coding_sub, S.desktop_pc_tl_coding_done),
        Triple(S.desktop_pc_tl_audit, S.desktop_pc_tl_audit_sub, S.desktop_pc_tl_audit_done),
        Triple(S.ah_step_approval, S.desktop_pc_tl_approval_sub, S.approved),
        Triple(S.desktop_ce_post_and_ledger, S.desktop_pc_tl_post_sub, S.cr_posted_to_ledger),
    ).map { (label, sub, done) -> Triple(str(label), str(sub), str(done)) } +
        Triple(
            str(S.desktop_ce_settlement),
            settlement ?: str(S.desktop_ce_settlement),
            settlement ?: str(S.desktop_pc_tl_settled),
        )
}

// -- rules and labels --------------------------------------------------------------------

/** Holder, designation, reference, BS code and company — what the card shows (`PCFloatsPage.jsx:419-433`). */
private fun CashFloat.matches(query: String, people: CashPeople, state: CashUiState): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return listOfNotNull(
        people.nameOrNull(userId, holderName),
        state.assignees.firstOrNull { it.userId == userId }?.designation,
        requestNumber,
        bsCode,
        companyName(state, this),
    ).any { it.lowercase().contains(needle) }
}

private fun companyName(state: CashUiState, float: CashFloat): String? =
    float.companyName?.takeIf { it.isNotBlank() }
        ?: float.companyId?.let { id -> state.companies.firstOrNull { it.id == id }?.name }?.takeIf { it.isNotBlank() }

/** Whole days since [createdAt], never negative. */
private fun daysActive(createdAt: Long?): Int {
    createdAt ?: return 0
    val elapsed = Clock.System.now().toEpochMilliseconds() - createdAt
    return (elapsed / DAY_MS).toInt().coerceAtLeast(0)
}

/** The web's `FLOAT_PILL` labels. */
private fun pillLabel(status: FloatStatus): String = when (status) {
    FloatStatus.AwaitingApproval, FloatStatus.Unknown -> str(S.dm_filter_status_pending)
    FloatStatus.Active -> str(S.active)
    FloatStatus.Spent -> str(S.desktop_pc_fully_spent)
    else -> status.label
}

private fun pillTone(status: FloatStatus): StatusTone = when (status) {
    FloatStatus.AwaitingApproval, FloatStatus.AcctOverride, FloatStatus.Spending, FloatStatus.Unknown ->
        StatusTone.Pending

    FloatStatus.Approved -> StatusTone.Progress
    FloatStatus.ReadyToCollect, FloatStatus.Collected, FloatStatus.Active -> StatusTone.Ready
    FloatStatus.Spent -> StatusTone.Escalated
    FloatStatus.PendingReturn, FloatStatus.Rejected -> StatusTone.Rejected
    FloatStatus.Cancelled, FloatStatus.Closed -> StatusTone.Neutral
}

/** The web's `STRIPE_COLOR`, in the theme's hues. */
@Composable
private fun stripeColor(status: FloatStatus): Color {
    val colors = ZillitTheme.colors
    return when (status) {
        FloatStatus.AwaitingApproval, FloatStatus.AcctOverride, FloatStatus.Spending -> colors.accent
        FloatStatus.Approved -> colors.info
        FloatStatus.ReadyToCollect -> colors.teal
        FloatStatus.Collected, FloatStatus.Active -> colors.success
        FloatStatus.Spent -> colors.violet
        FloatStatus.PendingReturn, FloatStatus.Rejected -> colors.danger
        FloatStatus.Cancelled, FloatStatus.Closed, FloatStatus.Unknown -> colors.borderStrong
    }
}

/** The queue a batch waits in, and the link to it. */
private fun queueFor(status: BatchStatus): Pair<CashDestination, String>? = when (status) {
    BatchStatus.InAudit -> CashDestination.AuditQueue to str(S.desktop_pc_go_audit_queue)
    BatchStatus.AwaitingApproval -> CashDestination.ApprovalQueue to str(S.desktop_pc_go_approval_queue)
    BatchStatus.ReadyToPost, BatchStatus.UnderReview, BatchStatus.Escalated, BatchStatus.AcctOverride ->
        CashDestination.PostLedger to str(S.desktop_pc_go_post_ledger)

    else -> null
}

private fun Pair<CashDestination, String>.openableBy(
    viewer: com.zillit.desktop.feature.cashexpenses.domain.CashViewer,
) =
    first.openableBy(viewer)

private fun settlementStep(type: String?): String? = when (type) {
    "REDUCE_FLOAT" -> str(S.desktop_pc_reduce_float)
    "REIMBURSE" -> str(S.desktop_ce_reimburse)
    "TOP_UP_FLOAT" -> str(S.desktop_pc_top_up_float)
    "CLOSE_FLOAT" -> str(S.desktop_pc_close_float_lower)
    else -> null
}

private fun batchCount(count: Int): String =
    if (count == 1) str(S.desktop_pc_batch_count_one, count) else str(S.desktop_pc_batch_count_other, count)

private fun confirm(action: ConfirmAction, id: String) = CashPrompt.Confirm(action, id, "", "")

/** The web's `STATUS_TO_STEP`. */
private val TIMELINE_STEP = mapOf(
    BatchStatus.Coding to 1,
    BatchStatus.InAudit to 2,
    BatchStatus.AwaitingApproval to 3,
    BatchStatus.ReadyToPost to 4,
    BatchStatus.Escalated to 4,
    BatchStatus.UnderReview to 4,
    BatchStatus.AcctOverride to 4,
    BatchStatus.Posted to 5,
)

private val SPENDING_STATUSES = setOf(
    FloatStatus.Collected,
    FloatStatus.Active,
    FloatStatus.Spending,
    FloatStatus.Spent,
    FloatStatus.PendingReturn,
)

private val HISTORY_STATUSES = setOf(
    FloatStatus.Active,
    FloatStatus.Spending,
    FloatStatus.Spent,
    FloatStatus.PendingReturn,
    FloatStatus.Collected,
    FloatStatus.Closed,
    FloatStatus.Cancelled,
)

private const val PERCENT = 100.0
private const val FULL = 100
private const val GRID_COLUMNS = 3
private const val DAY_MS = 86_400_000L
private val HAIRLINE = 1.dp
private val STRIPE = 4.dp
private val AVATAR = 40.dp
private val CHEVRON = 14.dp
private val SMALL_ICON = 12.dp
private val DOT = 10.dp
private val DOT_TOP = 4.dp
private val LINE = 22.dp
private val EMPTY_PADDING = 40.dp
private val SPINNER = 16.dp
