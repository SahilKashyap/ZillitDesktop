package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchStatus
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.Settlement
import com.zillit.desktop.feature.cashexpenses.ui.BatchStatusPill
import com.zillit.desktop.feature.cashexpenses.ui.CashDestination
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashUiState
import com.zillit.desktop.feature.cashexpenses.ui.CrewEvent
import com.zillit.desktop.feature.cashexpenses.ui.LifecycleBar
import com.zillit.desktop.feature.cashexpenses.ui.LocalCashPeople
import com.zillit.desktop.feature.cashexpenses.ui.date

/**
 * "Receipt Details" — one receipt of the open batch (`MyClaimsList.ClaimDetailModal`).
 *
 * Drawn at the screen root: a dialog shell inside the history pane's
 * scrolling column would draw at its foot.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The web's modal, top to bottom.
@Composable
fun CrewClaimDialog(state: CashUiState, onEvent: (CashEvent) -> Unit) {
    val history = state.destination == CashDestination.ReceiptsHistory ||
        state.destination == CashDestination.OutOfPocketHistory
    val batch = state.selectedBatch
    val claim = state.crew.openClaimId?.let { id -> state.panelClaims.firstOrNull { it.id == id } }
    val visible = history && batch != null && claim != null
    ZillitDialogShell(
        title = str(S.ah_receipt_details),
        onDismiss = { onEvent(CrewEvent.CloseClaim) },
        visible = visible,
        icon = ZillitIcons.Receipt,
        width = DIALOG_WIDTH,
    ) {
        if (batch == null || claim == null) return@ZillitDialogShell
        val colors = ZillitTheme.colors
        val money = { amount: Double? -> state.formatMoney(amount, batch.currency) }
        LifecycleBar(batch.status)
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = claim.description.ifBlank { "—" }, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = listOfNotNull(
                        date(claim.receiptDate),
                        claim.category?.takeIf { it.isNotBlank() }?.let(ExpenseCategory::label),
                        claim.supplier?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    // The batch's thread — the one the accountant writes to.
                    ZillitButton(
                        text = str(S.ah_cd_query),
                        onClick = {
                            onEvent(CrewEvent.CloseClaim)
                            onEvent(CrewEvent.ShowQuery(true))
                        },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Chat,
                    )
                    BatchStatusPill(batch.status, accountant = false)
                }
                ZillitText(text = money(claim.grossAmount), style = ZillitTheme.typography.titleMedium)
            }
        }
        DetailGrid(claim, batch)
        RejectionOrQuery(batch)
        ReceiptFile(claim, onEvent)
        LineItems(claim, money)
        if (!claim.taxType.isNullOrBlank()) {
            ZillitText(
                text = str(
                    S.desktop_pc_tax_summary,
                    formatRate(claim.taxRate),
                    money(claim.netAmount),
                    money(claim.vatAmount),
                    money(claim.grossAmount),
                ),
                style = ZillitTheme.typography.numeric,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DetailGrid(claim: Claim, batch: ClaimBatch) {
    val cells = listOfNotNull(
        batch.reference.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_batch) to "#$it" },
        claim.category?.takeIf { it.isNotBlank() }?.let { str(S.av_category) to ExpenseCategory.label(it) },
        batch.settlementType?.takeIf { it.isNotBlank() }?.let { str(S.desktop_ce_settlement) to Settlement.label(it) },
        claim.costCode?.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_cost_code) to it },
        claim.episode?.takeIf { it.isNotBlank() }?.let { str(S.episode) to it },
        claim.codedDescription?.takeIf { it.isNotBlank() }?.let { str(S.desktop_pc_coding_description) to it },
    )
    if (cells.isEmpty()) return
    CrewRule()
    cells.chunked(GRID_COLUMNS).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth()) {
            row.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    CrewHeading(label)
                    ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
                }
            }
            repeat(GRID_COLUMNS - row.size) { Column(modifier = Modifier.weight(1f)) {} }
        }
    }
    CrewRule()
}

@Composable
private fun RejectionOrQuery(batch: ClaimBatch) {
    val colors = ZillitTheme.colors
    val people = LocalCashPeople.current
    if (batch.status == BatchStatus.Rejected && !batch.rejectionReason.isNullOrBlank()) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.dangerSoft)
                .padding(ZillitTheme.spacing.md),
        ) {
            ZillitText(text = batch.rejectionReason, style = ZillitTheme.typography.bodySmall, color = colors.danger)
            batch.rejectedBy?.takeIf { it.isNotBlank() }?.let { by ->
                ZillitText(
                    text = listOfNotNull(
                        str(S.desktop_pc_rejected_by, people.nameOf(by)),
                        batch.rejectedAt?.let(::date),
                    ).joinToString(" · "),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.danger,
                )
            }
        }
    }
    if (batch.status == BatchStatus.Queried && !batch.queryReason.isNullOrBlank()) {
        ZillitText(
            text = batch.queryReason,
            style = ZillitTheme.typography.bodySmall,
            color = colors.warning,
            modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.warningSoft)
                .padding(ZillitTheme.spacing.md),
        )
    }
}

/** The stored receipt: its name and kind, and a View that opens it. */
@Composable
private fun ReceiptFile(claim: Claim, onEvent: (CashEvent) -> Unit) {
    val key = claim.receiptKey ?: return
    val colors = ZillitTheme.colors
    val name = claim.attachment?.name?.takeIf { it.isNotBlank() } ?: key.substringAfterLast('/')
    val kind = when {
        claim.receiptIsPdf -> str(S.desktop_pdf_document)
        claim.attachment?.contentSubtype?.isNotBlank() == true ->
            str(S.desktop_pc_image_kind, claim.attachment.contentSubtype.uppercase())
        else -> str(S.file)
    }
    CrewHeading(str(S.desktop_receipt))
    Row(
        modifier = Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
            .border(CREW_HAIRLINE, colors.border, ZillitTheme.shapes.medium).padding(ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = colors.info, size = FILE_ICON)
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = name, style = ZillitTheme.typography.label, maxLines = 1)
            ZillitText(text = kind, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        ZillitButton(
            text = str(S.view),
            onClick = { onEvent(CashEvent.ViewReceipt(key)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Eye,
        )
    }
}

/** Line Items — the consolidated tax line is a posting artefact and stays out. */
@Composable
private fun LineItems(claim: Claim, money: (Double?) -> String) {
    val lines = claim.lineItems.filterNot { it.isTax }
    if (lines.isEmpty()) return
    val colors = ZillitTheme.colors
    CrewHeading(str(S.ah_line_items))
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(str(S.description), str(S.desktop_pc_cost_code), str(S.price), str(S.amount))
            .forEachIndexed { index, header ->
                ZillitText(
                    text = header.uppercase(),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(WEIGHTS[index]),
                )
            }
    }
    lines.forEach { line ->
        CrewRule()
        Row(modifier = Modifier.fillMaxWidth()) {
            ZillitText(text = line.description.ifBlank { "—" }, style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.weight(WEIGHTS[0]))
            ZillitText(text = line.account?.takeIf { it.isNotBlank() } ?: "—", style = ZillitTheme.typography.numeric,
                textAlign = TextAlign.End, modifier = Modifier.weight(WEIGHTS[1]))
            ZillitText(text = money(line.unitPrice), style = ZillitTheme.typography.numeric,
                textAlign = TextAlign.End, modifier = Modifier.weight(WEIGHTS[2]))
            ZillitText(text = money(line.total), style = ZillitTheme.typography.numeric,
                textAlign = TextAlign.End, modifier = Modifier.weight(WEIGHTS[3]))
        }
    }
}

/** `20`, `12.5` — a rate as a person writes it. */
private fun formatRate(rate: Double?): String {
    val value = rate ?: 0.0
    return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

private val DIALOG_WIDTH = 640.dp
private val FILE_ICON = 20.dp
private const val GRID_COLUMNS = 3
private val WEIGHTS = listOf(0.35f, 0.2f, 0.2f, 0.25f)
