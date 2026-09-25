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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.FundsAction
import com.zillit.desktop.feature.cashexpenses.ui.FundsState
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.fundsCurrency

/**
 * Fund Requests — money asked for into the float custodian
 * (`RequestCashFundsModal.jsx`), opened from Active Floats' Funds button.
 *
 * Raising a request records nothing in the ledger; marking it received is
 * what posts. The requests on the left, each open one with its progress chain
 * and its two actions; the new request on the right. The custodian is shown,
 * not chosen — Petty Cash has one, and it is changed in Settings.
 */
@Composable
fun FundsPage(state: CashUiState, funds: FundsState, onEvent: (CashEvent) -> Unit) {
    ScrollingPage {
        Breadcrumb(onBack = { onEvent(CashEvent.ShowFunds(false)) })
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RequestList(state, funds, onEvent, Modifier.weight(1f))
            NewRequestForm(state, funds, onEvent, Modifier.width(FORM_WIDTH))
        }
    }
}

@Composable
private fun Breadcrumb(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = str(S.desktop_petty_cash), onClick = onBack)
        ZillitText(
            text = str(S.desktop_petty_cash).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.accent,
            modifier = Modifier.clickable(onClick = onBack),
        )
        ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
        ZillitText(
            text = str(S.desktop_ce_fund_requests),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Suppress("LongMethod") // Header, loading, empty, rows and the outstanding footer.
@Composable
private fun RequestList(state: CashUiState, funds: FundsState, onEvent: (CashEvent) -> Unit, modifier: Modifier) {
    val requests = funds.requests
    FundsCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.desktop_ce_fund_requests),
                style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (!funds.loading) {
                ZillitText(
                    text = if (requests.size == 1) {
                        str(S.desktop_pc_request_count_one, requests.size)
                    } else {
                        str(S.desktop_pc_request_count, requests.size)
                    },
                    style = ZillitTheme.typography.numeric,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }
        ZillitDivider()
        when {
            funds.loading -> repeat(SKELETON_ROWS) {
                Column(
                    modifier = Modifier.padding(ZillitTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitSkeletonBar(modifier = Modifier.width(180.dp))
                    ZillitSkeletonBar(modifier = Modifier.width(260.dp))
                }
            }

            requests.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(ZillitIcons.Bank, tint = ZillitTheme.colors.textMuted)
                ZillitText(
                    text = str(S.desktop_ce_no_fund_requests),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }

            else -> requests.forEachIndexed { index, request ->
                if (index > 0) ZillitDivider()
                RequestRow(state, request, onEvent)
            }
        }
        if (!funds.loading && requests.isNotEmpty()) {
            val open = requests.filter { it.isOpen }
            val currency = (open.firstOrNull() ?: requests.first()).currency
            ZillitDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ZillitTheme.colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Caption(str(S.desktop_outstanding), modifier = Modifier.weight(1f))
                ZillitText(
                    text = state.formatMoney(open.sumOf { it.amount }, currency),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/** One request: its amount and status, the custodian, who asked; an open one adds its chain and actions. */
@Suppress("LongMethod") // One row, three bands.
@Composable
private fun RequestRow(state: CashUiState, request: FundRequest, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val cancelled = request.status == FundRequest.CANCELLED
    val stripe = colors.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (request.isOpen) colors.accentSoft.copy(alpha = OPEN_WASH) else Color.Transparent)
            .drawBehind { if (request.isOpen) drawRect(stripe, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            Box(
                modifier = Modifier
                    .size(TILE)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (cancelled) colors.surfaceSunken else colors.infoSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = request.fundAccount.ifBlank { "?" }.take(1).uppercase(),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (cancelled) colors.textMuted else colors.info,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitText(
                        text = state.formatMoney(request.receivedAmount ?: request.amount, request.currency),
                        style = ZillitTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                        ),
                        color = when {
                            cancelled -> colors.textMuted
                            request.status == FundRequest.RECEIVED -> colors.textPrimary
                            else -> colors.accent
                        },
                    )
                    FundStatusPill(request.status)
                }
                ZillitText(
                    text = request.fundAccount.ifBlank { str(S.desktop_pc_unknown_custodian) },
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                    color = if (cancelled) colors.textSecondary else colors.textPrimary,
                )
                CashPerson(
                    userId = request.requestedBy,
                    secondary = designationOf(state, request.requestedBy),
                    size = 36.dp,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ZillitText(
                    text = EpochDate.date(request.requestedAt).ifEmpty { "—" },
                    style = ZillitTheme.typography.numeric,
                    color = colors.textMuted,
                )
                if (cancelled) {
                    ZillitButton(
                        text = str(S.action_duplicate),
                        onClick = { onEvent(CashEvent.Funds(FundsAction.DuplicateFunds(request.id))) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
        if (request.isOpen) {
            ZillitDivider()
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                Chain(request, modifier = Modifier.weight(1f))
                ZillitButton(
                    text = str(S.desktop_ce_mark_received),
                    onClick = { onEvent(CashEvent.Funds(FundsAction.ReceiveFunds(request.id))) },
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(CashEvent.Funds(FundsAction.CancelFunds(request.id))) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            }
        }
    }
}

/** "In progress" amber, "Received" teal, "Cancelled" grey — `STATUS_META`. */
@Composable
private fun FundStatusPill(status: String) {
    val (label, tone) = when (status) {
        FundRequest.RECEIVED -> str(S.received_text) to StatusTone.Done
        FundRequest.CANCELLED -> str(S.cancelled) to StatusTone.Neutral
        else -> str(S.ds_sent_filter_in_progress) to StatusTone.Pending
    }
    ZillitStatusPill(label = label, tone = tone, dot = true)
}

private enum class FundStep { Done, Active, Pending }

private data class ChainStep(val label: String, val step: FundStep, val person: String, val time: String?)

/**
 * Requested → In progress → Received, built only from what the row carries:
 * who asked and when, the custodian the money comes out of, who received it
 * (`RequestCashFundsModal.jsx:239-267`).
 */
@Composable
private fun Chain(request: FundRequest, modifier: Modifier = Modifier) {
    val people = LocalCashPeople.current
    val done = request.status == FundRequest.RECEIVED
    val cancelled = request.status == FundRequest.CANCELLED
    val steps = listOf(
        ChainStep(
            label = str(S.av_chip_requested),
            step = FundStep.Done,
            person = people.nameOf(request.requestedBy),
            time = EpochDate.dateTime(request.requestedAt).ifEmpty { null },
        ),
        ChainStep(
            label = if (cancelled) str(S.cancelled) else str(S.ds_sent_filter_in_progress),
            step = when {
                cancelled -> FundStep.Pending
                done -> FundStep.Done
                else -> FundStep.Active
            },
            person = request.fundAccount.ifBlank { "—" },
            time = if (done || cancelled) null else str(S.desktop_pc_since, EpochDate.date(request.requestedAt)),
        ),
        ChainStep(
            label = str(S.received_text),
            step = if (done) FundStep.Done else FundStep.Pending,
            person = if (done) people.nameOf(request.receivedBy) else str(S.ds_sent_filter_awaiting),
            time = if (done) EpochDate.dateTime(request.receivedAt).ifEmpty { null } else null,
        ),
    )
    Row(modifier = modifier) {
        steps.forEachIndexed { index, step ->
            ChainNode(step, last = index == steps.lastIndex, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ChainNode(step: ChainStep, last: Boolean, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier.padding(end = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(NODE)
                    .clip(CircleShape)
                    .background(if (step.step == FundStep.Done) colors.success else colors.surface)
                    .border(
                        1.dp,
                        when (step.step) {
                            FundStep.Done -> colors.success
                            FundStep.Active -> colors.accent
                            FundStep.Pending -> colors.borderStrong
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (step.step == FundStep.Done) ZillitIcon(ZillitIcons.Check, tint = colors.textOnAccent, size = 11.dp)
            }
            if (!last) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(if (step.step == FundStep.Done) colors.success else colors.border),
                )
            }
        }
        Caption(step.label)
        ZillitText(
            text = step.person,
            style = ZillitTheme.typography.bodySmall,
            color = if (step.step == FundStep.Pending) colors.textMuted else colors.textPrimary,
            maxLines = 1,
        )
        step.time?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

/** The right panel: the custodian (shown), the currency (picked, project default first) and the amount. */
@Suppress("LongMethod") // Three fields and the button.
@Composable
private fun NewRequestForm(state: CashUiState, funds: FundsState, onEvent: (CashEvent) -> Unit, modifier: Modifier) {
    val currency = fundsCurrency(state)
    val amount = funds.amount.trim().toDoubleOrNull()
    val canSubmit = funds.fundAccount.isNotBlank() && currency.isNotBlank() &&
        amount != null && amount > 0 && !state.busy
    val codes = (state.currencies.currencies.map { it.code } + listOfNotNull(state.currencies.defaultCode) + currency)
        .filter(String::isNotBlank)
        .distinct()
    FundsCard(modifier = modifier) {
        ZillitText(
            text = str(S.desktop_ce_new_fund_request),
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
        )
        ZillitDivider()
        Column(
            modifier = Modifier.padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Caption(str(S.desktop_ce_fund_account))
                ZillitTextField(
                    value = funds.fundAccount,
                    onValueChange = {},
                    readOnly = true,
                    placeholder = str(S.desktop_pc_no_custodian),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (funds.fundAccount.isBlank()) {
                    ZillitText(
                        text = str(S.desktop_pc_set_custodian_hint),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.warning,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Caption(str(S.ah_lbl_currency))
                if (codes.isEmpty()) {
                    ZillitTextField(
                        value = funds.currency,
                        onValueChange = { onEvent(CashEvent.EditFunds(funds.copy(currency = it.uppercase()))) },
                        placeholder = str(S.ah_select_currency),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ZillitSelect(
                        value = currency.ifBlank { codes.first() },
                        options = codes,
                        onSelect = { onEvent(CashEvent.EditFunds(funds.copy(currency = it))) },
                        label = { code ->
                            state.currencies.symbolFor(code).takeIf(String::isNotBlank)?.let { "$code ($it)" } ?: code
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Caption(str(S.amount))
                ZillitTextField(
                    value = funds.amount,
                    onValueChange = { onEvent(CashEvent.EditFunds(funds.copy(amount = it))) },
                    placeholder = "0.00",
                    keyboardType = KeyboardType.Decimal,
                    trailingContent = {
                        ZillitText(
                            text = currency,
                            style = ZillitTheme.typography.labelSmall,
                            color = ZillitTheme.colors.textMuted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ZillitDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = 14.dp),
        ) {
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = if (state.busy) str(S.desktop_pc_requesting) else str(S.desktop_ce_request_funds),
                onClick = { onEvent(CashEvent.SubmitFunds) },
                enabled = canSubmit,
                loading = state.busy,
            )
        }
    }
}

private fun designationOf(state: CashUiState, userId: String?): String? =
    userId?.let { id -> state.assignees.firstOrNull { it.userId == id }?.designation?.takeIf(String::isNotBlank) }

private const val SKELETON_ROWS = 3
private const val OPEN_WASH = 0.35f
private val FORM_WIDTH = 400.dp
private val TILE = 34.dp
private val NODE = 17.dp
