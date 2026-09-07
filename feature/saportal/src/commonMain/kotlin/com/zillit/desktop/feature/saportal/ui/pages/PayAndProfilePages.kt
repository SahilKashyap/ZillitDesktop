package com.zillit.desktop.feature.saportal.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.domain.AccountCheck
import com.zillit.desktop.feature.saportal.ui.SaUiState
import com.zillit.desktop.feature.saportal.ui.day
import com.zillit.desktop.feature.saportal.ui.money

/** What has been earned, by the week it was worked, and the holiday pot. */
@Composable
internal fun ColumnScope.PayPage(state: SaUiState) {
    val pay = state.pay
    if (pay == null) {
        if (!state.loading) {
            ZillitEmptyState(
                title = "No pay yet",
                message = "Your weeks appear here once days have been signed.",
                icon = ZillitIcons.Info,
            )
        }
        return
    }
    val currency = state.profile?.currency

    ZillitSectionLabel("Holiday")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(label = "Accrued", value = money(pay.holiday.accrued, currency))
        ZillitStatTile(label = "Paid", value = money(pay.holiday.paid, currency))
        ZillitStatTile(label = "Total", value = money(pay.holiday.total, currency))
    }

    ZillitSectionLabel("By week")
    if (pay.runs.isEmpty()) {
        ZillitText(
            text = "No weeks have been made up yet.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        return
    }
    pay.runs.forEach { run ->
        ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = "Week of ${day(run.weekStarting)}",
                        style = ZillitTheme.typography.bodyMedium,
                    )
                    ZillitText(
                        text = "${run.days} day${if (run.days == 1) "" else "s"}",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                ZillitText(text = money(run.gross, currency), style = ZillitTheme.typography.numeric)
                run.status.takeIf { it.isNotBlank() }?.let {
                    ZillitStatusPill(label = it.replaceFirstChar(Char::uppercase), tone = StatusTone.Neutral)
                }
            }
        }
    }
}

/**
 * The artiste's own record, led by anything still outstanding.
 *
 * A missing bank account or unsigned document is why a payment stalls, so
 * the checklist comes before the details rather than after them.
 */
@Composable
internal fun ColumnScope.ProfilePage(state: SaUiState) {
    val profile = state.profile
    if (profile == null) {
        if (!state.loading) {
            ZillitEmptyState(
                title = "No record found",
                message = "This project has no artiste record for you.",
                icon = ZillitIcons.Info,
            )
        }
        return
    }

    if (profile.accountStatus.isNotEmpty()) {
        ZillitSectionCard(
            title = if (profile.complete) "Your details are complete" else "Still needed",
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (!profile.complete) {
                    ZillitText(
                        // Named for the consequence, not the rule: this is the
                        // reason a payment sits still.
                        text = "Payments cannot be made up until these are in place.",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                profile.accountStatus.forEach { check -> CheckRow(check) }
            }
        }
    }

    ZillitSectionCard(title = "Your details", modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            DetailLine("Reference", profile.artisteRef.ifBlank { profile.refNumber })
            DetailLine("Category", profile.category)
            DetailLine("Engagement", profile.engagementType)
            DetailLine("Agency", profile.agencyName)
            if (profile.isMinor) {
                ZillitStatusPill(label = "Minor — chaperone rules apply", tone = StatusTone.Pending)
            }
        }
    }

    ZillitSectionCard(title = "Where you are paid", modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            DetailLine("Bank", profile.bank.name)
            DetailLine("Account name", profile.bank.accountHolderName)
            // Shown as stored — the account number is the artiste's own and
            // masking it here would stop them checking it is the right one.
            DetailLine("Account number", profile.bank.accountNumber)
            DetailLine("Sort code", profile.bank.sortCode)
            DetailLine("IBAN", profile.bank.ibanNumber)
        }
    }
}

@Composable
private fun CheckRow(check: AccountCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitStatusPill(
            label = if (check.ok) "Done" else "Needed",
            tone = if (check.ok) StatusTone.Done else StatusTone.Pending,
        )
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = check.label, style = ZillitTheme.typography.bodyMedium)
            check.detail.takeIf { it.isNotBlank() }?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(WIDE))
    }
}

private const val WIDE = 2f
