package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.FraudDetection
import com.zillit.desktop.feature.bankrec.domain.MatchRule
import com.zillit.desktop.feature.bankrec.domain.RuleSeverity
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BankRecIcons
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBankIdentity
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrInitialTile
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.BrTag
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.mono

/**
 * The accounts the reconciliation runs over, and the rules it runs.
 *
 * The accounts are read-only here — they are created and edited in Production
 * Setup. The two rule cards each save on their own, and only offer to once
 * something in them has changed.
 */
@Composable
fun ColumnScope.SettingsPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    BankAccountsCard(state)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < TWO_COLUMNS) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                MatchRulesCard(state, onEvent, Modifier.fillMaxWidth())
                FraudRulesCard(state, onEvent, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                MatchRulesCard(state, onEvent, Modifier.weight(1f))
                FraudRulesCard(state, onEvent, Modifier.weight(1f))
            }
        }
    }
}

@Suppress("LongMethod") // One column per field the web's accounts table shows.
@Composable
private fun BankAccountsCard(state: BankRecUiState) {
    val colors = ZillitTheme.colors
    val figure = mono(12.sp)
    BrCard(Modifier.fillMaxWidth(), title = "Connected Bank Accounts", icon = ZillitIcons.Bank) {
        if (state.periodsLoading && state.bankAccounts.isEmpty()) {
            BrSkeletonRows(2)
            return@BrCard
        }
        BrTable(
            rows = state.bankAccounts,
            key = { it.id },
            empty = {
                ZillitText(
                    "No bank accounts yet. Add one from Production Setup → Bank Accounts.",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
            },
            columns = listOf(
                BrColumn("Bank Name", weight = 1.4f) { account ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        BrInitialTile(account.displayName, size = 24.dp)
                        BrBankIdentity(account.displayName, compact = false)
                    }
                },
                BrColumn("Account Holder") { account ->
                    ZillitText(
                        account.holderName.ifBlank { BankRecFormat.DASH },
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn("Sort / Account", width = 170.dp) { account ->
                    val sort = BankRecFormat.sortCode(account.sortCode).ifBlank { BankRecFormat.DASH }
                    ZillitText(
                        "$sort · ${account.accountNumber.ifBlank { BankRecFormat.DASH }}",
                        style = figure,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn("IBAN", weight = 1.2f) { account ->
                    ZillitText(
                        account.iban.ifBlank { BankRecFormat.DASH },
                        style = figure,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                },
                BrColumn("Nominal", width = 80.dp) { account ->
                    ZillitText(
                        account.nominalCode.ifBlank { BankRecFormat.DASH },
                        style = mono(13.sp),
                        color = colors.textSecondary,
                    )
                },
            ),
        )
    }
}

@Composable
private fun MatchRulesCard(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit, modifier: Modifier) {
    val rules = state.rules
    BrCard(modifier, title = "Auto-Match Rules", icon = BankRecIcons.Bolt) {
        if (rules.loading) {
            BrSkeletonRows(4)
            return@BrCard
        }
        MatchRule.entries.forEachIndexed { index, rule ->
            if (index > 0) ZillitDivider()
            RuleRow(
                checked = rules.settings.autoMatch[rule.key] ?: true,
                onChecked = { onEvent(BankRecEvent.ToggleMatchRule(rule.key, it)) },
                label = rule.label,
                description = rule.description,
            ) {
                BrTag(rule.confidence, if (rule == MatchRule.AmountAndReference) BrTone.Green else BrTone.Amber)
            }
        }
        if (rules.matchDirty) SaveBar(rules.savingMatch) { onEvent(BankRecEvent.SaveMatchRules) }
    }
}

@Composable
private fun FraudRulesCard(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit, modifier: Modifier) {
    val rules = state.rules
    BrCard(
        modifier,
        title = "Fraud Detection Thresholds",
        icon = ZillitIcons.Shield,
        titleRight = { BrBadge("Active", BrTone.Red) },
    ) {
        if (rules.loading) {
            BrSkeletonRows(4)
            return@BrCard
        }
        FraudDetection.entries.forEachIndexed { index, detection ->
            if (index > 0) ZillitDivider()
            val rule = rules.settings.fraud[detection.key]
            RuleRow(
                checked = rule?.enabled ?: true,
                onChecked = { onEvent(BankRecEvent.ToggleFraudRule(detection.key, it)) },
                label = detection.label,
                description = detection.description,
            ) {
                if (detection.hasThreshold) {
                    ThresholdField(
                        amount = rule?.amount ?: detection.defaultAmount,
                        currency = state.projectCurrency,
                        onChange = { onEvent(BankRecEvent.SetFraudThreshold(detection.key, it)) },
                    )
                }
                BrBadge(
                    detection.severity,
                    if (detection.tone == RuleSeverity.Critical) BrTone.Red else BrTone.Amber,
                )
            }
        }
        if (rules.fraudDirty) SaveBar(rules.savingFraud) { onEvent(BankRecEvent.SaveFraudRules) }
    }
}

@Composable
private fun RuleRow(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    label: String,
    description: String,
    trailing: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(interaction)
            .background(if (hovered) colors.surfaceHover.copy(alpha = HOVER_ALPHA) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitCheckbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(label, style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(
                description,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = colors.textMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trailing()
        }
    }
}

/** Whole units, grouped as they are typed — `10,000` — as the web's box reads. */
@Composable
private fun ThresholdField(amount: Double?, currency: String, onChange: (Double?) -> Unit) {
    val whole = (amount ?: 0.0).toLong()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(Money.symbol(currency), style = mono(11.sp), color = ZillitTheme.colors.textMuted)
        ZillitTextField(
            value = Money.group(whole.toDouble(), 0),
            onValueChange = { text -> onChange(text.filter { it.isDigit() }.toLongOrNull()?.toDouble() ?: 0.0) },
            modifier = Modifier.width(92.dp),
        )
    }
}

@Composable
private fun SaveBar(saving: Boolean, onSave: () -> Unit) {
    ZillitDivider()
    Row(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(
            horizontal = 16.dp,
            vertical = 10.dp,
        ),
        horizontalArrangement = Arrangement.End,
    ) {
        ZillitButton(
            text = if (saving) "Saving…" else "Save",
            onClick = onSave,
            size = ButtonSize.Small,
            loading = saving,
            enabled = !saving,
        )
    }
}

private val TWO_COLUMNS = 900.dp
private const val HOVER_ALPHA = 0.6f
