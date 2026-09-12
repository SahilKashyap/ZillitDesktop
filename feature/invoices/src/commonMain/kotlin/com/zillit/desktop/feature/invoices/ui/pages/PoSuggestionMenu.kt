package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.invoices.domain.PoSuggestion
import com.zillit.desktop.feature.invoices.ui.InvoicesEvent
import com.zillit.desktop.feature.invoices.ui.PoPicker

/**
 * The orders an unmatched invoice could belong to — the web's
 * `POSuggestionDropdown`.
 *
 * Two groups, as the server answers them: orders raised against the same
 * vendor, and orders the reader raised themselves. Picking one links it.
 *
 * A `Popup` rather than a `DropdownMenu`: a menu inside a scrolling table row
 * is measured against an unbounded height, and the list inside it has to be a
 * plain column for the same reason.
 */
@Composable
internal fun PoSuggestionMenu(picker: PoPicker, projectCurrency: String, onEvent: (InvoicesEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Popup(
        offset = IntOffset(0, MENU_DROP),
        onDismissRequest = { onEvent(InvoicesEvent.ClosePoSuggestions) },
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(MENU_WIDTH)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .padding(vertical = ZillitTheme.spacing.xs),
        ) {
            when {
                picker.loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(LOADING_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitSpinner()
                }

                picker.suggestions.isEmpty -> MenuNote("No matching purchase orders found.")

                else -> ZillitScrollColumn(modifier = Modifier.heightIn(max = MENU_HEIGHT)) {
                    if (picker.suggestions.vendorPos.isNotEmpty()) {
                        MenuHeading("This vendor's orders")
                        picker.suggestions.vendorPos.forEach {
                            SuggestionRow(it, picker, projectCurrency, onEvent)
                        }
                    }
                    if (picker.suggestions.userPos.isNotEmpty()) {
                        MenuHeading("Orders you raised")
                        picker.suggestions.userPos.forEach {
                            SuggestionRow(it, picker, projectCurrency, onEvent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: PoSuggestion,
    picker: PoPicker,
    projectCurrency: String,
    onEvent: (InvoicesEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val busy = picker.matching != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onEvent(InvoicesEvent.MatchToPo(suggestion)) }
            .background(if (picker.matching == suggestion.poId) colors.accentSoft else colors.surface)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = suggestion.label,
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(
                text = Money.format(suggestion.grossAmount, suggestion.currency.ifBlank { projectCurrency }),
                style = ZillitTheme.typography.numeric,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        if (suggestion.vendorName.isNotBlank()) {
            ZillitText(
                text = suggestion.vendorName,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.xs,
            bottom = ZillitTheme.spacing.xxs,
        ),
        maxLines = 1,
    )
}

@Composable
private fun MenuNote(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(ZillitTheme.spacing.md),
    )
}

private val MENU_WIDTH = 320.dp
private val MENU_HEIGHT = 320.dp
private val LOADING_HEIGHT = 80.dp
private const val MENU_DROP = 36
