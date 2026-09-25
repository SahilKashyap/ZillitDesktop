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
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.LineDraft
import com.zillit.desktop.feature.invoices.domain.LineEdit
import com.zillit.desktop.feature.invoices.domain.TrackingSet
import com.zillit.desktop.feature.invoices.ui.InvoicesUiState

/**
 * The line items of a credit note or a sales invoice — the web's
 * `LineItemsEditor` with its totals breakdown: Description · Code · Layers ·
 * Amount · Tax (Credits and Sales pass `showTags={false}`), Split and Add
 * line on the left, Net / Tax / Gross on the right, and the save's refusal
 * under them.
 *
 * A picked child splits its parent again; a parent with splits shows the
 * read-only sum; the fields a save refused are outlined red (`errCls`), not
 * the whole row.
 */
@Suppress("LongMethod", "LongParameterList") // Header, rows, the Split / Add and totals footer, and the refusal.
@Composable
internal fun LineDraftCard(
    state: InvoicesUiState,
    draft: LineDraft,
    currency: String,
    frozen: Boolean,
    error: String?,
    /** The Layers picker's sets; the module's own list unless the caller has its own. */
    trackingSets: List<TrackingSet> = state.trackingSets,
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
    val columns = LineColumns(
        trackingSets = trackingSets,
        accounts = state.entryRefs.accounts,
        currency = currency,
    )
    // "+ Add line" sits in the panel's header, as the web's Line Items panel has it (`CreditsPage.jsx:650-657`).
    ZillitSectionCard(
        title = str(S.ah_line_items),
        icon = ZillitIcons.Ledger,
        action = if (frozen) {
            null
        } else {
            {
                ZillitButton(
                    text = str(S.desktop_po_add_line),
                    onClick = { onEdit(LineEdit.Add) },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        },
    ) {
        LineGridHeader(columns)
        draft.lines.forEach { line ->
            LineItemRow(
                state = state,
                lines = draft.lines,
                line = line,
                selected = draft.selectedId == line.id,
                frozen = frozen,
                calls = calls,
                columns = columns,
                errors = if (line.id in draft.flagged) flaggedFields(line) else emptySet(),
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
            }
            Spacer(Modifier.weight(1f))
            LineTotals(totals.net, totals.tax, totals.gross, currency)
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

/** Which fields of a line the save refused — the same rule as `LineItems.check`: an account, an amount above 0. */
private fun flaggedFields(line: CodedLine): Set<LineField> = buildSet {
    if (line.account.isBlank()) add(LineField.Account)
    if (line.amount <= 0.0) add(LineField.Amount)
}
