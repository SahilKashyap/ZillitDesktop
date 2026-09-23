package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.date
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * A receipt's query thread (`QueryPanel.jsx`): who asked what, oldest first,
 * and a line to add to it. The first message opens the thread.
 */
@Suppress("LongMethod") // The thread and its composer, read top to bottom.
@Composable
fun QueryDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val query = state.query ?: return
    ZillitDialogShell(
        title = str(S.ah_query_label),
        subtitle = query.title,
        icon = ZillitIcons.Info,
        visible = true,
        width = QUERY_WIDTH,
        onDismiss = { onEvent(CardEvent.CloseQuery) },
        actions = {
            ZillitButton(
                text = str(S.close),
                onClick = { onEvent(CardEvent.CloseQuery) },
                variant = ButtonVariant.Tertiary,
            )
            Spacer(Modifier.weight(1f))
            ZillitButton(
                text = str(S.send),
                onClick = { onEvent(CardEvent.SendQuery) },
                leadingIcon = ZillitIcons.Send,
                enabled = query.text.isNotBlank() && !query.loading && !query.sending,
                loading = query.sending,
            )
        },
    ) {
        when {
            query.loading -> Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
            query.thread.messages.isEmpty() -> ZillitText(
                text = str(S.ah_no_queries_yet),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )

            else -> query.thread.messages.forEach { message ->
                val mine = message.userId == state.viewer.userId
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                ) {
                    ZillitText(
                        text = listOfNotNull(
                            if (mine) str(S.txt_me) else state.personName(message.userId),
                            date(message.at).takeIf { it != "—" },
                        ).joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                    ZillitText(text = message.text, style = ZillitTheme.typography.bodyMedium)
                }
            }
        }
        ZillitTextField(
            value = query.text,
            onValueChange = { onEvent(CardEvent.EditQuery(it)) },
            placeholder = str(S.type_a_message),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Fund Requests — the register's "Funds" (`RequestFundsModal.jsx`): the
 * requests on the left of the web's page, the new-request form under them
 * here. Raising one has no accounting effect; marking it received posts.
 */
@Suppress("LongMethod") // The list and the form, which belong together.
@Composable
fun FundsDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val funds = state.funds ?: return
    val draft = funds.draft
    ZillitDialogShell(
        title = str(S.desktop_card_fund_requests),
        subtitle = str(S.desktop_card_fund_requests_note),
        icon = ZillitIcons.Wallet,
        visible = true,
        width = FUNDS_WIDTH,
        onDismiss = { onEvent(CardEvent.CloseFunds) },
    ) {
        if (funds.loading) {
            Row(Modifier.fillMaxWidth(), Arrangement.Center) { ZillitSpinner() }
        } else if (funds.requests.isEmpty()) {
            ZillitText(
                text = str(S.desktop_ce_no_fund_requests),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        funds.requests.forEach { request -> FundRow(state, request, onEvent) }

        ZillitDivider()
        FieldGroupLabel(str(S.desktop_card_request_funds))
        if (state.banks.isEmpty()) {
            ZillitNotice(text = str(S.desktop_card_no_banks_note), tone = StatusTone.Pending, icon = ZillitIcons.Info)
        } else {
            ZillitSelect(
                value = state.banks.firstOrNull { it.id == draft.bankId },
                options = state.banks,
                onSelect = { bank -> onEvent(CardEvent.EditFundDraft(draft.copy(bankId = bank?.id.orEmpty()))) },
                label = { bank ->
                    bank?.let { listOfNotNull(it.name, it.currency).joinToString(" · ") } ?: str(S.dm_pay_card_bank)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(ZillitTheme.spacing.sm), Alignment.Bottom) {
            ZillitTextField(
                value = draft.fundAccount,
                onValueChange = { onEvent(CardEvent.EditFundDraft(draft.copy(fundAccount = it))) },
                label = str(S.desktop_ce_fund_account),
                placeholder = "2100",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.amount,
                onValueChange = { typed ->
                    onEvent(CardEvent.EditFundDraft(draft.copy(amount = typed.filter { it.isDigit() || it == '.' })))
                },
                label = str(S.amount),
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = str(S.desktop_card_request_funds),
                onClick = { onEvent(CardEvent.SubmitFundRequest) },
                enabled = draft.complete && !state.busy,
                loading = state.busy,
            )
        }
    }
}

@Composable
private fun FundRow(state: CardUiState, request: FundRequest, onEvent: (CardEvent) -> Unit) {
    val bank = state.banks.firstOrNull { it.id == request.bankId }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = money(request.receivedAmount ?: request.amount, request.currency ?: bank?.currency),
                style = ZillitTheme.typography.titleSmall,
            )
            ZillitText(
                text = listOfNotNull(
                    bank?.name,
                    request.fundAccount.takeIf { it.isNotBlank() },
                    state.personName(request.requestedBy).takeIf { it != "—" },
                    date(request.requestedAt).takeIf { it != "—" },
                ).joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
        ZillitStatusPill(
            label = when (request.status) {
                FundRequest.RECEIVED -> str(S.received_text)
                FundRequest.CANCELLED -> str(S.cancelled)
                else -> str(S.in_progress)
            },
            tone = when (request.status) {
                FundRequest.RECEIVED -> StatusTone.Done
                FundRequest.CANCELLED -> StatusTone.Neutral
                else -> StatusTone.Pending
            },
            dot = true,
        )
        if (request.open) {
            ZillitButton(
                text = str(S.desktop_ce_mark_received),
                onClick = { onEvent(CardEvent.ReceiveFundRequest(request.id)) },
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
            ZillitButton(
                text = str(S.desktop_card_cancel_request),
                onClick = { onEvent(CardEvent.CancelFundRequest(request.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !state.busy,
            )
        }
    }
}

private val QUERY_WIDTH = 560.dp
private val FUNDS_WIDTH = 760.dp
