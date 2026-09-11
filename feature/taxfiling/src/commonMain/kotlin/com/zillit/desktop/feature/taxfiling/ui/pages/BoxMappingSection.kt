package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTokenField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.ui.ReturnState
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent

private val BOX_LABEL_WIDTH = 40.dp

/**
 * Which ledger entries feed each box.
 *
 * Boxes 3 and 5 are absent by design: they are arithmetic on the others, and
 * offering a mapping for them invites a return whose own boxes disagree.
 */
@Composable
fun ColumnScope.BoxMappingSection(state: ReturnState, onEvent: (TaxFilingEvent) -> Unit) {
    val unmapped = VatBox.mappable.count { !state.mappingFor(it).isConfigured }

    ZillitSectionCard(
        title = "Box mapping",
        icon = ZillitIcons.Filter,
        meta = if (unmapped == 0) "All seven mapped" else "$unmapped not mapped",
        action = {
            ZillitButton(
                text = "Save mapping",
                onClick = { onEvent(TaxFilingEvent.SaveMapping) },
                variant = ButtonVariant.Tertiary,
            )
        },
    ) {
        ZillitText(
            text = "The mapping belongs to the company, not the period — it is saved once and " +
                "every later return reads it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )

        VatBox.mappable.forEach { box ->
            BoxRow(box, state.mappingFor(box), onEvent)
        }
    }
}

@Composable
private fun ColumnScope.BoxRow(box: VatBox, mapping: BoxMapping, onEvent: (TaxFilingEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ZillitText(
                text = box.number.toString(),
                style = ZillitTheme.typography.label,
                modifier = Modifier.width(BOX_LABEL_WIDTH),
            )
            ZillitText(
                text = box.label,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ZillitStatusPill(
                label = box.direction,
                tone = StatusTone.Neutral,
            )
            ZillitStatusPill(
                label = if (mapping.isConfigured) "Mapped" else "Not mapped",
                tone = if (mapping.isConfigured) StatusTone.Done else StatusTone.Pending,
                dot = true,
            )
        }

        // Zeroing is the honest answer for a production with no EU trade, and
        // it is not the same as leaving the box unmapped: unmapped reads the
        // ledger and finds nothing, which looks identical and means something
        // else entirely.
        ZillitCheckbox(
            checked = mapping.markZero,
            onCheckedChange = { onEvent(TaxFilingEvent.EditMapping(mapping.copy(markZero = it))) },
            label = "File this box as zero without reading the ledger",
        )

        if (!mapping.markZero) {
            CodeFields(mapping, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.CodeFields(mapping: BoxMapping, onEvent: (TaxFilingEvent) -> Unit) {
    var codeInput by remember(mapping.box) { mutableStateOf("") }
    var tagInput by remember(mapping.box) { mutableStateOf("") }

    ZillitTokenField(
        tokens = mapping.codes,
        input = codeInput,
        onValueChange = { tokens, text ->
            codeInput = text
            if (tokens != mapping.codes) {
                onEvent(TaxFilingEvent.EditMapping(mapping.copy(codes = tokens)))
            }
        },
        placeholder = "Account codes, comma separated",
        modifier = Modifier.fillMaxWidth(),
    )
    ZillitTokenField(
        tokens = mapping.tags,
        input = tagInput,
        onValueChange = { tokens, text ->
            tagInput = text
            if (tokens != mapping.tags) {
                onEvent(TaxFilingEvent.EditMapping(mapping.copy(tags = tokens)))
            }
        },
        placeholder = "Tags, comma separated",
        modifier = Modifier.fillMaxWidth(),
    )
}
