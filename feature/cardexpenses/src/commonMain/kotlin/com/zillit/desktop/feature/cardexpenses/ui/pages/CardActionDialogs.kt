package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardNumbers
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.CrewCardDraft
import com.zillit.desktop.feature.cardexpenses.ui.capHint
import com.zillit.desktop.feature.cardexpenses.ui.capVerdict
import com.zillit.desktop.feature.cardexpenses.ui.components.FormLabel
import com.zillit.desktop.feature.cardexpenses.ui.defaultCurrency

/**
 * The register's and the Card tab's action dialogs — reject, override,
 * assign a physical card, delete — and the cardholder's own request and
 * re-submit. Drawn at the page root, over the grid or the open card.
 */
@Composable
fun CardActionDialogs(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    RejectCardDialog(state, onEvent)
    OverrideCardDialog(state, onEvent)
    AssignPhysicalDialog(state, onEvent)
    DeleteCardDialog(state, onEvent)
    CrewRequestDialog(state, onEvent)
    CrewEditDialog(state, onEvent)
}

private fun CardUiState.holderOf(cardId: String?): String =
    cards.firstOrNull { it.id == cardId }?.let { card -> people.firstOrNull { it.id == card.holderId }?.name }
        ?: str(S.desktop_card_card_holder)

/** Reject Card Request — a reason is required (`CardRegisterPage.jsx:1168-1208`). */
@Composable
private fun RejectCardDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.reject ?: return
    val busy = state.busy
    ZillitDialogShell(
        title = str(S.desktop_ce_cards_reject_title),
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = if (busy) str(S.desktop_ce_cards_rejecting) else str(S.desktop_ce_cards_reject_card),
                onClick = { onEvent(CardsEvent.ConfirmReject) },
                variant = ButtonVariant.Danger,
                enabled = draft.reason.isNotBlank() && !busy,
                loading = busy,
            )
        },
    ) {
        Intro(str(S.desktop_ce_cards_reject_body, state.holderOf(draft.cardId)))
        FormLabel(str(S.reason), required = true)
        ZillitTextField(
            value = draft.reason,
            onValueChange = { onEvent(CardsEvent.EditRejectReason(it)) },
            placeholder = str(S.desktop_ce_cards_reject_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Override Card Approval — the reason is optional (`CardRegisterPage.jsx:1210-1255`). */
@Composable
private fun OverrideCardDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.override ?: return
    val busy = state.busy
    ZillitDialogShell(
        title = str(S.desktop_ce_cards_override_title),
        icon = ZillitIcons.Shield,
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = if (busy) str(S.desktop_ce_cards_overriding) else str(S.dm_nom_table_override),
                onClick = { onEvent(CardsEvent.ConfirmOverride) },
                enabled = !busy,
                loading = busy,
            )
        },
    ) {
        DialogNote(str(S.desktop_ce_cards_override_body, state.holderOf(draft.cardId)), StatusTone.Pending)
        ZillitTextField(
            value = draft.reason,
            onValueChange = { onEvent(CardsEvent.EditOverrideReason(it)) },
            placeholder = str(S.desktop_ce_cards_override_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Assign Physical Card (`CardRegisterPage.jsx:1399-1517`): whose card, the
 * virtual number it replaces, exactly sixteen digits for the plastic, and the
 * warning that the digital card must be retired at the issuer.
 */
@Composable
private fun AssignPhysicalDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.assignPhysical ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    val holder = state.people.firstOrNull { it.id == card?.holderId }
    val busy = state.busy
    ZillitDialogShell(
        title = str(S.desktop_ce_cards_assign_physical),
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = if (busy) str(S.desktop_ce_cards_assigning) else str(S.desktop_ce_cards_assign_physical),
                onClick = { onEvent(CardsEvent.ConfirmAssignPhysical) },
                enabled = draft.digits.length == CardNumbers.LENGTH && !busy,
                loading = busy,
            )
        },
    ) {
        TwoUp(
            left = {
                FormLabel(str(S.desktop_card_card_holder))
                ZillitText(
                    text = holder?.name ?: "—",
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                holder?.designation?.takeIf { it.isNotBlank() }?.let { Hint(it) }
            },
            right = {
                FormLabel(str(S.department))
                ZillitText(text = holder?.department?.takeIf { it.isNotBlank() } ?: "—")
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(ZillitTheme.colors.tealSoft)
                .border(1.dp, ZillitTheme.colors.teal, ZillitTheme.shapes.large)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            FormLabel(str(S.desktop_ce_cards_current_digital))
            ZillitText(
                text = CardNumbers.masked(card?.digitalCardNumber) ?: "—",
                style = ZillitTheme.typography.titleSmall,
                color = ZillitTheme.colors.teal,
            )
        }
        FormLabel(str(S.desktop_ce_cards_physical_number), required = true)
        CardNumberField(draft.digits) { onEvent(CardsEvent.EditPhysicalNumber(it)) }
        DialogNote(str(S.desktop_ce_cards_physical_warning), StatusTone.Pending)
    }
}

/**
 * Delete Card? — or, on the Card tab, Delete Card Request? — naming the last
 * four where there are any (`CardRegisterPage.jsx:1647-1659`, `UserCardsPage.jsx:537-547`).
 */
@Composable
private fun DeleteCardDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val cardId = state.cardsArea.deleteCardId ?: return
    val last4 = state.cards.firstOrNull { it.id == cardId }?.lastFour?.takeIf { it.isNotBlank() }
    val accountant = state.viewer.isAccountant
    val message = when {
        accountant && last4 != null -> str(S.desktop_ce_cards_delete_body_ending, last4)
        accountant -> str(S.desktop_ce_cards_delete_body)
        last4 != null -> str(S.desktop_ce_cards_delete_request_body_ending, last4)
        else -> str(S.desktop_ce_cards_delete_request_body)
    }
    ZillitDialogShell(
        title = if (accountant) str(S.desktop_ce_cards_delete_title) else str(S.desktop_ce_cards_delete_request_title),
        icon = ZillitIcons.Trash,
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CardsEvent.CloseDialog) },
                variant = ButtonVariant.Tertiary,
                enabled = !state.busy,
            )
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(CardsEvent.ConfirmDelete) },
                variant = ButtonVariant.Danger,
                enabled = !state.busy,
                loading = state.busy,
            )
        },
    ) {
        Intro(message)
    }
}

