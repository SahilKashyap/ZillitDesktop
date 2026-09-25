package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.domain.FundRouting
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.InsightsEvent
import com.zillit.desktop.feature.cardexpenses.ui.components.CardCalcInput
import com.zillit.desktop.feature.cardexpenses.ui.components.InsightEyebrow

/**
 * Fund Requests — a full page, as the web's `RequestFundsModal.jsx:336-627`
 * is: the requests on the left, the fixed-width "New request" panel on the
 * right, under a breadcrumb back to the cards. Opened from the register's
 * Funds button ([CardEvent.OpenFunds]).
 *
 * Raising a request has no accounting effect; Mark received is what posts
 * (debit the bank's nominal, credit the fund account).
 */
@Composable
fun FundRequestsPage(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val funds = state.funds ?: return
    Column(Modifier.fillMaxSize()) {
        Breadcrumb(onEvent)
        ZillitDivider()
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(ZillitTheme.spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ZillitScrollColumn(modifier = Modifier.weight(1f).fillMaxHeight()) {
                RequestList(state, funds.requests, funds.loading, funds.acting, onEvent)
            }
            ZillitScrollColumn(modifier = Modifier.width(PANEL_WIDTH).fillMaxHeight()) {
                NewRequestPanel(state, onEvent)
            }
        }
    }
}

@Composable
private fun Breadcrumb(onEvent: (CardEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.desktop_ce_cards_back_to_cards),
            onClick = { onEvent(CardEvent.CloseFunds) },
        )
        ZillitText(
            text = str(S.ah_card_expenses).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.accent,
            modifier = Modifier.clickable { onEvent(CardEvent.CloseFunds) },
        )
        ZillitText(text = "/", color = ZillitTheme.colors.textMuted)
        ZillitText(
            text = str(S.desktop_ce_fund_requests),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun RequestList(
    state: CardUiState,
    requests: List<FundRequest>,
    loading: Boolean,
    acting: String?,
    onEvent: (CardEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().panel(ZillitTheme.colors.surface, ZillitTheme.colors.border)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.desktop_ce_fund_requests),
                style = ZillitTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (!loading) {
                ZillitText(
                    text = if (requests.size == 1) {
                        str(S.desktop_pc_request_count_one, 1)
                    } else {
                        str(S.desktop_pc_request_count, requests.size)
                    },
                    style = ZillitTheme.typography.numeric,
                    color = colors.textMuted,
                )
            }
        }
        ZillitDivider()
        when {
            loading -> Row(Modifier.fillMaxWidth().padding(24.dp), Arrangement.Center) { ZillitSpinner() }
            requests.isEmpty() -> Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZillitIcon(ZillitIcons.Wallet, tint = colors.textMuted, size = 20.dp)
                ZillitText(text = str(S.desktop_ce_no_fund_requests), color = colors.textMuted)
            }

            else -> requests.forEachIndexed { index, request ->
                if (index > 0) ZillitDivider()
                RequestRow(state, request, acting, onEvent)
            }
        }
        if (!loading && requests.isNotEmpty()) {
            ZillitDivider()
            val open = requests.filter { it.open }
            val symbol = symbolFor(state, open.firstOrNull() ?: requests.first())
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InsightEyebrow(str(S.desktop_outstanding), modifier = Modifier.weight(1f))
                ZillitText(
                    text = "$symbol${Money.group(open.sumOf { it.amount }, 2)}",
                    style = ZillitTheme.typography.numeric,
                )
            }
        }
    }
}

