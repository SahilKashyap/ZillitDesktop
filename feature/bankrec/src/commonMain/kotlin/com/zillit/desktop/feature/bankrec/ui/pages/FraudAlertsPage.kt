package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.WorkspaceRows
import com.zillit.desktop.feature.bankrec.ui.AlertAction
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrDot
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTileGrid
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.bg
import com.zillit.desktop.feature.bankrec.ui.components.edge
import com.zillit.desktop.feature.bankrec.ui.components.fg
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.components.titleStyle

/** The five checks the web explains under the alerts, with the colour of their weight. */
private val AUTO_CHECKS = listOf(
    Triple(S.desktop_br_check_mandate, S.desktop_br_check_mandate_desc, BrTone.Red),
    Triple(S.desktop_br_check_split, S.desktop_br_check_split_desc, BrTone.Red),
    Triple(S.desktop_br_check_duplicate, S.desktop_br_check_duplicate_desc, BrTone.Amber),
    Triple(S.desktop_br_check_unregistered, S.desktop_br_check_unregistered_desc, BrTone.Amber),
    Triple(S.desktop_br_check_round, S.desktop_br_check_round_desc, BrTone.Blue),
)

/**
 * The payments the engine wants a person to look at, riskiest first.
 *
 * Each card carries the engine's reasons, the invoice it thinks the payment was
 * for and the supplier's registered bank details beside it — the comparison
 * that decides whether a changed sort code is fraud.
 */
