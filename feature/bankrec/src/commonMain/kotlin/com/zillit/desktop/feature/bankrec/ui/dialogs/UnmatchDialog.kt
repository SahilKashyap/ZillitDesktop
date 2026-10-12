package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.BankRow
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.WorkspaceView
import com.zillit.desktop.feature.bankrec.ui.components.BrBanner
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.workspaceView

/**
 * Taking back one match or several — confirmed first, because a fraud line
 * returns to review rather than to unmatched, and its alert re-opens. The
 * dialog says so (and turns red) when any line it names was flagged.
 */
@Suppress("LongMethod") // The confirmation's title, both sides, the fraud warning and the two buttons.
@Composable
internal fun UnmatchDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val request = rememberLast(state.workspace.unmatch) ?: return
    val view = state.workspaceView()
    val rows = request.ids.mapNotNull { view.bankRow(it) }
    val count = request.ids.size
    val single = rows.singleOrNull()?.takeIf { count == 1 }
    val fraudCount = rows.count { it.txn.fraudType != null }
    val submitting = request.submitting
    ZillitDialogShell(
        title = if (count == 1) {
            str(S.desktop_br_unreconcile_transaction_q)
        } else {
            str(S.desktop_br_unreconcile_n_transactions_q, count)
        },
        onDismiss = { if (!submitting) onEvent(BankRecEvent.DismissUnmatch) },
        visible = state.workspace.unmatch != null,
        icon = if (fraudCount > 0) ZillitIcons.Shield else ZillitIcons.Warning,
        width = 560.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(BankRecEvent.DismissUnmatch) },
                variant = ButtonVariant.Tertiary,
                enabled = !submitting,
            )
            ZillitButton(
                text = when {
                    submitting -> str(S.desktop_br_unreconciling)
                    count == 1 -> str(S.desktop_br_unreconcile)
                    else -> str(S.desktop_br_unreconcile_n, count)
                },
                onClick = { onEvent(BankRecEvent.ConfirmUnmatch) },
                variant = if (fraudCount > 0) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = submitting,
            )
        },
    ) {
        if (single != null) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BankSide(single, view, tone = null)
                MatchedToCard(single, view)
            }
        } else {
            LineList(rows, view)
        }
        if (fraudCount > 0) {
            BrBanner(
                tone = BrTone.Red,
                icon = ZillitIcons.Shield,
                message = if (count == 1) {
                    str(S.desktop_br_unmatch_fraud_one)
                } else {
                    str(S.desktop_br_unmatch_fraud_many, fraudCount)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // No status named: a fraud line returns to fraud review, not unmatched;
        // the red box above says so.
        ZillitText(
            if (count == 1) {
                str(S.desktop_br_unmatch_removed_one)
            } else {
                str(S.desktop_br_unmatch_removed_many, count)
            },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/**
 * The ledger side of a single match, found the way the cross-highlight finds
 * it: by the entries' `tr_ids`, else by the line's own matched invoice ids.
 */
@Composable
private fun RowScope.MatchedToCard(bank: BankRow, view: WorkspaceView) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    val entries = view.ledgerRows.filter {
        bank.id in it.entry.transactionIds || it.entry.entityId in bank.txn.matchedInvoiceIds
    }
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(shape).background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitText(str(S.desktop_br_matched_to_heading).uppercase(), style = eyebrow(10.sp), color = colors.textMuted)
        if (entries.isEmpty()) {
            ZillitText(
                str(S.desktop_br_invoice_unavailable),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ZillitText(
                    entry.title,
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                )
                if (entry.reference.isNotBlank()) {
                    ZillitText(entry.reference, style = mono(11.sp), color = colors.textMuted, maxLines = 1)
                }
                ZillitText(
                    entry.amount?.let { signed(it, entry.entry.currency ?: view.projectCurrency) }
                        ?: BankRecFormat.DASH,
                    style = mono(14.sp, FontWeight.Bold),
                )
            }
        }
    }
}

/** Several lines: date, name and amount each, scrolling past a couple of hundred points. */
@Composable
private fun LineList(rows: List<BankRow>, view: WorkspaceView) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(shape).border(1.dp, colors.border, shape)
            .verticalScroll(rememberScrollState()),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) ZillitDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZillitText(
                    BankRecFormat.statementDay(row.txn.transactionDateMillis),
                    style = mono(10.5.sp),
                    color = colors.textMuted,
                    modifier = Modifier.width(52.dp),
                )
                ZillitText(
                    row.title,
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                ZillitText(
                    signed(row.amount, row.amountCurrency ?: view.statementCurrency),
                    style = mono(12.sp),
                    maxLines = 1,
                )
            }
        }
    }
}
