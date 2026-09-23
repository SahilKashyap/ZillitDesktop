package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.ui.CardEditDraft
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.NewCardDraft
import com.zillit.desktop.feature.cardexpenses.ui.components.FieldGroupLabel
import com.zillit.desktop.feature.cardexpenses.ui.money

/**
 * Raising a card.
 *
 * One dialog for both surfaces — a cardholder asking for their own and an
 * accountant issuing one to someone else. The holder picker is the only
 * difference and it is a branch inside, not a second form: the web kept two
 * copies and they drifted apart over the one-card-per-user rule, so a crew
 * member was refused for a reason the accountant's copy never checked.
 */
@Suppress("LongMethod") // One form, read top to bottom; splitting it hides the order.
@Composable
fun NewCardDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.newCard ?: return
    val accountant = state.viewer.isAccountant
    val providers = state.providers
    val blocked = draft.validationError(providers.isNotEmpty(), holderRequired = accountant)

    ZillitDialogShell(
        title = if (accountant) str(S.desktop_card_issue_a_card) else str(S.desktop_card_request_a_card),
        subtitle = if (accountant) {
            str(S.desktop_card_issue_subtitle)
        } else {
            str(S.desktop_card_request_subtitle)
        },
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseNewCard) },
        width = FORM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CardEvent.CloseNewCard) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (accountant) str(S.desktop_card_issue_card) else str(S.av_send_request),
                onClick = { onEvent(CardEvent.SubmitNewCard) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        if (accountant) {
            FieldGroupLabel(str(S.desktop_cardholder))
            HolderPicker(
                people = state.eligibleHolders,
                selectedId = draft.holderId,
                onSelect = { onEvent(CardEvent.EditNewCard(draft.copy(holderId = it))) },
            )
            if (state.people.isNotEmpty() && state.eligibleHolders.size < state.people.size) {
                ZillitText(
                    text = str(
                        S.desktop_card_crew_already_hold,
                        state.people.size - state.eligibleHolders.size,
                    ),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }

        FieldGroupLabel(str(S.desktop_card_the_card))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.proposedLimit,
                onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(proposedLimit = it))) },
                label = str(S.desktop_card_proposed_limit),
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                helperText = str(S.desktop_card_authorised_for_helper),
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.currency,
                onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(currency = it.uppercase()))) },
                label = str(S.ah_lbl_currency),
                placeholder = "GBP",
                modifier = Modifier.weight(CURRENCY_FIELD),
            )
        }

        ProviderPicker(
            providers = providers,
            selectedId = draft.providerId,
            // The provider binds a bank and a company: `card_issuer` is the
            // bank's id and the company rides along (CardRegisterPage.jsx:217-222).
            // The desktop sent the provider's *name* as the issuer.
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditNewCard(
                        draft.copy(
                            providerId = provider?.id.orEmpty(),
                            issuer = provider?.bankId.orEmpty(),
                            companyId = provider?.companyId.orEmpty(),
                        ),
                    ),
                )
            },
        )

        ZillitTextField(
            value = draft.bsControlCode,
            onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(bsControlCode = it))) },
            label = str(S.desktop_card_bs_control_code),
            placeholder = "2100",
            helperText = str(S.desktop_card_control_account_helper),
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(justification = it))) },
            label = str(S.desktop_card_justification),
            placeholder = str(S.desktop_card_justification_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        if (blocked != null) {
            ZillitText(
                text = blocked,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * An accountant's edit of a card request.
 *
 * The warning is not decoration. Saving stamps a status the server reads as a
 * resubmit, which wipes every approval the card has already collected — so
 * whoever is about to change a limit is told before they do, not after their
 * head of department asks why they are being asked again.
 */
@Suppress("LongMethod") // One form; see NewCardDialog.
@Composable
fun CardEditDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardEdit ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    val providers = state.providers
    val blocked = draft.validationError(providers.isNotEmpty())

    ZillitDialogShell(
        title = str(S.desktop_card_edit_card_details),
        subtitle = card?.holderName?.takeIf { it.isNotBlank() }
            ?.let { str(S.desktop_card_holder_card_suffix, it, card.lastFour ?: "—") },
        icon = ZillitIcons.Edit,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseCardEdit) },
        width = FORM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CardEvent.CloseCardEdit) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_card_save_and_resubmit),
                onClick = { onEvent(CardEvent.SaveCardEdit) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        ZillitNotice(
            text = str(S.desktop_card_resubmit_warning),
            tone = StatusTone.Pending,
            icon = ZillitIcons.Warning,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.limit,
                onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(limit = it))) },
                label = str(S.desktop_card_authorised_limit),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.currency,
                onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(currency = it.uppercase()))) },
                label = str(S.ah_lbl_currency),
                modifier = Modifier.weight(CURRENCY_FIELD),
            )
        }

        // The balance moves with the limit, and showing the arithmetic before
        // it is committed is the difference between a considered change and a
        // surprise on the holder's card.
        if (draft.limitValue > 0 && draft.limitValue != draft.currentLimit) {
            ZillitText(
                text = str(
                    S.desktop_card_new_balance_note,
                    money(draft.newBalance, draft.currency),
                    money(draft.currentBalance, draft.currency),
                ),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        ProviderPicker(
            providers = providers,
            selectedId = draft.providerId,
            // A provider pick rewrites the bank and the company from its binding.
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditCardDraft(
                        draft.copy(
                            providerId = provider?.id.orEmpty(),
                            issuer = provider?.bankId.orEmpty(),
                            companyId = provider?.companyId.orEmpty(),
                        ),
                    ),
                )
            },
        )

        ZillitTextField(
            value = draft.bsControlCode,
            onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(bsControlCode = it))) },
            label = str(S.desktop_card_bs_control_code),
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(justification = it))) },
            label = str(S.desktop_card_justification),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )

        if (blocked != null) {
            ZillitText(
                text = blocked,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/**
 * Activating an approved card (`CardRegisterPage.jsx:460-506`).
 *
 * Digital or physical, its sixteen digits, and — for a request raised without
 * one, which is every crew self-service request — the provider it is held
 * with; the provider's bank and company ride along. The desktop activated on a
 * bare "yes" with an empty body, so no card ever received its number.
 */
@Suppress("LongMethod") // One form; see NewCardDialog.
@Composable
fun ActivationDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.activation ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    val providers = state.providers
    val blocked = draft.validationError(providers.isNotEmpty())

    ZillitDialogShell(
        title = str(S.desktop_card_activate_card),
        subtitle = card?.let { str(S.desktop_card_holder_card_suffix, state.holderName(it), it.lastFour ?: "—") },
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseActivation) },
        width = FORM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CardEvent.CloseActivation) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_card_activate_assign_number),
                onClick = { onEvent(CardEvent.SubmitActivation) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        FieldGroupLabel(str(S.desktop_card_card_type))
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CardType.entries.forEach { type ->
                ZillitButton(
                    text = type.label,
                    onClick = { onEvent(CardEvent.EditActivation(draft.copy(type = type))) },
                    variant = if (draft.type == type) ButtonVariant.Primary else ButtonVariant.Secondary,
                )
            }
        }
        ZillitTextField(
            value = draft.number,
            onValueChange = { typed ->
                onEvent(CardEvent.EditActivation(draft.copy(number = typed.filter { it.isDigit() || it == ' ' })))
            },
            label = str(S.desktop_card_number),
            placeholder = "4000 0000 0000 0000",
            keyboardType = KeyboardType.Number,
            helperText = str(S.desktop_card_last_four_note),
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.needsProvider) {
            ProviderPicker(
                providers = providers,
                selectedId = draft.providerId,
                onSelect = { provider ->
                    onEvent(CardEvent.EditActivation(draft.copy(providerId = provider?.id.orEmpty())))
                },
            )
        }
        if (blocked != null) {
            ZillitText(
                text = blocked,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
private fun HolderPicker(
    people: List<CardPerson>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (people.isEmpty()) {
        ZillitText(
            text = str(S.desktop_card_nobody_eligible),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    val selected = people.firstOrNull { it.id == selectedId }
    ZillitSelect(
        value = selected,
        options = people,
        onSelect = { onSelect(it?.id.orEmpty()) },
        label = { it?.pickerLabel ?: str(S.desktop_card_choose_a_cardholder) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The issuer the card is held with.
 *
 * Soft-required everywhere it appears: with none configured the field cannot
 * be satisfied, and refusing on it would make every card form unusable until
 * somebody visits Settings.
 */
@Composable
private fun ProviderPicker(
    providers: List<CardProvider>,
    selectedId: String,
    onSelect: (CardProvider?) -> Unit,
) {
    if (providers.isEmpty()) {
        ZillitText(
            text = str(S.desktop_card_no_providers_note),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel(str(S.desktop_card_provider))
        ZillitSelect(
            value = providers.firstOrNull { it.id == selectedId },
            options = providers,
            onSelect = onSelect,
            label = { it?.name ?: str(S.desktop_card_choose_a_provider) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val CURRENCY_FIELD = 0.5f
private val FORM_WIDTH = 520.dp
