package com.zillit.desktop.feature.assetreport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.assetreport.domain.AssetCategory
import com.zillit.desktop.feature.assetreport.domain.AssetLine
import com.zillit.desktop.feature.assetreport.domain.ExpenditureType
import com.zillit.desktop.feature.assetreport.domain.assetTotal
import com.zillit.desktop.feature.assetreport.domain.moneyLabel

/**
 * The Asset Register: one flat table of PO lines, a detail overlay per row —
 * the web's `AssetReportModule`, columns and all.
 */
@Composable
fun AssetScreen(
    state: AssetUiState,
    onEvent: (AssetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when {
            state.viewer.isBlocked -> Centred("You don't have access to the Asset Register.")
            state.detail != null -> DetailPage(state.detail, state, onEvent)
            else -> TablePage(state, onEvent)
        }
    }
}

@Composable
private fun TablePage(state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        TableHeading(state, onEvent)

        FilterBar(state, onEvent)

        HeaderRow()

        when {
            state.isLoading && state.lines.isEmpty() -> Centred("Loading assets…")
            state.visible.isEmpty() -> Centred("No assets match your filters.")
            else -> {
                val listState = rememberLazyListState()
                ZillitLazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.visible, key = AssetLine::lineItemId) { line ->
                        LineRow(line, state) { onEvent(AssetEvent.Open(line.lineItemId)) }
                    }
                }
            }
        }
    }
}

/**
 * The detail's top line: back, what this asset is, and Save when the category
 * has been changed and there is somebody allowed to save it.
 */
@Composable
private fun DetailHeading(
    detail: AssetDetail,
    state: AssetUiState,
    onEvent: (AssetEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val line = detail.line

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIconButton(
            icon = ZillitIcons.ArrowLeft,
            contentDescription = "Back to the register",
            onClick = { onEvent(AssetEvent.CloseDetail) },
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = line.description.ifBlank { "—" },
                style = ZillitTheme.typography.titleLarge,
                maxLines = 2,
            )
            ZillitText(
                text = listOf(line.account, line.poNumber).filter { it.isNotBlank() }.joinToString(" · "),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
        if (detail.categoryDirty && !detail.isHydrating && state.viewer.mayEdit) {
            ZillitButton(
                text = if (detail.isSaving) "Saving…" else "Save",
                enabled = !detail.isSaving,
                onClick = { onEvent(AssetEvent.SaveCategory) },
            )
        }
    }
}

/** Title, the count and running total, and the export buttons. */
@Composable
private fun TableHeading(state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = "Asset Register", style = ZillitTheme.typography.titleLarge)
            val total = assetTotal(state.visible)
            ZillitText(
                text = buildString {
                    append(state.visible.size)
                    append(if (state.visible.size == 1) " asset" else " assets")
                    total?.let { (sum, currency) ->
                        append(" · ")
                        if (currency.isNotBlank()) append("$currency ")
                        append(moneyLabel(sum))
                    }
                },
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        if (state.viewer.mayExport) {
            ZillitButton(
                text = if (state.isExporting) "Exporting…" else "Export PDF",
                variant = ButtonVariant.Secondary,
                enabled = !state.isExporting,
                onClick = { onEvent(AssetEvent.Export("pdf")) },
            )
            ZillitButton(
                text = "Export Excel",
                variant = ButtonVariant.Secondary,
                enabled = !state.isExporting,
                onClick = { onEvent(AssetEvent.Export("xlsx")) },
            )
        }
    }
}

/** The category chips and the search box that narrow the register. */
@Composable
private fun FilterBar(state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        CategoryFilter.entries.forEach { filter ->
            ZillitChoiceChip(
                label = filter.label,
                selected = state.categoryFilter == filter,
                onClick = { onEvent(AssetEvent.Filter(filter)) },
            )
        }
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(AssetEvent.Search(it)) },
            placeholder = "Search assets, vendors, refs…",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
    }
}

@Composable
private fun HeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        HeaderCell("Code", CODE_SHARE)
        HeaderCell("Asset", ASSET_SHARE)
        HeaderCell("Vendor", VENDOR_SHARE)
        HeaderCell("Department", DEPARTMENT_SHARE)
        HeaderCell("Ref", REF_SHARE)
        HeaderCell("Exp. Type", EXP_SHARE)
        HeaderCell("Qty", QTY_SHARE, end = true)
        HeaderCell("Unit Cost", UNIT_SHARE, end = true)
        HeaderCell("Total", TOTAL_SHARE, end = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    share: Float,
    end: Boolean = false,
) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = Modifier.weight(share),
    )
}

