package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.BankAccount
import com.zillit.desktop.feature.accounthub.domain.CardProvider
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.Company
import com.zillit.desktop.feature.accounthub.domain.LocalIds
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HairLine
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

/**
 * Card Providers — the web's `CardProvidersEditor`, the card side's whole
 * Accounts & Custodian pane.
 *
 * Each provider binds one bank to one company with its own custodian account
 * and float range. Picking a bank fills the company that owns it (one bank
 * belongs to one company) and locks the field; the company picker opens only
 * for a bank no company has claimed. The three account fields are
 * balance-sheet codes.
 */
@Composable
internal fun ColumnScope.CardProvidersPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (SpendSettings) -> Unit,
) {
    val companies = state.setup.companies.saved
    val banks = state.setup.banks
    fun patch(index: Int, next: CardProvider) =
        update(value.copy(providers = value.providers.mapIndexed { i, p -> if (i == index) next else p }))
    SubCard(
        title = str(S.desktop_ce_insights_card_providers),
        hint = str(S.desktop_ce_insights_providers_description),
        action = {
            if (editable) {
                GhostAddButton(
                    str(S.desktop_ce_insights_add_card_provider),
                    onClick = {
                        val taken = value.providers.map { it.id }
                        update(value.copy(providers = value.providers + CardProvider(LocalIds.next("prov", taken))))
                    },
                )
            }
        },
    ) {
        if (value.providers.isEmpty()) FieldHint(str(S.desktop_ce_insights_providers_empty))
        value.providers.forEachIndexed { index, provider ->
            if (index > 0) HairLine()
            ProviderRow(
                provider = provider,
                banks = banks,
                companies = companies,
                state = state,
                editable = editable,
                onEvent = onEvent,
                onChange = { patch(index, it) },
                onRemove = { update(value.copy(providers = value.providers.filterIndexed { i, _ -> i != index })) },
            )
        }
    }
}

@Composable
private fun ProviderRow(
    provider: CardProvider,
    banks: List<BankAccount>,
    companies: List<Company>,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    onChange: (CardProvider) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitTextField(
                value = provider.name,
                onValueChange = { onChange(provider.copy(name = it)) },
                label = str(S.desktop_ce_insights_provider_name),
                placeholder = str(S.desktop_ce_insights_provider_placeholder),
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            BankSelect(provider, banks, companies, editable, onChange, Modifier.weight(1f))
            CompanyField(provider, companies, editable, onChange, Modifier.weight(1f))
        }
        ProviderAccountFields(provider, state, editable, onEvent, onChange, onRemove)
    }
}

@Composable
private fun ProviderAccountFields(
    provider: CardProvider,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    onChange: (CardProvider) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        val accounts = balanceSheet(state)
        val create = quickCreateHandler(state, onEvent)
        AccountCodeField(
            value = provider.custodianAccount,
            label = str(S.desktop_ce_insights_custodian_account),
            placeholder = str(S.desktop_pc_enter_account_code),
            accounts = accounts,
            editable = editable,
            onCreate = create,
            onChange = { onChange(provider.copy(custodianAccount = it)) },
        )
        AccountCodeField(
            value = provider.floatMin,
            label = str(S.desktop_ce_insights_float_from),
            placeholder = str(S.desktop_pc_eg_1000),
            accounts = accounts,
            editable = editable,
            onCreate = create,
            onChange = { onChange(provider.copy(floatMin = it)) },
        )
        AccountCodeField(
            value = provider.floatMax,
            label = str(S.desktop_ce_insights_float_to),
            placeholder = str(S.desktop_pc_eg_1999),
            accounts = accounts,
            editable = editable,
            onCreate = create,
            onChange = { onChange(provider.copy(floatMax = it)) },
        )
        if (editable) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.desktop_ce_insights_remove_provider),
                onClick = onRemove,
                tint = ZillitTheme.colors.danger,
                modifier = Modifier.padding(top = ZillitTheme.spacing.lg),
            )
        }
    }
}

/** The bank, from the production's accounts. Its owning company follows it, or clears when nobody owns it. */
@Composable
private fun BankSelect(
    provider: CardProvider,
    banks: List<BankAccount>,
    companies: List<Company>,
    editable: Boolean,
    onChange: (CardProvider) -> Unit,
    modifier: Modifier,
) {
    // A bank the list no longer holds stays chosen, named as unknown rather than by its id.
    val options = banks + listOfNotNull(
        provider.bankId.takeIf { it.isNotBlank() && banks.none { b -> b.id == it } }
            ?.let { BankAccount(id = it, name = str(S.desktop_ce_insights_unknown_bank)) },
    )
    HubSelect(
        value = options.firstOrNull { it.id == provider.bankId },
        options = options,
        label = ::bankLabel,
        secondary = { if (it.accountNumber.isBlank()) "" else "•••• ${it.accountLast4}" },
        onSelect = { picked ->
            val owner = picked?.let { ownerOf(it.id, companies) }
            onChange(provider.copy(bankId = picked?.id.orEmpty(), companyId = owner?.id.orEmpty()))
        },
        placeholder = str(S.desktop_ce_insights_search_bank),
        fieldLabel = str(S.desktop_bank),
        enabled = editable,
        clearable = true,
        modifier = modifier,
    )
}

/** The owning company, locked, or a picker for a bank no company has claimed. */
@Composable
private fun CompanyField(
    provider: CardProvider,
    companies: List<Company>,
    editable: Boolean,
    onChange: (CardProvider) -> Unit,
    modifier: Modifier,
) {
    val owner = ownerOf(provider.bankId, companies)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (owner != null) {
            FieldLabel(str(S.company))
            ZillitText(text = owner.name, style = ZillitTheme.typography.bodyMedium)
            FieldHint(str(S.desktop_ce_insights_owns_bank))
        } else {
            HubSelect(
                value = companies.firstOrNull { it.id == provider.companyId },
                options = companies,
                label = { it.name },
                onSelect = { onChange(provider.copy(companyId = it?.id.orEmpty())) },
                placeholder = str(
                    if (provider.bankId.isBlank()) S.desktop_ce_insights_pick_bank_first
                    else S.desktop_ce_insights_search_company,
                ),
                fieldLabel = str(S.company),
                enabled = editable && provider.bankId.isNotBlank(),
                clearable = true,
            )
        }
    }
}

@Composable
private fun RowScope.AccountCodeField(
    value: String,
    label: String,
    placeholder: String,
    accounts: List<CoaAccount>,
    editable: Boolean,
    onCreate: ((String, String, CoaCostType) -> Unit)?,
    onChange: (String) -> Unit,
) {
    CoaCodeField(
        value = value,
        onValueChange = onChange,
        accounts = accounts,
        label = label,
        placeholder = placeholder,
        enabled = editable,
        costType = CoaCostType.Asset,
        onCreate = onCreate,
        modifier = Modifier.weight(1f),
    )
}

private fun ownerOf(bankId: String, companies: List<Company>): Company? =
    bankId.takeIf { it.isNotBlank() }?.let { id -> companies.firstOrNull { id in it.bankIds } }

private fun bankLabel(bank: BankAccount): String =
    bank.name.ifBlank { bank.accountHolderName } + if (bank.currencyCode.isBlank()) "" else " · ${bank.currencyCode}"
