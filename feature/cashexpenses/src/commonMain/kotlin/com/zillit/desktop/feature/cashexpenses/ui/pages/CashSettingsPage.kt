package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.SettingsEvent

/**
 * The production's cash configuration — the web's `PCSettingsPage`, section
 * for section and in its order.
 *
 * Senior accountants only — every switch here changes what other people are
 * allowed to do, so the page is gated in [com.zillit.desktop.feature.cashexpenses.ui.CashDestination]
 * rather than shown read-only. Each section saves on its own, sending only its
 * own keys, so one refusal never costs another section's edits.
 */
@Composable
fun CashSettingsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    // The code fields offer the chart; read it once, as the coding pages do.
    LaunchedEffect(Unit) { onEvent(CashEvent.LoadChartAccounts) }
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage { ZillitNotice(text = str(S.desktop_ce_loading_settings)) }
        return
    }

    ScrollingPage {
        ZillitNotice(text = str(S.desktop_pc_settings_banner), tone = StatusTone.Pending, icon = ZillitIcons.Settings)
        Column {
            ZillitText(text = str(S.desktop_pc_settings_title), style = ZillitTheme.typography.titleSmall)
            ZillitText(
                text = str(S.desktop_pc_settings_subtitle),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
        }

        CustodianCard(state, draft, onEvent)
        TeamCard(state, onEvent)
        CoordinatorsCard(state, draft, onEvent)
        ApprovalCard(state, draft, onEvent)
        RequestCapCard(state, onEvent)
        ReimbursementCard(state, draft, onEvent)
        DeductionRulesCard(state, draft, onEvent)
        QuickCodesCard(state, draft, onEvent)
        AssignmentRulesCard(state, onEvent)
    }
}

/** A section's Save, wired to its own PATCH. */
@Composable
internal fun SectionSaveFor(
    section: CashSettingsSection,
    state: CashUiState,
    draft: CashSettings,
    onEvent: (CashEvent) -> Unit,
) {
    SectionSave(
        dirty = section.isDirty(draft, state.settings),
        saving = state.settingsUi.saving == section,
        enabled = state.settingsUi.saving == null,
        onSave = { onEvent(SettingsEvent.SaveSection(section)) },
    )
}

private fun edit(draft: CashSettings, onEvent: (CashEvent) -> Unit, next: CashSettings.() -> CashSettings) =
    onEvent(CashEvent.EditSettings(draft.next()))

// -- Float Accounts & Custodian ----------------------------------------------------

@Composable
private fun CustodianCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    val accounts = state.chartAccounts.orEmpty().balanceSheetCodes()
    ZillitSectionCard(
        title = str(S.desktop_pc_float_accounts_custodian),
        action = { SectionSaveFor(CashSettingsSection.Custodian, state, draft, onEvent) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            CodeColumn(
                caption = str(S.desktop_pc_float_custodian_account),
                value = draft.custodianAccount,
                placeholder = str(S.desktop_pc_enter_account_code),
                help = str(S.desktop_pc_custodian_help),
                accounts = accounts,
                onChange = { edit(draft, onEvent) { copy(custodianAccount = it) } },
            )
            CodeColumn(
                caption = str(S.desktop_pc_bs_code_from),
                value = draft.bsCodeFrom,
                placeholder = str(S.desktop_pc_eg_1000),
                help = str(S.desktop_pc_bs_from_help),
                accounts = accounts,
                onChange = { edit(draft, onEvent) { copy(bsCodeFrom = it) } },
            )
            CodeColumn(
                caption = str(S.desktop_pc_bs_code_to),
                value = draft.bsCodeTo,
                placeholder = str(S.desktop_pc_eg_1999),
                help = str(S.desktop_pc_bs_to_help),
                accounts = accounts,
                onChange = { edit(draft, onEvent) { copy(bsCodeTo = it) } },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CodeColumn(
    caption: String,
    value: String,
    placeholder: String,
    help: String,
    accounts: List<com.zillit.desktop.feature.cashexpenses.domain.CashAccount>,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldCaption(caption)
        CashCodeField(value = value, onValueChange = onChange, accounts = accounts, placeholder = placeholder)
        QuietLine(help)
    }
}

// -- Approval & Override Settings ----------------------------------------------------

@Composable
private fun ApprovalCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.ah_settings_approval_override),
        action = { SectionSaveFor(CashSettingsSection.Approval, state, draft, onEvent) },
    ) {
        SettingToggleRow(
            label = str(S.desktop_pc_override_float_label),
            detail = str(S.desktop_pc_override_float_desc),
            checked = draft.overrideFloatRequest,
            onChange = { edit(draft, onEvent) { copy(overrideFloatRequest = it) } },
        )
        ZillitDivider()
        SettingToggleRow(
            label = str(S.desktop_pc_override_batch_label),
            detail = str(S.desktop_pc_override_batch_desc),
            checked = draft.overrideReceiptBatch,
            onChange = { edit(draft, onEvent) { copy(overrideReceiptBatch = it) } },
        )
        ZillitDivider()
        SettingToggleRow(
            label = str(S.desktop_pc_coord_code_label),
            detail = str(S.desktop_pc_coord_code_desc),
            checked = draft.requireCoordinatorCoding,
            onChange = { edit(draft, onEvent) { copy(requireCoordinatorCoding = it) } },
        )
        ZillitDivider()
        SettingToggleRow(
            label = str(S.desktop_pc_senior_signoff_label),
            detail = str(S.desktop_pc_senior_signoff_desc),
            checked = draft.requireSeniorSignOff,
            onChange = { edit(draft, onEvent) { copy(requireSeniorSignOff = it) } },
        )
    }
}