/** One request: bank tile, amount, pill, bank and code, requester, date — and the chain while open. */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The web's three-column row plus its open-row chain.
@Composable
private fun RequestRow(state: CardUiState, request: FundRequest, acting: String?, onEvent: (CardEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val bank = state.banks.firstOrNull { it.id == request.bankId }
    val cancelled = request.status == FundRequest.CANCELLED
    val symbol = symbolFor(state, request)
    val requester = state.people.firstOrNull { it.id == request.requestedBy }
    Column(
        Modifier.fillMaxWidth()
            .background(if (request.open) colors.warningSoft.copy(alpha = 0.35f) else colors.surface)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (cancelled) colors.surfaceHover else colors.infoSoft),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(
                    text = (bank?.name?.firstOrNull() ?: '?').uppercase(),
                    style = ZillitTheme.typography.titleSmall,
                    color = if (cancelled) colors.textSecondary else colors.info,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZillitText(
                        text = "$symbol${Money.group(request.receivedAmount ?: request.amount, 2)}",
                        style = ZillitTheme.typography.titleMedium.copy(
                            textDecoration = if (cancelled) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        color = when (request.status) {
                            FundRequest.CANCELLED -> colors.textMuted
                            FundRequest.RECEIVED -> colors.textPrimary
                            else -> colors.accent
                        },
                    )
                    StatusBadge(request.status)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        text = bank?.name ?: str(S.desktop_ce_insights_unknown_bank),
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (cancelled) colors.textSecondary else colors.textPrimary,
                    )
                    request.fundAccount.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = str(S.desktop_dm_range_from, it),
                            style = ZillitTheme.typography.numeric,
                            color = colors.textSecondary,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitAvatar(name = requester?.name ?: "—", userId = request.requestedBy, size = 36.dp)
                    Column {
                        ZillitText(
                            text = requester?.name ?: "—",
                            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                        requester?.designation?.takeIf { it.isNotBlank() }?.let {
                            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                        }
                    }
                }
            }
            ZillitText(
                text = EpochDate.date(request.requestedAt).ifEmpty { "—" },
                style = ZillitTheme.typography.numeric,
                color = colors.textMuted,
            )
        }
        if (request.open) {
            ZillitDivider()
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Chain(state, request, bank, Modifier.weight(1f))
                val busy = acting == request.id
                ZillitButton(
                    text = if (busy) str(S.desktop_working_ellipsis) else str(S.desktop_ce_mark_received),
                    onClick = { onEvent(CardEvent.ReceiveFundRequest(request.id)) },
                    size = ButtonSize.Small,
                    enabled = !busy,
                    loading = busy,
                )
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(CardEvent.CancelFundRequest(request.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = !busy,
                )
            }
        }
    }
}

/** The status pill; "In progress" pulses (`STATUS_META`). */
@Composable
private fun StatusBadge(status: String) {
    val colors = ZillitTheme.colors
    val (label, tint, background) = when (status) {
        FundRequest.RECEIVED -> Triple(str(S.recived), colors.teal, colors.tealSoft)
        FundRequest.CANCELLED -> Triple(str(S.cancelled), colors.textSecondary, colors.surfaceHover)
        else -> Triple(str(S.ds_sent_filter_in_progress), colors.warning, colors.warningSoft)
    }
    val pulse = rememberInfiniteTransition(label = "fundPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (status == FundRequest.REQUESTED) 0.3f else 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "fundPulseAlpha",
    )
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(tint))
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

