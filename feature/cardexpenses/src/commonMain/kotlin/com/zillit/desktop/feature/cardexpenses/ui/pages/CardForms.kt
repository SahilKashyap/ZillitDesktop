package com.zillit.desktop.feature.cardexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardNumbers
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.components.CardRichSelect
import com.zillit.desktop.feature.cardexpenses.ui.components.FormLabel
import com.zillit.desktop.feature.cardexpenses.ui.components.ReadOnlyField
import com.zillit.desktop.feature.cardexpenses.ui.defaultCurrency

/**
 * Request New Card, as the accounts team raises one for a crew member
 * (`CardRegisterPage.jsx:1035-1166`).
 *
 * Validated on submit, in the web's order, each refusal a toast; the button is
 * only held back while nobody is chosen. A cardholder asking for their own
 * card gets the crew form instead — see `CrewRequestDialog`.
 */
@Suppress("LongMethod") // One form, read top to bottom; splitting it hides the order.
@Composable
fun NewCardDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.newCard ?: return
    val holder = state.people.firstOrNull { it.id == draft.holderId }
    val currency = draft.currency.ifBlank { state.defaultCurrency }

    ZillitDialogShell(
        title = str(S.desktop_ce_cards_request_new_card),
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseNewCard) },
        width = WIDE_FORM,
        actions = {
            ZillitButton(
                text = if (state.busy) str(S.desktop_ce_cards_submitting) else str(S.desktop_ce_cards_submit_request),
                onClick = { onEvent(CardEvent.SubmitNewCard) },
                enabled = draft.holderId.isNotBlank() && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        TwoUp(
            left = {
                FormLabel(str(S.desktop_card_card_holder), required = true)
                CardRichSelect(
                    value = holder,
                    options = state.eligibleHolders,
                    onSelect = { onEvent(CardEvent.EditNewCard(draft.copy(holderId = it.id))) },
                    title = { it.name.ifBlank { it.id } },
                    subtitle = { person ->
                        listOf(person.department, person.designation).filter(String::isNotBlank).joinToString(" · ")
                    },
                    placeholder = str(S.desktop_ce_cards_search_user),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            right = {
                FormLabel(str(S.department))
                ReadOnlyField(holder?.department.orEmpty(), placeholder = str(S.desktop_ce_cards_auto_filled))
            },
        )

        ProviderPicker(
            state = state,
            selectedId = draft.providerId,
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditNewCard(
                        draft.copy(
                            providerId = provider.id,
                            issuer = provider.bankId,
                            companyId = provider.companyId,
                            // The currency follows the provider's bank, else the default.
                            currency = state.bankCurrency(provider.bankId) ?: state.defaultCurrency,
                        ),
                    ),
                )
            },
        )

        FormLabel(str(S.asset_currency), required = true)
        CurrencyPicker(state, currency) { onEvent(CardEvent.EditNewCard(draft.copy(currency = it))) }

        TwoUp(
            left = {
                FormLabel(str(S.desktop_ce_cards_proposed_limit), required = true)
                LimitField(draft.proposedLimit, currency) {
                    onEvent(CardEvent.EditNewCard(draft.copy(proposedLimit = it)))
                }
            },
            right = {
                FormLabel(str(S.desktop_ce_cards_bs_control_code), required = true)
                ZillitTextField(
                    value = draft.bsControlCode,
                    onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(bsControlCode = it))) },
                    placeholder = str(S.desktop_ce_cards_bs_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        FormLabel(str(S.desktop_card_justification), required = true)
        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditNewCard(draft.copy(justification = it))) },
            placeholder = str(S.desktop_ce_cards_justification_placeholder),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Edit Card Details, the accountant's (`CardRegisterPage.jsx:1519-1645`): the
 * holder and department read-only, the provider (whose pick rewrites the
 * bank, the company and the currency), the currency, the proposed limit, the
 * control code and the justification. Saving submits it for approval.
 */
@Suppress("LongMethod") // One form; see NewCardDialog.
@Composable
fun CardEditDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.cardEdit ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    val holder = state.people.firstOrNull { it.id == card?.holderId }
    val currency = draft.currency.ifBlank { state.defaultCurrency }
    val valid = (state.providers.isEmpty() || draft.providerId.isNotBlank()) && currency.isNotBlank()

    ZillitDialogShell(
        title = str(S.desktop_ce_cards_edit_card_details),
        icon = ZillitIcons.Edit,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseCardEdit) },
        width = WIDE_FORM,
        actions = {
            ZillitButton(
                text = if (state.busy) str(S.desktop_ce_cards_submitting) else str(S.dm_action_submit),
                onClick = { onEvent(CardEvent.SaveCardEdit) },
                enabled = valid && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        HolderAndDepartment(holder)

        ProviderPicker(
            state = state,
            selectedId = draft.providerId,
            // A real pick — and only a pick — re-denominates the card to the
            // new bank's currency; a bank with none leaves it (`:233-259`).
            onSelect = { provider ->
                onEvent(
                    CardEvent.EditCardDraft(
                        draft.copy(
                            providerId = provider.id,
                            issuer = provider.bankId,
                            companyId = provider.companyId,
                            currency = state.bankCurrency(provider.bankId) ?: draft.currency,
                        ),
                    ),
                )
            },
        )

        FormLabel(str(S.asset_currency), required = true)
        CurrencyPicker(state, currency) { onEvent(CardEvent.EditCardDraft(draft.copy(currency = it))) }

        TwoUp(
            left = {
                FormLabel(str(S.desktop_ce_cards_proposed_limit), required = true)
                LimitField(draft.limit, currency) { onEvent(CardEvent.EditCardDraft(draft.copy(limit = it))) }
            },
            right = {
                FormLabel(str(S.desktop_ce_cards_bs_control_code), required = true)
                ZillitTextField(
                    value = draft.bsControlCode,
                    onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(bsControlCode = it))) },
                    placeholder = str(S.desktop_ce_cards_bs_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        FormLabel(str(S.desktop_card_justification), required = true)
        ZillitTextField(
            value = draft.justification,
            onValueChange = { onEvent(CardEvent.EditCardDraft(draft.copy(justification = it))) },
            placeholder = str(S.desktop_ce_cards_justification_placeholder_edit),
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Activate Card (`CardRegisterPage.jsx:1257-1397`): the provider for a
 * request that came without one, a Digital or Physical tile, and the sixteen
 * digits in groups of four with what is left to type.
 */
@Suppress("LongMethod") // One form; see NewCardDialog.
@Composable
fun ActivationDialog(state: CardUiState, onEvent: (CardEvent) -> Unit) {
    val draft = state.activation ?: return
    val card = state.cards.firstOrNull { it.id == draft.cardId }
    val holder = state.people.firstOrNull { it.id == card?.holderId }?.name ?: str(S.desktop_card_card_holder)
    val digits = CardNumbers.digits(draft.number)
    val blocked = draft.validationError(state.providers.isNotEmpty())

    ZillitDialogShell(
        title = str(S.desktop_ce_cards_activate_title),
        icon = ZillitIcons.CreditCard,
        visible = true,
        onDismiss = { onEvent(CardEvent.CloseActivation) },
        width = NARROW_FORM,
        actions = {
            ZillitButton(
                text = when {
                    state.busy -> str(S.desktop_ce_cards_activating)
                    draft.type == CardType.Digital -> str(S.desktop_ce_cards_activate_digital)
                    else -> str(S.desktop_ce_cards_activate_physical)
                },
                onClick = { onEvent(CardEvent.SubmitActivation) },
                enabled = blocked == null && !state.busy,
                loading = state.busy,
            )
        },
    ) {
        Intro(str(S.desktop_ce_cards_activate_body, holder))
        if (draft.needsProvider) {
            ProviderPicker(
                state = state,
                selectedId = draft.providerId,
                onSelect = { onEvent(CardEvent.EditActivation(draft.copy(providerId = it.id))) },
            )
        }
        FormLabel(str(S.desktop_ce_cards_card_type), required = true)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            TypeTile(str(S.desktop_ce_cards_digital_card), draft.type == CardType.Digital, Modifier.weight(1f)) {
                onEvent(CardEvent.EditActivation(draft.copy(type = CardType.Digital)))
            }
            TypeTile(str(S.desktop_ce_cards_physical_card), draft.type == CardType.Physical, Modifier.weight(1f)) {
                onEvent(CardEvent.EditActivation(draft.copy(type = CardType.Physical)))
            }
        }
        draft.type?.let { type ->
            FormLabel(
                if (type == CardType.Digital) {
                    str(S.desktop_ce_cards_virtual_number)
                } else {
                    str(S.desktop_ce_cards_card_number)
                },
                required = true,
            )
            CardNumberField(digits) { onEvent(CardEvent.EditActivation(draft.copy(number = CardNumbers.digits(it)))) }
        }
    }
}

// -- shared form parts --------------------------------------------------------

/**
 * The card provider, the only issuer field on every card form
 * (`ui/CardProviderSelect.jsx`): searchable, each option naming its bank and
 * company; soft-required, so with none configured it stays an empty picker
 * with the Settings hint rather than a block.
 */
@Composable
internal fun ProviderPicker(state: CardUiState, selectedId: String, onSelect: (CardProvider) -> Unit) {
    val providers = state.providers
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FormLabel(str(S.desktop_ce_cards_card_provider), required = providers.isNotEmpty())
        CardRichSelect(
            value = providers.firstOrNull { it.id == selectedId },
            options = providers,
            onSelect = onSelect,
            title = { it.name },
            subtitle = { provider -> providerBinding(state, provider) },
            placeholder = str(S.desktop_ce_cards_select_provider),
            modifier = Modifier.fillMaxWidth(),
        )
        Hint(
            if (providers.isEmpty()) {
                str(S.desktop_ce_cards_no_providers_hint)
            } else {
                str(S.desktop_ce_cards_provider_hint)
            },
        )
    }
}

/** "Bank · Company" under a provider option. */
private fun providerBinding(state: CardUiState, provider: CardProvider): String {
    val reference = state.cardsArea.reference
    return listOfNotNull(
        reference.banks.firstOrNull { it.id == provider.bankId }?.name,
        reference.companies.firstOrNull { it.id == provider.companyId }?.name,
    ).filter { it.isNotBlank() }.joinToString(" · ")
}

/** The currency a bank holds, if it names one. */
internal fun CardUiState.bankCurrency(bankId: String): String? =
    cardsArea.reference.banks.firstOrNull { it.id == bankId }?.currency?.takeIf { it.isNotBlank() }

/**
 * The project's selected currencies, the default among them — and the value
 * already on the record, so an off-list code stays visible. Before the list
 * has loaded it is a plain code field.
 */
@Composable
internal fun CurrencyPicker(state: CardUiState, value: String, onChange: (String) -> Unit) {
    val options = state.cardsArea.reference.currencyOptions(ensure = value.ifBlank { null })
    if (options.isEmpty()) {
        ZillitTextField(
            value = value,
            onValueChange = { onChange(it.uppercase()) },
            placeholder = str(S.desktop_ce_cards_select_currency),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    ZillitSelect(
        value = value.uppercase().ifBlank { options.first() },
        options = options,
        onSelect = onChange,
        label = { code -> currencyLabel(code) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** `GBP (£)`, or the bare code where it has no symbol of its own. */
private fun currencyLabel(code: String): String =
    Money.symbol(code).trim().takeIf { it.isNotEmpty() && it != code }?.let { "$code ($it)" } ?: code

/** A proposed limit, placeholder in the chosen currency (`£1,500`). */
@Composable
internal fun LimitField(value: String, currency: String, hint: String? = null, onChange: (String) -> Unit) {
    ZillitTextField(
        value = value,
        onValueChange = { typed -> onChange(typed.filter { it.isDigit() || it == '.' || it == ',' }) },
        placeholder = Money.symbol(currency) + LIMIT_EXAMPLE,
        keyboardType = KeyboardType.Decimal,
        helperText = hint,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Sixteen digits, grouped in fours as typed, with what is left to type (`:1345-1363`). */
@Composable
internal fun CardNumberField(digits: String, onChange: (String) -> Unit) {
    ZillitTextField(
        value = CardNumbers.grouped(digits),
        onValueChange = onChange,
        placeholder = CARD_NUMBER_PLACEHOLDER,
        keyboardType = KeyboardType.Number,
        errorText = digits.takeIf { it.isNotEmpty() && it.length < CardNumbers.LENGTH }
            ?.let { str(S.desktop_ce_cards_digits_remaining, CardNumbers.remaining(it)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Card Holder and Department, read-only, side by side. */
@Composable
internal fun HolderAndDepartment(holder: CardPerson?, fallbackName: String = "") {
    TwoUp(
        left = {
            FormLabel(str(S.desktop_card_card_holder))
            ReadOnlyField(holder?.name ?: fallbackName)
        },
        right = {
            FormLabel(str(S.department))
            ReadOnlyField(holder?.department.orEmpty())
        },
    )
}

@Composable
internal fun TwoUp(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) { left() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) { right() }
    }
}

@Composable
internal fun Intro(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
}

@Composable
internal fun Hint(text: String) {
    ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

/** A warning or information box inside a dialog. */
@Composable
internal fun DialogNote(text: String, tone: StatusTone) {
    ZillitNotice(
        text = text,
        tone = tone,
        icon = if (tone == StatusTone.Neutral) ZillitIcons.Info else ZillitIcons.Warning,
    )
}

/** A Digital / Physical choice, drawn as the web's two tiles. */
@Composable
private fun TypeTile(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surfaceSunken)
            .border(2.dp, if (selected) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = ZillitTheme.spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(ZillitIcons.CreditCard, tint = if (selected) colors.accent else colors.textMuted)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (selected) colors.accentText else colors.textMuted,
        )
    }
}

internal val WIDE_FORM = 520.dp
internal val NARROW_FORM = 480.dp
private const val LIMIT_EXAMPLE = "1,500"
private const val CARD_NUMBER_PLACEHOLDER = "0000 0000 0000 0000"