@Composable
private fun LineRow(line: AssetLine, state: AssetUiState, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        BodyCell(line.account.ifBlank { "—" }, CODE_SHARE)
        Row(
            modifier = Modifier.weight(ASSET_SHARE),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = line.description.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (line.category != AssetCategory.None) {
                ZillitTag(line.category.wire, tone = TagTone.Accent)
            }
        }
        BodyCell(state.vendorName(line.vendorId), VENDOR_SHARE)
        BodyCell(state.departmentName(line.departmentId), DEPARTMENT_SHARE)
        BodyCell(line.poNumber.ifBlank { "—" }, REF_SHARE)
        BodyCell(line.expenditureType.label.ifBlank { "—" }, EXP_SHARE)
        BodyCell(trimQty(line.quantity), QTY_SHARE, end = true)
        BodyCell(moneyLabel(line.unitPrice), UNIT_SHARE, end = true)
        BodyCell(moneyLabel(line.total), TOTAL_SHARE, end = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BodyCell(
    text: String,
    share: Float,
    end: Boolean = false,
) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        maxLines = 1,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        modifier = Modifier.weight(share),
    )
}

/** `3.0` reads as `3`; fractional quantities keep their fraction. */
private fun trimQty(quantity: Double): String =
    if (quantity == quantity.toLong().toDouble()) quantity.toLong().toString() else quantity.toString()

@Composable
@Suppress("LongMethod") // One overlay, one function — the web's detail, block for block.
private fun DetailPage(detail: AssetDetail, state: AssetUiState, onEvent: (AssetEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val line = detail.line

    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        DetailHeading(detail, state, onEvent)

        if (detail.isHydrating) {
            ZillitText(
                text = "Loading the register record…",
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
        }

        SectionLabel("DETAILS")
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            DetailCell("Vendor", state.vendorName(line.vendorId))
            DetailCell("Department", state.departmentName(line.departmentId))
            DetailCell("Expense Type", line.expenditureType.label.ifBlank { "—" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            DetailCell("Qty", trimQty(line.quantity))
            DetailCell("Unit Cost", "${line.currency} ${moneyLabel(line.unitPrice)}".trim())
            DetailCell("Total", "${line.currency} ${moneyLabel(line.total)}".trim())
        }

        SectionLabel("CATEGORY")
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            CategoryCard(
                title = "Keep",
                subtitle = "Retain in inventory",
                selected = detail.categoryDraft == AssetCategory.Keep,
                enabled = !detail.isHydrating && state.viewer.mayEdit,
                onClick = { onEvent(AssetEvent.PickCategory(AssetCategory.Keep)) },
            )
            CategoryCard(
                title = "Sell",
                subtitle = "List on wrap sale",
                selected = detail.categoryDraft == AssetCategory.Sell,
                enabled = !detail.isHydrating && state.viewer.mayEdit,
                onClick = { onEvent(AssetEvent.PickCategory(AssetCategory.Sell)) },
            )
        }

        SectionLabel("COMMENTS")
        ZillitTextField(
            value = detail.noteDraft,
            onValueChange = { onEvent(AssetEvent.NoteChanged(it)) },
            placeholder = "Add a note about this asset…",
            singleLine = false,
            enabled = !detail.isHydrating && state.viewer.mayEdit,
            modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_MIN_HEIGHT),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = when {
                    detail.isHydrating -> "Loading…"
                    detail.noteDirty -> "Unsaved changes"
                    detail.record?.comments.orEmpty().isBlank() -> "Not saved yet"
                    else -> "Saved"
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (detail.noteDirty && !detail.isHydrating && state.viewer.mayEdit) {
                ZillitButton(
                    text = if (detail.isSaving) "Saving…" else "Save note",
                    size = ButtonSize.Small,
                    enabled = !detail.isSaving,
                    onClick = { onEvent(AssetEvent.SaveNote) },
                )
            }
        }

        detail.record?.attachmentNames?.takeIf { it.isNotEmpty() }?.let { names ->
            SectionLabel("ATTACHMENTS")
            ZillitText(
                text = names.joinToString(" · "),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
    )
}

@Composable
private fun DetailCell(label: String, value: String) {
    Column {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .width(CATEGORY_CARD_WIDTH)
            .clip(ZillitTheme.shapes.medium)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = ZillitTheme.shapes.medium,
            )
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleSmall,
            color = if (selected) colors.accentText else colors.textPrimary,
        )
        ZillitText(
            text = subtitle,
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun Centred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

// The web's grid ratios, as weight shares of the row.
private const val CODE_SHARE = 0.07f
private const val ASSET_SHARE = 0.22f
private const val VENDOR_SHARE = 0.12f
private const val DEPARTMENT_SHARE = 0.12f
private const val REF_SHARE = 0.09f
private const val EXP_SHARE = 0.12f
private const val QTY_SHARE = 0.06f
private const val UNIT_SHARE = 0.10f
private const val TOTAL_SHARE = 0.10f

private val SEARCH_WIDTH = 280.dp
private val NOTE_MIN_HEIGHT = 104.dp
private val CATEGORY_CARD_WIDTH = 220.dp