/** Requested → In progress → Received, built from what the row carries (`chainFor`). */
@Suppress("CyclomaticComplexMethod") // Each step's label, state, person and time depend on the status.
@Composable
private fun Chain(state: CardUiState, request: FundRequest, bank: CardBank?, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val received = request.status == FundRequest.RECEIVED
    val cancelled = request.status == FundRequest.CANCELLED
    val steps = listOf(
        ChainStep(
            str(S.av_chip_requested),
            ChainState.Done,
            state.personName(request.requestedBy),
            EpochDate.dateTime(request.requestedAt),
        ),
        ChainStep(
            if (cancelled) str(S.cancelled) else str(S.ds_sent_filter_in_progress),
            when {
                cancelled -> ChainState.Pending
                received -> ChainState.Done
                else -> ChainState.Active
            },
            bank?.name ?: "—",
            if (received || cancelled) "" else str(S.desktop_pc_since, EpochDate.date(request.requestedAt)),
        ),
        ChainStep(
            str(S.recived),
            if (received) ChainState.Done else ChainState.Pending,
            if (received) state.personName(request.receivedBy) else str(S.ds_sent_filter_awaiting),
            if (received) EpochDate.dateTime(request.receivedAt) else "",
        ),
    )
    Row(modifier) {
        steps.forEach { step ->
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val (fill, ring) = when (step.state) {
                    ChainState.Done -> colors.success to colors.success
                    ChainState.Active -> colors.surface to colors.accent
                    ChainState.Pending -> colors.surface to colors.borderStrong
                }
                Box(
                    Modifier.size(17.dp).clip(CircleShape).background(fill).border(1.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (step.state == ChainState.Done) ZillitIcon(ZillitIcons.Check, tint = Color.White, size = 11.dp)
                }
                InsightEyebrow(step.label)
                ZillitText(
                    text = step.person,
                    style = ZillitTheme.typography.bodySmall,
                    color = if (step.state == ChainState.Pending) colors.textMuted else colors.textPrimary,
                    maxLines = 1,
                )
                if (step.time.isNotEmpty()) {
                    ZillitText(text = step.time, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
                }
            }
        }
    }
}

private enum class ChainState { Done, Active, Pending }

private data class ChainStep(val label: String, val state: ChainState, val person: String, val time: String)

/**
 * "New request": pay into, fund account, amount (`RequestFundsModal.jsx:524-625`).
 * Only banks bound to a provider are offered, and the bank and custodian fill
 * each other when the answer is unambiguous.
 */
@Suppress("LongMethod") // The form's three fields and its footer.
@Composable
private fun NewRequestPanel(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val funds = state.funds ?: return
    val draft = funds.draft
    val providers = state.providers
    val banks = FundRouting.providerBanks(state.banks, providers)
    val options = FundRouting.options(providers, draft.fundAccount, str(S.desktop_ce_insights_unnamed_provider))
    val bank = state.banks.firstOrNull { it.id == draft.bankId }
    Column(Modifier.fillMaxWidth().panel(ZillitTheme.colors.surface, ZillitTheme.colors.border)) {
        ZillitText(
            text = str(S.desktop_ce_new_fund_request),
            style = ZillitTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        ZillitDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                InsightEyebrow(str(S.desktop_ce_insights_pay_into))
                ZillitSelect(
                    value = banks.firstOrNull { it.id == draft.bankId },
                    options = listOf<CardBank?>(null) + banks,
                    onSelect = { onEvent(InsightsEvent.PickFundBank(it?.id.orEmpty())) },
                    label = { choice ->
                        when {
                            choice != null -> listOfNotNull(choice.name, choice.currency).joinToString(" · ")
                            banks.isEmpty() -> str(S.desktop_ce_insights_no_provider_bank)
                            else -> str(S.desktop_ce_insights_select_bank_account)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                InsightEyebrow(str(S.desktop_ce_fund_account))
                // A code outside the configured custodians is still valid —
                // the backend takes any nominal — so it can be typed.
                ZillitTextField(
                    value = draft.fundAccount,
                    onValueChange = { onEvent(InsightsEvent.PickFundAccount(it)) },
                    placeholder = if (options.none { !it.custom }) {
                        str(S.desktop_ce_insights_no_custodians)
                    } else {
                        str(S.desktop_ce_insights_select_custodian)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                options.filterNot { it.custom }.forEach { option ->
                    ZillitChoiceChip(
                        label = "${option.code} · ${option.providers.joinToString(", ")}",
                        selected = option.code == draft.fundAccount.trim(),
                        onClick = { onEvent(InsightsEvent.PickFundAccount(option.code)) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                InsightEyebrow(str(S.amount))
                CardCalcInput(
                    value = draft.amount,
                    onValueChange = { onEvent(CardEvent.EditFundDraft(draft.copy(amount = it))) },
                    suffix = bank?.symbol ?: bank?.currency,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ZillitDivider()
        Row(
            Modifier.fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ZillitButton(
                text = if (state.busy) str(S.desktop_pc_requesting) else str(S.desktop_ce_request_funds),
                onClick = { onEvent(CardEvent.SubmitFundRequest) },
                enabled = draft.complete && !state.busy,
                loading = state.busy,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

/** A request's symbol: its bank's, else its own currency code (`symbolFor`). */
private fun symbolFor(state: CardUiState, request: FundRequest): String {
    val bank = state.banks.firstOrNull { it.id == request.bankId }
    return bank?.symbol
        ?: bank?.currency?.let { Money.symbol(it) }
        ?: request.currency?.let { Money.symbol(it) }.orEmpty()
}

private fun Modifier.panel(surface: Color, border: Color): Modifier =
    this.clip(PANEL_SHAPE).background(surface).border(1.dp, border, PANEL_SHAPE)

private const val PULSE_MS = 900
private val PANEL_WIDTH = 400.dp
private val PANEL_SHAPE = RoundedCornerShape(14.dp)
