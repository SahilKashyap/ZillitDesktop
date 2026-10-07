package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.SpendDeductionRule
import com.zillit.desktop.feature.accounthub.domain.SpendQuickCode
import com.zillit.desktop.feature.accounthub.domain.SpendSettings
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.Chip
import com.zillit.desktop.feature.accounthub.ui.components.CoaCodeField
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.GhostAddButton
import com.zillit.desktop.feature.accounthub.ui.components.HairLine
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard
import com.zillit.desktop.feature.accounthub.ui.components.groupAmount
import com.zillit.desktop.feature.accounthub.ui.components.quickCreateHandler

// -- deduction rules ---------------------------------------------------------------------

@Composable
internal fun ColumnScope.DeductionPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (SpendSettings) -> Unit,
) {
    val symbol = state.setup.currencies.saved.default?.symbol.orEmpty()
    SubCard(
        action = {
            if (editable) {
                GhostAddButton(str(S.desktop_add_rule), onClick = { onEvent(AccountHubEvent.ComposeSpendRule(null)) })
            }
        },
        padded = false,
    ) {
        if (value.deductionRules.isEmpty()) {
            FieldHint(str(S.desktop_pc_deduction_empty), Modifier.padding(ZillitTheme.spacing.lg))
        }
        value.deductionRules.forEachIndexed { index, rule ->
            if (index > 0) HairLine()
            DeductionRow(
                rule = rule,
                symbol = symbol,
                editable = editable,
                onToggle = { on ->
                    val rows = value.deductionRules.mapIndexed { i, r -> if (i == index) r.copy(enable = on) else r }
                    update(value.copy(deductionRules = rows))
                },
                onEdit = { onEvent(AccountHubEvent.ComposeSpendRule(index)) },
                onRemove = {
                    update(value.copy(deductionRules = value.deductionRules.filterIndexed { i, _ -> i != index }))
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeductionRow(
    rule: SpendDeductionRule,
    symbol: String,
    editable: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(ICON_CIRCLE).clip(CircleShape).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Calculator, tint = colors.accentText, size = 14.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = rule.title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                )
                if (rule.systemDefault) Pill(str(S.desktop_language_system_short))
            }
            if (rule.description.isNotBlank()) FieldHint(rule.description)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Pill(processLabel(rule.processType), tone = processTone(rule.processType))
                Pill(valueLabel(rule, symbol), tone = StatusTone.Pending)
                rule.triggerCodes.forEach { Chip(it) }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSwitch(checked = rule.enable, onCheckedChange = onToggle, enabled = editable)
            if (editable) {
                ZillitButton(
                    text = str(S.edit),
                    onClick = onEdit,
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
                // A system rule can be re-tuned and switched off but never deleted.
                if (!rule.systemDefault) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.remove),
                        onClick = onRemove,
                        tint = colors.danger,
                    )
                }
            }
        }
    }
}

internal fun processLabel(process: String): String = when (process) {
    SpendDeductionRule.DEDUCT_AMOUNT -> str(S.desktop_hub_sp_rule_deduct)
    SpendDeductionRule.SENIOR_REVIEW -> str(S.desktop_hub_sp_rule_senior_review)
    SpendDeductionRule.NEED_QUERY -> str(S.desktop_hub_sp_rule_query)
    else -> process
}

private fun processTone(process: String): StatusTone = when (process) {
    SpendDeductionRule.DEDUCT_AMOUNT -> StatusTone.Rejected
    SpendDeductionRule.SENIOR_REVIEW -> StatusTone.Pending
    SpendDeductionRule.NEED_QUERY -> StatusTone.Progress
    else -> StatusTone.Neutral
}

private fun valueLabel(rule: SpendDeductionRule, symbol: String): String {
    val number = rule.thresholdValue.toString().removeSuffix(".0")
    return if (rule.isPercentage) "$number%" else str(S.desktop_hub_sp_rule_min, symbol, groupAmount(number))
}

// -- quick codes -----------------------------------------------------------------------------

@Composable
internal fun ColumnScope.QuickCodesPane(
    value: SpendSettings,
    state: AccountHubUiState,
    editable: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
    update: (SpendSettings) -> Unit,
) {
    val create = quickCreateHandler(state, onEvent)
    fun patch(index: Int, next: SpendQuickCode) =
        update(value.copy(quickCodes = value.quickCodes.mapIndexed { i, c -> if (i == index) next else c }))
    SubCard(
        action = {
            if (editable) {
                GhostAddButton(
                    str(S.desktop_add_code),
                    onClick = { update(value.copy(quickCodes = value.quickCodes + SpendQuickCode())) },
                )
            }
        },
        padded = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            MonoLabel(str(S.ah_lbl_title), Modifier.weight(1f))
            MonoLabel(str(S.dm_rule_nominal), Modifier.weight(1.4f))
            MonoLabel(str(S.desktop_hub_sp_col_keywords), Modifier.weight(1.6f))
            MonoLabel(str(S.desktop_hub_sp_col_deduction_pct), Modifier.width(VAT_COLUMN))
            Box(Modifier.width(REMOVE))
        }
        value.quickCodes.forEachIndexed { index, code ->
            HairLine()
            QuickCodeRow(
                code = code,
                accounts = state.chart.accounts,
                editable = editable,
                onCreate = create,
                onChange = { patch(index, it) },
                onRemove = { update(value.copy(quickCodes = value.quickCodes.filterIndexed { i, _ -> i != index })) },
            )
        }
    }
}

@Composable
private fun QuickCodeRow(
    code: SpendQuickCode,
    accounts: List<CoaAccount>,
    editable: Boolean,
    onCreate: ((String, String, CoaCostType) -> Unit)?,
    onChange: (SpendQuickCode) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitTextField(
            value = code.name,
            onValueChange = { onChange(code.copy(name = it)) },
            placeholder = str(S.ah_lbl_title),
            enabled = editable,
            modifier = Modifier.weight(1f),
        )
        CoaCodeField(
            value = code.nominalCode,
            onValueChange = { onChange(code.copy(nominalCode = it)) },
            accounts = accounts,
            placeholder = str(S.desktop_pc_search_nominal),
            enabled = editable,
            onCreate = onCreate,
            modifier = Modifier.weight(1.4f),
        )
        ZillitTextField(
            value = code.keywordsText,
            onValueChange = { onChange(code.copy(keywordsText = it)) },
            placeholder = str(S.desktop_hub_sp_keywords_ph),
            enabled = editable,
            modifier = Modifier.weight(1.6f),
        )
        // A stored rate outside the web's three stays offered, so opening the row does not change it.
        val rates = (SpendQuickCode.VAT_CHOICES + code.vat).distinct()
        HubSelect(
            value = code.vat,
            options = rates,
            label = { "${it.toString().removeSuffix(".0")}%" },
            onSelect = { picked -> if (picked != null) onChange(code.copy(vat = picked)) },
            enabled = editable,
            searchable = false,
            showInitials = false,
            modifier = Modifier.width(VAT_COLUMN),
        )
        Box(Modifier.width(REMOVE), contentAlignment = Alignment.Center) {
            if (editable) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = str(S.remove),
                    onClick = onRemove,
                    tint = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

private val ICON_CIRCLE = 32.dp
private val VAT_COLUMN = 150.dp
private val REMOVE = 36.dp
