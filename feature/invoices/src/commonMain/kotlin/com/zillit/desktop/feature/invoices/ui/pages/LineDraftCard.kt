package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The line items of a credit note or a sales invoice — the web's
 * `LineItemsEditor` with its totals breakdown: the coding screen's rows,
 * Split and Add line on the left, Net / VAT / Gross on the right, and the
 * save's refusal under them. Lines the save flagged are tinted.
 */
@Suppress("LongMethod") // Header, rows, the Split / Add and totals footer, and the refusal.
@Composable
internal fun LineDraftCard(
    state: InvoicesUiState,
    draft: LineDraft,
    currency: String,
    frozen: Boolean,
    error: String?,
    onEdit: (LineEdit) -> Unit,
) {
    val calls = remember(onEdit) {
        LineCallbacks(
            select = { onEdit(LineEdit.Select(it)) },
            change = { onEdit(LineEdit.Change(it)) },
            splitAmount = { id, amount -> onEdit(LineEdit.SplitAmount(id, amount)) },
            remove = { onEdit(LineEdit.Remove(it)) },
        )
    }
    ZillitSectionCard(title = str(S.ah_line_items), icon = ZillitIcons.Ledger) {
        LineGridHeader()
        draft.lines.forEachIndexed { index, line ->
            LineItemRow(
                state = state,
                lines = draft.lines,
                line = line,
                index = index,
                selected = draft.selectedId == line.id,
                frozen = frozen,
                calls = calls,
                flagged = line.id in draft.flagged,
            )
        }
        val totals = draft.totals
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!frozen) {
                ZillitButton(
                    text = str(S.desktop_po_split_line),
                    onClick = { onEdit(LineEdit.Split) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    enabled = draft.canSplit,
                )
                ZillitButton(
                    text = str(S.desktop_po_add_line),
                    onClick = { onEdit(LineEdit.Add) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
            Spacer(Modifier.weight(1f))
            Total(str(S.desktop_net), InvoiceFormat.money(totals.net, currency))
            Total(str(S.ah_lbl_vat), InvoiceFormat.money(totals.tax, currency))
            Total(str(S.desktop_gross), InvoiceFormat.money(totals.gross, currency), strong = true)
        }
        error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
            )
        }
    }
}