// -- Reimbursement Settlement --------------------------------------------------------

@Composable
private fun ReimbursementCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.desktop_pc_reimbursement_title),
        action = { SectionSaveFor(CashSettingsSection.Reimbursement, state, draft, onEvent) },
    ) {
        SettingToggleRow(
            label = str(S.desktop_pc_reimburse_label),
            detail = str(S.desktop_pc_reimburse_desc),
            checked = draft.reimburseToPayroll,
            onChange = { edit(draft, onEvent) { copy(reimburseToPayroll = it) } },
        )
    }
}

// -- Deduction & Processing Rules ----------------------------------------------------

/**
 * The rules that fire on a receipt's words and its value — each shown as the
 * web shows it: what it does, at what threshold, on which words; edited in
 * the rule dialog, switched on and off in place.
 */
@Composable
private fun DeductionRulesCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.ah_settings_deduction_rules),
        action = {
            SectionSaveFor(CashSettingsSection.Deduction, state, draft, onEvent)
            SectionAdd(str(S.desktop_pc_add_rule)) { onEvent(SettingsEvent.AddDeductionRule) }
        },
    ) {
        if (draft.deductionRules.isEmpty()) {
            QuietLine(str(S.desktop_pc_deduction_empty))
            return@ZillitSectionCard
        }
        draft.deductionRules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            DeductionRuleRow(
                rule = rule,
                symbol = state.defaultSymbol(),
                onToggle = { on ->
                    edit(draft, onEvent) {
                        copy(
                            deductionRules = deductionRules.mapIndexed { i, r ->
                                if (i == index) r.copy(enabled = on) else r
                            },
                        )
                    }
                },
                onEdit = { onEvent(SettingsEvent.EditDeductionRule(index)) },
                onRemove = { onEvent(SettingsEvent.RemoveDeductionRule(index)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeductionRuleRow(
    rule: DeductionRule,
    symbol: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.Wallet, contentDescription = null, tint = ZillitTheme.colors.accent)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = rule.title.ifBlank { rule.type.ifBlank { str(S.desktop_pc_untitled_rule) } },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (rule.systemDefault) ZillitTag(label = str(S.desktop_language_system_short))
            }
            if (rule.description.isNotBlank()) {
                ZillitText(
                    text = rule.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitTag(label = rule.processType.badgeLabel().uppercase(), tone = rule.processType.badgeTone())
                ZillitTag(label = rule.thresholdLabel(symbol), tone = TagTone.Accent)
                rule.triggerCodes.forEach { ZillitTag(label = it) }
                if (rule.enabled) ZillitTag(label = str(S.active), tone = TagTone.Success)
            }
        }
        // Switch, Edit, delete — the web's order; the four shipped rules cannot be deleted.
        ZillitSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        ZillitButton(text = str(S.edit), onClick = onEdit, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        if (!rule.systemDefault) {
            ZillitIconButton(
                icon = ZillitIcons.Trash,
                contentDescription = str(S.delete),
                onClick = onRemove,
                tint = ZillitTheme.colors.danger,
            )
        }
    }
}

/** The web's process badge words: Deduct, Senior Review, Query. */
internal fun RuleProcess.badgeLabel(): String = when (this) {
    RuleProcess.DeductAmount -> str(S.desktop_ce_deduct)
    RuleProcess.SeniorReview -> str(S.desktop_pc_badge_senior_review)
    RuleProcess.NeedQuery -> str(S.ah_cd_query)
}

private fun RuleProcess.badgeTone(): TagTone = when (this) {
    RuleProcess.DeductAmount -> TagTone.Danger
    RuleProcess.SeniorReview -> TagTone.Warning
    RuleProcess.NeedQuery -> TagTone.Info
}

/** `20%`, or `£150 min` — the web's threshold chip. */
internal fun DeductionRule.thresholdLabel(symbol: String): String =
    if (thresholdType == RuleThreshold.Percentage) {
        "${plainFigure(thresholdValue)}%"
    } else {
        str(S.desktop_pc_amount_min, "$symbol${plainFigure(thresholdValue)}")
    }

/** The project default currency's symbol, else its code — the web's `symbol(defaultCode)`. */
internal fun CashUiState.defaultSymbol(): String =
    currencies.symbolFor(currencies.default).ifBlank { com.zillit.desktop.core.common.Money.symbol(currencies.default) }
        .ifBlank { currencies.default }

/** `40`, `40.5` — a figure as it would be typed. */
internal fun plainFigure(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

// -- Quick Codes — Auto-mapping -------------------------------------------------------

@Composable
private fun QuickCodesCard(state: CashUiState, draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    fun update(codes: List<QuickCode>) = edit(draft, onEvent) { copy(quickCodes = codes) }
    val accounts = state.chartAccounts.orEmpty().nominalCodes()
    ZillitSectionCard(
        title = str(S.desktop_pc_quick_codes_title),
        action = {
            SectionSaveFor(CashSettingsSection.QuickCodes, state, draft, onEvent)
            SectionAdd(str(S.desktop_pc_add_code)) { update(draft.quickCodes + QuickCode(name = "", vat = 0.0)) }
        },
    ) {
        ZillitText(
            text = str(S.desktop_pc_quick_codes_desc),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        if (draft.quickCodes.isEmpty()) {
            QuietLine(str(S.desktop_pc_quick_codes_empty), Modifier.padding(vertical = ZillitTheme.spacing.md))
            return@ZillitSectionCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldCaption(str(S.ah_lbl_title), Modifier.width(LABEL_WIDTH))
            FieldCaption(str(S.dm_rule_nominal), Modifier.weight(1f))
            FieldCaption(str(S.desktop_pc_col_keywords), Modifier.weight(1f))
            FieldCaption(str(S.ah_lbl_vat), Modifier.width(TAX_WIDTH))
            androidx.compose.foundation.layout.Spacer(Modifier.width(REMOVE_WIDTH))
        }
        draft.quickCodes.forEachIndexed { index, code ->
            if (index > 0) ZillitDivider()
            QuickCodeRow(
                code = code,
                accounts = accounts,
                onChange = { next -> update(draft.quickCodes.mapIndexed { i, old -> if (i == index) next else old }) },
                onRemove = { update(draft.quickCodes.filterIndexed { i, _ -> i != index }) },
            )
        }
    }
}

/** Label, nominal, keywords, tax — the web's four columns, and the ×. */
@Composable
private fun QuickCodeRow(
    code: QuickCode,
    accounts: List<com.zillit.desktop.feature.cashexpenses.domain.CashAccount>,
    onChange: (QuickCode) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        // Editable, unlike the web's read-only badge: a code added there can
        // never be named, which leaves the "—" badge nobody can change.
        ZillitTextField(
            value = code.name,
            onValueChange = { onChange(code.copy(name = it)) },
            placeholder = "—",
            modifier = Modifier.width(LABEL_WIDTH),
        )
        CashCodeField(
            value = code.nominalCode,
            onValueChange = { onChange(code.copy(nominalCode = it)) },
            accounts = accounts,
            placeholder = str(S.desktop_pc_search_nominal),
            modifier = Modifier.weight(1f),
        )
        CommaListField(
            values = code.keywords,
            onChange = { onChange(code.copy(keywords = it)) },
            placeholder = str(S.desktop_pc_keywords_placeholder),
            modifier = Modifier.weight(1f),
        )
        ZillitSelect(
            value = code.vat ?: 0.0,
            options = (VAT_RATES + listOfNotNull(code.vat)).distinct(),
            onSelect = { onChange(code.copy(vat = it)) },
            label = { "${plainFigure(it)}%" },
            modifier = Modifier.width(TAX_WIDTH),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.desktop_pc_remove_code),
            onClick = onRemove,
            tint = ZillitTheme.colors.textMuted,
        )
    }
}

/** The web's tax choices, in its order. */
private val VAT_RATES = listOf(20.0, 5.0, 0.0)
private val LABEL_WIDTH = 120.dp
private val TAX_WIDTH = 90.dp
private val REMOVE_WIDTH = 32.dp
