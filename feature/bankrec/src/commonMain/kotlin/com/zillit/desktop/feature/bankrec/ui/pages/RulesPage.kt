package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.FraudDetection
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.MatchRule
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState

private val THRESHOLD_WIDTH = 180.dp

/**
 * The bank accounts this production reconciles, and the checks that run.
 *
 * The two rule sections save separately, which is a correctness property
 * rather than a layout choice: one save carrying both would take whatever the
 * other section has unsaved along with it.
 */
@Composable
fun ColumnScope.RulesPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    Accounts(state)
    MatchRules(state, onEvent)
    FraudRules(state, onEvent)
}

@Composable
private fun ColumnScope.Accounts(state: BankRecUiState) {
    ZillitSectionCard(
        title = "Bank accounts",
        icon = ZillitIcons.Bank,
        meta = "${state.bankAccounts.size}",
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.bankAccounts.isEmpty()) {
            ZillitText(
                text = "No production bank accounts. They are added in Production Setup, " +
                    "not here — this page only shows what a reconciliation can run against.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            return@ZillitSectionCard
        }
        state.bankAccounts.forEach { account ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    ZillitText(text = account.name, style = ZillitTheme.typography.bodyMedium)
                    ZillitText(
                        text = listOfNotNull(
                            account.sortCode.takeIf { it.isNotBlank() }?.let { "Sort $it" },
                            account.accountNumber.takeIf { it.isNotBlank() }?.let { "Account $it" },
                        ).joinToString(" · ").ifBlank { "No details recorded" },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textMuted,
                    )
                }
                ZillitStatusPill(
                    label = account.currencyCode.ifBlank { state.projectCurrency },
                    tone = StatusTone.Neutral,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MatchRules(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val rules = state.rules
    ZillitSectionCard(
        title = "Matching rules",
        icon = ZillitIcons.Reload,
        meta = "Run when a statement is imported",
        action = {
            ZillitButton(
                text = "Save matching",
                onClick = { onEvent(BankRecEvent.SaveMatchRules) },
                size = ButtonSize.Small,
                loading = rules.savingMatch,
                enabled = rules.matchDirty,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        MatchRule.entries.forEach { rule ->
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ZillitCheckbox(
                        checked = rules.settings.autoMatch[rule.key] == true,
                        onCheckedChange = { onEvent(BankRecEvent.ToggleMatchRule(rule.key, it)) },
                        label = rule.label,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitStatusPill(label = rule.confidence, tone = StatusTone.Progress)
                }
                ZillitText(
                    text = rule.description,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                ZillitDivider()
            }
        }
    }
}

@Composable
private fun ColumnScope.FraudRules(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val rules = state.rules
    ZillitSectionCard(
        title = "Fraud detection",
        icon = ZillitIcons.Shield,
        meta = "Runs on every imported line",
        action = {
            ZillitButton(
                text = "Save detection",
                onClick = { onEvent(BankRecEvent.SaveFraudRules) },
                size = ButtonSize.Small,
                loading = rules.savingFraud,
                enabled = rules.fraudDirty,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        FraudDetection.entries.forEach { detection ->
            FraudRuleRow(detection, rules.settings.fraud[detection.key] ?: FraudRule(), state, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.FraudRuleRow(
    detection: FraudDetection,
    rule: FraudRule,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitCheckbox(
                checked = rule.enabled,
                onCheckedChange = { onEvent(BankRecEvent.ToggleFraudRule(detection.key, it)) },
                label = detection.label,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(label = detection.severity, tone = StatusTone.Pending)
        }
        ZillitText(
            text = detection.description,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        // Only a check that has a threshold gets a box. Sending an amount for
        // one that has none stores a shape the engine does not read, and the
        // check quietly stops running.
        if (detection.hasThreshold) {
            ZillitTextField(
                value = rule.amount?.toLong()?.toString().orEmpty(),
                onValueChange = { text ->
                    onEvent(
                        BankRecEvent.SetFraudThreshold(
                            detection.key,
                            text.filter { it.isDigit() }.toDoubleOrNull() ?: detection.defaultAmount,
                        ),
                    )
                },
                label = "Threshold (${state.projectCurrency})",
                enabled = rule.enabled,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.width(THRESHOLD_WIDTH),
            )
        }
        ZillitDivider()
    }
}