/**
 * A cardholder's own request (`UserCardsPage.jsx:398-467`): who and which
 * department, read-only; the proposed limit with the project cap under it;
 * the currency; the justification; and the note that the accounts team
 * assigns everything else.
 */
@Composable
private fun CrewRequestDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.crewRequest ?: return
    val me = state.people.firstOrNull { it.id == state.viewer.userId }
    ZillitDialogShell(
        title = str(S.desktop_ce_cards_request_new_card),
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = if (state.busy) str(S.desktop_ce_cards_submitting) else str(S.desktop_ce_cards_submit_request),
                onClick = { onEvent(CardsEvent.SubmitCrewRequest) },
                enabled = !state.busy,
                loading = state.busy,
            )
        },
    ) {
        HolderAndDepartment(me)
        CrewFields(state, draft, S.desktop_ce_cards_justification_placeholder) {
            onEvent(CardsEvent.EditCrewRequest(it))
        }
        DialogNote(str(S.desktop_ce_cards_crew_note), StatusTone.Neutral)
    }
}

/** A cardholder re-submitting their request; refusals stay in the dialog (`UserCardsPage.jsx:469-535`). */
@Composable
private fun CrewEditDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardsArea.crewEdit ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    ZillitDialogShell(
        title = str(S.desktop_ce_cards_edit_card_details),
        icon = ZillitIcons.Edit,
        visible = true,
        onDismiss = { onEvent(CardsEvent.CloseDialog) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = if (state.busy) str(S.desktop_ce_cards_submitting) else str(S.desktop_ce_cards_resubmit),
                onClick = { onEvent(CardsEvent.SubmitCrewEdit) },
                enabled = !state.busy,
                loading = state.busy,
            )
        },
    ) {
        HolderAndDepartment(state.people.firstOrNull { it.id == card?.holderId })
        CrewFields(state, draft, S.desktop_ce_cards_justification_placeholder) {
            onEvent(CardsEvent.EditCrewEdit(it))
        }
        draft.error?.let {
            ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
        }
    }
}

/** Proposed limit (with the cap hint) and currency side by side, then the justification. */
@Composable
private fun CrewFields(
    state: CardUiState,
    draft: CrewCardDraft,
    justificationPlaceholder: String,
    onChange: (CrewCardDraft) -> Unit,
) {
    val currency = draft.currency.ifBlank { state.defaultCurrency }
    TwoUp(
        left = {
            FormLabel(str(S.desktop_ce_cards_proposed_limit), required = true)
            LimitField(draft.limit, currency, hint = capHint(state.capVerdict(currency), state.cardsArea.reference)) {
                onChange(draft.copy(limit = it))
            }
        },
        right = {
            FormLabel(str(S.asset_currency), required = true)
            CurrencyPicker(state, currency) { onChange(draft.copy(currency = it)) }
        },
    )
    FormLabel(str(S.desktop_card_justification), required = true)
    ZillitTextField(
        value = draft.justification,
        onValueChange = { onChange(draft.copy(justification = it)) },
        placeholder = str(justificationPlaceholder),
        singleLine = false,
        modifier = Modifier.fillMaxWidth(),
    )
}
