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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** What has been earned, by the week it was worked, and the holiday pot. */
@Composable
internal fun ColumnScope.PayPage(state: SaUiState) {
    val pay = state.pay
    if (pay == null) {
        if (!state.loading) {
            ZillitEmptyState(
                title = str(S.desktop_sa_no_pay_yet),
                message = str(S.desktop_sa_no_pay_message),
                icon = ZillitIcons.Info,
            )
        }
        return
    }
    val currency = state.profile?.currency

    ZillitSectionLabel(str(S.desktop_holiday))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(label = str(S.desktop_accrued), value = money(pay.holiday.accrued, currency))
        ZillitStatTile(label = str(S.desktop_paid), value = money(pay.holiday.paid, currency))
        ZillitStatTile(label = str(S.asset_total), value = money(pay.holiday.total, currency))
    }

    ZillitSectionLabel(str(S.desktop_sa_by_week))
    if (pay.runs.isEmpty()) {
        ZillitText(
            text = str(S.desktop_sa_no_weeks_made_up),
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
                        text = str(S.desktop_sa_week_of, day(run.weekStarting)),
                        style = ZillitTheme.typography.bodyMedium,
                    )
                    ZillitText(
                        text = if (run.days == 1) str(S.desktop_one_day) else str(S.ah_days_format, run.days),
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
                title = str(S.desktop_sa_no_record_title),
                message = str(S.desktop_sa_no_record_message),
                icon = ZillitIcons.Info,
            )
        }
        return
    }

    if (profile.accountStatus.isNotEmpty()) {
        ZillitSectionCard(
            title = if (profile.complete) str(S.desktop_sa_details_complete) else str(S.desktop_sa_still_needed),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                if (!profile.complete) {
                    ZillitText(
                        // Named for the consequence, not the rule: this is the
                        // reason a payment sits still.
                        text = str(S.desktop_sa_payments_blocked),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                profile.accountStatus.forEach { check -> CheckRow(check) }
            }
        }
    }

    ZillitSectionCard(title = str(S.dm_nda_fill_title), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            DetailLine(str(S.desktop_reference), profile.artisteRef.ifBlank { profile.refNumber })
            DetailLine(str(S.av_category), profile.category)
            DetailLine(str(S.desktop_engagement), profile.engagementType)
            DetailLine(str(S.desktop_dm_agency), profile.agencyName)
            if (profile.isMinor) {
                ZillitStatusPill(label = str(S.desktop_sa_minor_chaperone), tone = StatusTone.Pending)
            }
        }
    }

    ZillitSectionCard(title = str(S.desktop_sa_where_paid), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            DetailLine(str(S.desktop_bank), profile.bank.name)
            DetailLine(str(S.desktop_account_name), profile.bank.accountHolderName)
            // Shown as stored — the account number is the artiste's own and
            // masking it here would stop them checking it is the right one.
            DetailLine(str(S.account_number), profile.bank.accountNumber)
            DetailLine(str(S.ah_lbl_sort_code), profile.bank.sortCode)
            DetailLine(str(S.ah_lbl_iban_row), profile.bank.ibanNumber)
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
            label = if (check.ok) str(S.done_text) else str(S.desktop_needed),
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
