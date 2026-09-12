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
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
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
        title = if (accountant) "Issue a card" else "Request a card",
        subtitle = if (accountant) {
            "The holder can spend against it once you approve and activate it."
        } else {
            "It goes to the accounts team, who set the limit they authorise."
        },
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseNewCard) },
        width = FORM_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(CardEvent.CloseNewCard) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (accountant) "Issue card" else "Send request",
                onClick = { onEvent(CardEvent.SubmitNewCard) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        if (accountant) {
            FieldGroupLabel("Cardholder")
            HolderPicker(
                people = state.eligibleHolders,
                selectedId = draft.holderId,
                onSelect = { onEvent(CardEvent.EditNewCard(draft.copy(holderId = it))) },
            )
            if (state.people.isNotEmpty() && state.eligibleHolders.size < state.people.size) {
                ZillitText(
                    text = "${state.people.size - state.eligibleHolders.size} crew already hold a card and are " +
                        "not offered here. Suspend or close the old card first.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
        }

        FieldGroupLabel("The card")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitTextField(
                value = draft.proposedLimit,
                onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(proposedLimit = it))) },
                label = "Proposed limit",
                placeholder = "0.00",
                keyboardType = KeyboardType.Decimal,
                helperText = "What the card should be authorised for.",
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.currency,
                onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(currency = it.uppercase()))) },
                label = "Currency",
                placeholder = "GBP",
                modifier = Modifier.weight(CURRENCY_FIELD),
            )
        }

        ProviderPicker(
            providers = providers,
            selectedId = draft.providerId,
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditNewCard(
                        draft.copy(providerId = provider?.id.orEmpty(), issuer = provider?.name.orEmpty()),
                    ),
                )
            },
        )

        ZillitTextField(
            value = draft.bsControlCode,
            onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(bsControlCode = it))) },
            label = "Balance-sheet control code",
            placeholder = "2100",
            helperText = "The control account this card's spend sits against.",
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(justification = it))) },
            label = "Justification",
            placeholder = "Daily unit spend for the art department",
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
        title = "Edit card details",
        subtitle = card?.holderName?.takeIf { it.isNotBlank() }?.let { "$it · card ${card.lastFour ?: "—"}" },
        icon = ZillitIcons.Edit,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseCardEdit) },
        width = FORM_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(CardEvent.CloseCardEdit) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Save and resubmit",
                onClick = { onEvent(CardEvent.SaveCardEdit) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        ZillitNotice(
            text = "Saving resubmits the card: the approvals it has collected so far are cleared and the " +
                "chain starts again from the top.",
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
                label = "Authorised limit",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f),
            )
            ZillitTextField(
                value = draft.currency,
                onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(currency = it.uppercase()))) },
                label = "Currency",
                modifier = Modifier.weight(CURRENCY_FIELD),
            )
        }

        // The balance moves with the limit, and showing the arithmetic before
        // it is committed is the difference between a considered change and a
        // surprise on the holder's card.
        if (draft.limitValue > 0 && draft.limitValue != draft.currentLimit) {
            ZillitText(
                text = "Remaining balance becomes ${money(draft.newBalance, draft.currency)}, " +
                    "from ${money(draft.currentBalance, draft.currency)}.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        ProviderPicker(
            providers = providers,
            selectedId = draft.providerId,
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditCardDraft(
                        draft.copy(providerId = provider?.id.orEmpty(), issuer = provider?.name.orEmpty()),
                    ),
                )
            },
        )

        ZillitTextField(
            value = draft.bsControlCode,
            onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(bsControlCode = it))) },
            label = "Balance-sheet control code",
            modifier = Modifier.fillMaxWidth(),
        )

        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(justification = it))) },
            label = "Justification",
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

@Composable
private fun HolderPicker(
    people: List<CardPerson>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (people.isEmpty()) {
        ZillitText(
            text = "Nobody on this production can be issued a card right now.",
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
        label = { it?.pickerLabel ?: "Choose a cardholder" },
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
            text = "No card providers are configured for this production, so the card is raised without one.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldGroupLabel("Provider")
        ZillitSelect(
            value = providers.firstOrNull { it.id == selectedId },
            options = providers,
            onSelect = onSelect,
            label = { it?.name ?: "Choose a provider" },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val CURRENCY_FIELD = 0.5f
private val FORM_WIDTH = 520.dp
