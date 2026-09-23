package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRule
import com.zillit.desktop.feature.cashexpenses.domain.QuickCode
import com.zillit.desktop.feature.cashexpenses.domain.RuleProcess
import com.zillit.desktop.feature.cashexpenses.domain.RuleThreshold
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.personColumn

/**
 * The production's cash configuration.
 *
 * Senior accountants only — every switch here changes what other people are
 * allowed to do, so the page is gated in [com.zillit.desktop.feature.cashexpenses.ui.CashDestination]
 * rather than shown read-only. A settings screen you can see but not use
 * invites someone to ask why, and the answer is never satisfying.
 */
@Suppress("LongMethod") // A settings catalogue: splitting it hides what the page offers.
@Composable
fun CashSettingsPage(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val draft = state.settingsDraft ?: state.settings
    if (draft == null) {
        ScrollingPage { ZillitNotice(text = str(S.desktop_ce_loading_settings)) }
        return
    }

    val dirty = draft != state.settings

    ScrollingPage {
        ZillitNotice(
            text = str(S.desktop_ce_settings_intro),
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )

        ZillitSectionCard(
            title = str(S.desktop_ce_custodian_account),
            icon = ZillitIcons.Bank,
            meta = str(S.desktop_ce_custodian_meta),
        ) {
            ZillitTextField(
                value = draft.custodianAccount,
                onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(custodianAccount = it))) },
                label = str(S.desktop_ce_float_custodian_account),
                placeholder = "1200",
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitTextField(
                    value = draft.bsCodeFrom,
                    onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(bsCodeFrom = it))) },
                    label = str(S.desktop_ce_bs_codes_from),
                    modifier = Modifier.weight(1f),
                )
                ZillitTextField(
                    value = draft.bsCodeTo,
                    onValueChange = { onEvent(CashEvent.EditSettings(draft.copy(bsCodeTo = it))) },
                    label = str(S.recce_weather_to),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ZillitSectionCard(title = str(S.desktop_card_nav_workflow), icon = ZillitIcons.Shield) {
            SettingSwitch(
                checked = draft.requireCoordinatorCoding,
                label = str(S.desktop_ce_coordinators_code_label),
                detail = str(S.desktop_ce_coordinators_code_detail),
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(requireCoordinatorCoding = it))) },
            )
            SettingSwitch(
                checked = draft.requireSeniorSignOff,
                label = str(S.desktop_card_senior_signoff_label),
                detail = str(S.desktop_ce_senior_signoff_detail),
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(requireSeniorSignOff = it))) },
            )
            SettingSwitch(
                checked = draft.overrideFloatRequest,
                label = str(S.desktop_ce_override_float_label),
                detail = str(S.desktop_ce_override_float_detail),
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(overrideFloatRequest = it))) },
            )
            SettingSwitch(
                checked = draft.overrideReceiptBatch,
                label = str(S.desktop_ce_override_batch_label),
                detail = str(S.desktop_ce_override_batch_detail),
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(overrideReceiptBatch = it))) },
            )
        }

        ZillitSectionCard(title = str(S.desktop_ce_reimbursement), icon = ZillitIcons.Wallet) {
            SettingSwitch(
                checked = draft.reimburseToPayroll,
                label = str(S.desktop_ce_payroll_reimbursement_label),
                detail = str(S.desktop_ce_payroll_reimbursement_detail),
                onChange = { onEvent(CashEvent.EditSettings(draft.copy(reimburseToPayroll = it))) },
            )
        }

        DeductionRulesCard(draft, onEvent)
        QuickCodesCard(draft, onEvent)

        ZillitSectionCard(
            title = str(S.ah_settings_team_posting),
            icon = ZillitIcons.Users,
            meta = str(S.desktop_ce_member_count, draft.teamMembers.size),
            padded = false,
        ) {
            ZillitDataTable(
                rows = draft.teamMembers,
                columns = teamColumns(),
                key = { it.userId },
                emptyTitle = str(S.desktop_ce_no_team_configured),
                emptyMessage = str(S.desktop_ce_team_empty_note),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = str(S.desktop_ce_save_settings),
                onClick = { onEvent(CashEvent.SaveSettings) },
                enabled = dirty && !state.busy,
                loading = state.busy,
            )
            if (dirty) {
                ZillitButton(
                    text = str(S.desktop_dm_discard_changes),
                    onClick = { state.settings?.let { onEvent(CashEvent.EditSettings(it)) } },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitText(
                    text = str(S.desktop_unsaved_changes),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.warning,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    label: String,
    detail: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitCheckbox(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
    }
}

@Suppress("MagicNumber") // Column proportions; naming each would not clarify them.
private fun teamColumns(): List<TableColumn<CashTeamMember>> = listOf(
    personColumn(str(S.name), ColumnWidth.Weight(1.8f), userId = { it.userId }) { it.name },
    TableColumn(
        header = str(S.desktop_ce_seniority),
        width = ColumnWidth.Weight(1f),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.isSenior) str(S.desktop_senior) else str(S.desktop_ce_team),
                tone = if (row.isSenior) StatusTone.Done else StatusTone.Neutral,
            )
        },
    ),
    TableColumn(
        header = str(S.dm_nom_table_override),
        width = ColumnWidth.Weight(1f),
        cell = { row ->
            ZillitStatusPill(
                label = if (row.canOverride) str(S.desktop_ce_allowed) else str(S.no),
                tone = if (row.canOverride) StatusTone.Pending else StatusTone.Neutral,
            )
        },
    ),
    textColumn(str(S.desktop_posting_limit), ColumnWidth.Weight(1f), numeric = true) {
        // No limit is a real answer, and printing it as "0.00" would read as
        // "may post nothing" — the opposite of what it means.
        it.postingLimit?.let { limit -> Money.format(limit, null) } ?: str(S.desktop_ce_no_limit)
    },
)


