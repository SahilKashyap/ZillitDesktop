package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCalcField
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentField
import com.zillit.desktop.feature.talentdeals.domain.TalentTerritory
import com.zillit.desktop.feature.talentdeals.domain.TalentUnion
import com.zillit.desktop.feature.talentdeals.ui.PageColumn
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState
import com.zillit.desktop.feature.talentdeals.ui.TalentHeader

/**
 * Add Contract / Edit — a director, cast or producer engagement keyed against
 * the executed agreement (`TalentContractPage.jsx`). The role is decided before
 * this page opens (the role picker) and titles it; there is no role field.
 *
 * Save uploads a picked file first, then posts the envelope. An outstanding
 * field is counted in the header and marked under its control, but only once
 * someone has tried to save.
 */
@Composable
internal fun ContractFormPage(
    state: TalentDealsUiState,
    onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    val page = state.form
    val shown = page.shownErrors
    Column(modifier.fillMaxSize()) {
        TalentHeader(
            backLabel = str(S.desktop_td_back_to_talent_deals),
            onBack = { onEvent(TalentDealsEvent.Back) },
            eyebrow = str(S.desktop_td_title),
            onEyebrow = { onEvent(TalentDealsEvent.Back) },
            current = if (page.isEdit) page.reference else page.role,
            sub = page.form.person.trim().takeIf { it.isNotEmpty() },
            actions = {
                if (shown.isNotEmpty()) {
                    val key = if (shown.size > 1) {
                        S.desktop_td_fields_attention_other
                    } else {
                        S.desktop_td_fields_attention_one
                    }
                    ZillitText(
                        text = str(key, shown.size),
                        style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                        color = ZillitTheme.colors.danger,
                    )
                }
                ZillitButton(
                    text = when {
                        page.saving -> str(S.ah_saving)
                        page.isEdit -> str(S.ah_save_changes)
                        else -> str(S.desktop_td_save_contract)
                    },
                    size = ButtonSize.Small,
                    enabled = !page.saving && !page.loadingRow,
                    onClick = { onEvent(TalentDealsEvent.Save) },
                )
            },
        )
        ZillitScrollColumn(Modifier.fillMaxSize()) {
            if (page.loadingRow) {
                PageColumn {
                    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 24.dp) }
                    }
                }
            } else {
                PageColumn {
                    FormCard(state, onEvent, signatureLoader)
                    ZillitText(
                        text = str(S.desktop_td_reference, page.reference),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun FormCard(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit,
    signatureLoader: suspend (TalentAttachment) -> ByteArray?) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        UploadSection(state, onEvent)
        UnionSection(state, onEvent)
        PaymentSection(state, onEvent, signatureLoader)
        ExpensesSection(state, onEvent)
    }
}

@Composable
private fun UploadSection(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit) {
    val page = state.form
    val form = page.form
    val shown = page.shownErrors
    FormSection(str(S.desktop_td_sec_upload)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            NameField(
                value = form.person,
                role = page.role,
                error = if (TalentField.Person in shown) str(S.desktop_td_err_name) else null,
                onChange = { name -> onEvent(TalentDealsEvent.FormEdit { copy(person = name) }) },
                onBlur = { onEvent(TalentDealsEvent.CommitName(form.person)) },
                modifier = Modifier.width(FIELD_WIDTH),
            )
            FileField(
                label = str(S.contract_text),
                cta = str(S.desktop_td_choose_contract_file),
                file = form.contractFile,
                onPick = { onEvent(TalentDealsEvent.PickContractFile) },
                onRemove = { onEvent(TalentDealsEvent.FormEdit { copy(contractFile = null) }) },
                required = true,
                error = if (TalentField.Contract in shown) str(S.desktop_td_err_contract) else null,
                modifier = Modifier.width(FIELD_WIDTH),
            )
            FormField(
                label = str(S.dm_section_territory),
                required = true,
                error = if (TalentField.Territory in shown) str(S.desktop_td_err_territory) else null,
                modifier = Modifier.width(FIELD_WIDTH),
            ) {
                ZillitSearchSelect(
                    value = state.reference.territories.firstOrNull { it.id == form.territory },
                    options = state.reference.territories,
                    onSelect = { onEvent(TalentDealsEvent.SelectTerritory(it.id)) },
                    label = TalentTerritory::label,
                    subtitle = { "${it.id.uppercase()} · ${it.currency}".trimEnd(' ', '·') },
                    searchText = { "${it.label} ${it.id} ${it.currency}" },
                    placeholder = str(S.dm_step1_select_territory),
                    isError = TalentField.Territory in shown,
                    showInitials = false,
                    dropdownWidth = DROPDOWN_WIDTH,
                )
            }
        }
    }
}