@Composable
fun ColumnScope.FraudAlertsPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val page = state.fraudPage
    val alerts = state.fraudAlerts.forPeriod(state, page.periodChoice) { it.periodId }
    val active = alerts.count { it.isActive }

    ActionRow {
        BrBadge(str(S.desktop_br_n_active, active), BrTone.Red)
        PeriodFilter(state, page.periodChoice) { onEvent(BankRecEvent.SetFraudPeriod(it)) }
        ZillitButton(
            text = str(S.desktop_audit_log),
            onClick = { onEvent(BankRecEvent.OpenAuditLog) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Ledger,
        )
    }

    if (active > 0) {
        BrBanner(
            tone = BrTone.Red,
            icon = ZillitIcons.Shield,
            title = if (active == 1) {
                str(S.desktop_br_ai_detected_one, active)
            } else {
                str(S.desktop_br_ai_detected_many, active)
            },
            message = str(S.desktop_br_ai_detected_detail),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when {
        !state.fraudLoading && !state.periodsLoading && state.openPeriods.isEmpty() ->
            NoActivePeriod(ZillitIcons.Shield)
        (state.fraudLoading || state.periodsLoading) && state.fraudAlerts.isEmpty() -> BrSkeletonRows(4)
        alerts.isEmpty() -> BrEmpty(
            title = str(S.desktop_br_no_fraud_alerts_detected),
            icon = ZillitIcons.Shield,
            tone = BrTone.Green,
        )
        else -> alerts.forEach { alert -> AlertCard(alert, state, onEvent) }
    }

    AutoChecks()
}

// An alert shows only what the engine sent: each section is optional.
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun AlertCard(alert: FraudAlert, state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val tone = if (alert.isHighRisk) BrTone.Red else BrTone.Amber
    val acting = state.fraudPage.acting?.takeIf { it.first == alert.id }?.second
    BrCard(Modifier.fillMaxWidth(), padded = true, edge = tone.edge()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            RiskCircle(alert.riskScore, tone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZillitText(
                        alert.title.ifBlank { alert.typeLabel },
                        style = titleStyle(14.sp),
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    BrBadge(alert.riskLabel, tone)
                    if (!alert.isActive) {
                        BrBadge(
                            alert.status.label,
                            if (alert.status == FraudStatus.Escalated) BrTone.Red else BrTone.Green,
                        )
                    }
                }
                if (alert.description.isNotBlank()) {
                    ZillitText(
                        alert.description,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        if (alert.signals.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            BrTileGrid(
                columns = 3,
                minTile = 200.dp,
                gap = 10.dp,
                tiles = alert.signals.mapIndexed { index, signal ->
                    { modifier ->
                        Column(
                            modifier.clip(RoundedCornerShape(8.dp)).background(colors.surfaceSunken)
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BrDot(if (index == 0) colors.danger else colors.warning)
                                ZillitText(
                                    signal.title,
                                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 2,
                                )
                            }
                            if (signal.detail.isNotBlank() && signal.detail != signal.title) {
                                ZillitText(
                                    signal.detail,
                                    style = ZillitTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                },
            )
        }

        if (alert.hasSuggestion || alert.alertType == FraudType.UnregisteredPayee) {
            Spacer(Modifier.height(12.dp))
            SuggestedMatch(alert, state)
        }

        Spacer(Modifier.height(12.dp))
        ZillitDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitText(
                "${alert.typeLabel} · ${BankRecFormat.day(alert.createdAtMillis)}",
                style = mono(10.5.sp),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (alert.canDismiss) {
                ZillitButton(
                    text = if (acting == AlertAction.Dismiss) {
                        str(S.desktop_br_dismissing)
                    } else {
                        str(S.desktop_br_audit_investigated_no_issue)
                    },
                    onClick = { onEvent(BankRecEvent.DismissAlert(alert.id)) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = acting == null,
                )
            }
            if (alert.canEscalate) {
                ZillitButton(
                    text = if (acting == AlertAction.Escalate) {
                        str(S.desktop_br_escalating)
                    } else {
                        str(S.desktop_br_escalate_to_finance)
                    },
                    onClick = { onEvent(BankRecEvent.EscalateAlert(alert.id)) },
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Small,
                    enabled = acting == null,
                )
            }
        }
    }
}

@Composable
private fun RiskCircle(score: Int, tone: BrTone) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).border(3.dp, tone.fg(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZillitText(
                score.toString(),
                style = titleStyle(19.sp).copy(fontWeight = FontWeight.ExtraBold, lineHeight = 20.sp),
            )
            ZillitText(str(S.desktop_risk), style = mono(7.sp), color = ZillitTheme.colors.textMuted)
        }
    }
}

/**
 * The invoice the engine matched the payment to, and the supplier as the
 * register knows them — or, for an unregistered payee, that the register does
 * not.
 */
@Suppress("LongMethod") // The invoices and the registered vendor, as the web lists them.
@Composable
private fun SuggestedMatch(alert: FraudAlert, state: BankRecUiState) {
    val colors = ZillitTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BrTone.Amber.bg())
            .border(1.dp, BrTone.Amber.edge(), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.zillit.desktop.core.designsystem.component.ZillitIcon(
                ZillitIcons.BarChart,
                tint = colors.warning,
                size = 12.dp,
            )
            ZillitText(
                str(S.desktop_br_suggested_match_heading),
                style = mono(10.5.sp, FontWeight.SemiBold),
                color = colors.warning,
            )
        }
        alert.invoices.forEach { invoice ->
            val currency = invoice.currency ?: state.projectCurrency
            ZillitText(
                listOf(
                    invoice.invoiceNumber.ifBlank { BankRecFormat.DASH },
                    invoice.supplierName.ifBlank { str(S.desktop_unknown) },
                    BankRecFormat.plainMoney(invoice.grossAmount, currency),
                    invoice.payMethod.takeIf { it.isNotBlank() }?.let { WorkspaceRows.payMethodCode(it).uppercase() }
                        ?: BankRecFormat.DASH,
                ).joinToString("  ·  "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
            )
        }
        val vendor = alert.vendor
        if (vendor != null || alert.alertType == FraudType.UnregisteredPayee) {
            if (alert.invoices.isNotEmpty()) ZillitDivider()
            Row(
                Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (vendor != null) {
                    BrDot(colors.success)
                    ZillitText(
                        listOfNotNull(
                            str(S.desktop_br_registered_vendor, vendor.name),
                            vendor.sortCode.takeIf { it.isNotBlank() }
                                ?.let { str(S.desktop_dm_sort_code_value, BankRecFormat.sortCode(it)) },
                            vendor.accountNumber.takeIf { it.isNotBlank() }
                                ?.let { str(S.desktop_br_account_short, it) },
                        ).joinToString("  ·  "),
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    )
                } else {
                    BrDot(colors.danger)
                    ZillitText(
                        str(
                            S.desktop_br_not_in_supplier_register,
                            alert.transaction?.vendorName?.ifBlank { null } ?: str(S.desktop_unknown),
                        ),
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoChecks() {
    val colors = ZillitTheme.colors
    BrCard(
        Modifier.fillMaxWidth(),
        title = str(S.desktop_br_what_zillit_checks),
        icon = ZillitIcons.Shield,
        padded = true,
    ) {
        BrTileGrid(
            columns = 3,
            minTile = 220.dp,
            gap = 14.dp,
            tiles = AUTO_CHECKS.map { (titleKey, descriptionKey, tone) ->
                { modifier ->
                    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.padding(top = 5.dp)) { BrDot(tone.fg()) }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ZillitText(
                                str(titleKey),
                                style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            )
                            ZillitText(
                                str(descriptionKey),
                                style = ZillitTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    }
                }
            },
        )
    }
}