/**
 * The rules that fire on a receipt's words and its value.
 *
 * Four ship switched off; a production turns on the ones it runs. Each says
 * what it does and at what threshold, and the words that trigger it are shown
 * rather than hidden behind an editor — an accountant deciding whether to
 * enable "Fuel Deduction" needs to know it fires on the word "diesel".
 */
@Composable
private fun DeductionRulesCard(draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    ZillitSectionCard(
        title = str(S.ah_settings_deduction_rules),
        icon = ZillitIcons.Ledger,
        meta = str(
            S.desktop_ce_rules_on_count,
            draft.deductionRules.count { it.enabled },
            draft.deductionRules.size,
        ),
    ) {
        if (draft.deductionRules.isEmpty()) {
            ZillitText(
                text = str(S.desktop_ce_no_rules_configured),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        draft.deductionRules.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            DeductionRuleRow(draft, rule, onEvent)
        }
    }
}

@Composable
private fun DeductionRuleRow(draft: CashSettings, rule: DeductionRule, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    fun replace(next: DeductionRule) = onEvent(
        CashEvent.EditSettings(
            draft.copy(deductionRules = draft.deductionRules.map { if (it.id == rule.id) next else it }),
        ),
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(
                    text = rule.title.ifBlank { rule.id },
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (rule.description.isNotBlank()) {
                    ZillitText(
                        text = rule.description,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
            ZillitStatusPill(
                label = rule.processType.label,
                tone = when (rule.processType) {
                    RuleProcess.DeductAmount -> StatusTone.Rejected
                    RuleProcess.SeniorReview -> StatusTone.Pending
                    RuleProcess.NeedQuery -> StatusTone.Progress
                },
            )
            ZillitSwitch(checked = rule.enabled, onCheckedChange = { replace(rule.copy(enabled = it)) })
        }
        RuleThresholdRow(rule, replace = ::replace)
    }
}

/** How much, and on what — inert until the rule above it is switched on. */
@Composable
private fun RuleThresholdRow(rule: DeductionRule, replace: (DeductionRule) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitSelect(
            value = rule.thresholdType,
            options = RuleThreshold.entries,
            onSelect = { replace(rule.copy(thresholdType = it)) },
            label = { it.label },
            enabled = rule.enabled,
            modifier = Modifier.width(THRESHOLD_WIDTH),
        )
        ZillitTextField(
            value = rule.thresholdValue.trimmedText(),
            onValueChange = { typed -> replace(rule.copy(thresholdValue = typed.toDoubleOrNull() ?: 0.0)) },
            keyboardType = KeyboardType.Number,
            enabled = rule.enabled,
            modifier = Modifier.width(VALUE_WIDTH),
        )
        ZillitText(
            text = if (rule.triggerCodes.isEmpty()) {
                str(S.desktop_ce_on_every_receipt)
            } else {
                str(S.desktop_ce_on_codes, rule.triggerCodes.joinToString(", "))
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Whole numbers without a trailing `.0`, which is how a threshold is typed. */
private fun Double.trimmedText(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

/**
 * The coding shortcuts offered when a receipt is coded.
 *
 * Name, nominal code, VAT and the words that pick it automatically — the four
 * fields the web stores. Rows are added and removed here; the codes themselves
 * are free text because the chart is a different service.
 */
@Composable
private fun QuickCodesCard(draft: CashSettings, onEvent: (CashEvent) -> Unit) {
    fun update(codes: List<QuickCode>) = onEvent(CashEvent.EditSettings(draft.copy(quickCodes = codes)))
    ZillitSectionCard(
        title = str(S.desktop_ce_quick_codes),
        icon = ZillitIcons.Receipt,
        meta = str(S.desktop_ce_code_count, draft.quickCodes.size),
        action = {
            ZillitButton(
                text = str(S.desktop_add_code),
                onClick = { update(draft.quickCodes + QuickCode(name = "")) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (draft.quickCodes.isEmpty()) {
            ZillitText(
                text = str(S.desktop_ce_no_quick_codes),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            return@ZillitSectionCard
        }
        draft.quickCodes.forEachIndexed { index, code ->
            if (index > 0) ZillitDivider()
            QuickCodeRow(
                code = code,
                onChange = { next ->
                    update(draft.quickCodes.mapIndexed { i, existing -> if (i == index) next else existing })
                },
                onRemove = { update(draft.quickCodes.filterIndexed { i, _ -> i != index }) },
            )
        }
    }
}

/** One shortcut: what it is called, what it codes to, its VAT, and the words that pick it. */
@Composable
private fun QuickCodeRow(code: QuickCode, onChange: (QuickCode) -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitTextField(
            value = code.name,
            onValueChange = { onChange(code.copy(name = it)) },
            placeholder = str(S.av_category),
            modifier = Modifier.weight(NAME_WEIGHT),
        )
        ZillitTextField(
            value = code.nominalCode,
            onValueChange = { onChange(code.copy(nominalCode = it)) },
            placeholder = str(S.dm_rule_nominal),
            modifier = Modifier.weight(1f),
        )
        ZillitTextField(
            value = code.vat?.trimmedText().orEmpty(),
            onValueChange = { onChange(code.copy(vat = it.toDoubleOrNull())) },
            placeholder = str(S.desktop_ce_vat_percent),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(VAT_WIDTH),
        )
        ZillitTextField(
            value = code.keywords.joinToString(", "),
            onValueChange = { typed ->
                onChange(code.copy(keywords = typed.split(",").map { it.trim() }.filter { it.isNotBlank() }))
            },
            placeholder = str(S.desktop_ce_words_that_pick_it),
            modifier = Modifier.weight(KEYWORD_WEIGHT),
        )
        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.bs_chip_remove, code.name.ifBlank { str(S.desktop_ce_this_code) }),
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

private val THRESHOLD_WIDTH = 180.dp
private val VALUE_WIDTH = 110.dp
private val VAT_WIDTH = 90.dp
private const val NAME_WEIGHT = 1.2f
private const val KEYWORD_WEIGHT = 1.6f