/** The Name field; the Exhibit G lookup commits on blur, not per keystroke. */
@Composable
private fun NameField(
    value: String,
    role: String,
    error: String?,
    onChange: (String) -> Unit,
    onBlur: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    FormField(label = str(S.name), required = true, error = error, modifier = modifier) {
        Box(
            Modifier.onFocusChanged { focus ->
                if (focused && !focus.hasFocus) onBlur()
                focused = focus.hasFocus
            },
        ) {
            ZillitTextField(
                value = value,
                onValueChange = onChange,
                placeholder = str(S.desktop_td_name_placeholder, role),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Suppress("LongMethod") // A page laid out section by section.
@Composable
private fun UnionSection(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit) {
    val page = state.form
    val form = page.form
    val shown = page.shownErrors
    val options = page.unionOptions
    val territory = state.reference.territories.firstOrNull { it.id == form.territory }
    val currencies = state.reference.currencyOptions(form.currency)
    val active = state.activeCurrency
    FormSection(str(S.dm_label_union)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            FormField(
                label = str(S.dm_label_union),
                required = page.unionRequired,
                error = if (TalentField.Union in shown) str(S.desktop_td_err_union) else null,
                modifier = Modifier.width(FIELD_WIDTH),
            ) {
                ZillitSearchSelect(
                    value = options.firstOrNull { it.identifier == form.union },
                    options = options,
                    onSelect = { onEvent(TalentDealsEvent.FormEdit { copy(union = it.identifier) }) },
                    label = { it.name.ifBlank { it.identifier } },
                    subtitle = TalentUnion::identifier,
                    searchText = { "${it.name} ${it.identifier}" },
                    // Usable as soon as there is anything to pick — the role's guild is there before any territory is.
                    enabled = !(options.isEmpty() && (form.territory.isBlank() || page.unionsLoading)),
                    placeholder = when {
                        options.isNotEmpty() -> str(S.desktop_td_select_union_ph)
                        form.territory.isBlank() -> str(S.dm_rule_import_agreement_first)
                        page.unionsLoading -> str(S.desktop_td_loading_unions)
                        else -> str(S.desktop_td_no_union_here)
                    },
                    isError = TalentField.Union in shown,
                    showInitials = false,
                    dropdownWidth = DROPDOWN_WIDTH,
                )
            }
            FormField(label = str(S.asset_currency), required = true, modifier = Modifier.width(FIELD_WIDTH)) {
                ZillitSearchSelect(
                    value = currencies.firstOrNull { it.code == active },
                    options = currencies,
                    onSelect = { onEvent(TalentDealsEvent.FormEdit { copy(currency = it.code) }) },
                    label = { it.label },
                    subtitle = { if (it.isProject) str(S.dm_step2_external_off) else null },
                    searchText = { "${it.code} ${it.name} ${it.symbol}" },
                    placeholder = str(S.desktop_ce_cards_select_currency),
                    showInitials = false,
                    dropdownWidth = DROPDOWN_WIDTH,
                )
                if (territory != null && active == territory.currency) {
                    ZillitText(
                        str(S.desktop_td_set_from_territory, territory.label),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
            }
            FormField(
                label = str(S.desktop_td_lbl_total_fee),
                required = true,
                error = if (TalentField.Fee in shown) str(S.desktop_td_err_fee) else null,
                modifier = Modifier.width(FEE_WIDTH),
            ) {
                MoneyInput(
                    value = form.fee,
                    symbol = state.symbol,
                    onCommit = { onEvent(TalentDealsEvent.FormEdit { copy(fee = it) }) },
                )
            }
        }
    }
}

/** A money box: the currency's symbol, then the calculator field (the web's `CalcInput`). */
@Composable
internal fun MoneyInput(value: String, symbol: String, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        if (symbol.isNotBlank()) ZillitText(symbol, style = ZillitTheme.typography.numeric,
            color = ZillitTheme.colors.textMuted, maxLines = 1)
        ZillitCalcField(value = value, onCommit = onCommit, modifier = Modifier.weight(1f))
    }
}

private val FEE_WIDTH = 200.dp
private val DROPDOWN_WIDTH = 320.dp
private const val SKELETON_ROWS = 8
