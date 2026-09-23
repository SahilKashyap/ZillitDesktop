package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BulkAddState
import com.zillit.desktop.feature.accounthub.ui.components.CoaIcons
import com.zillit.desktop.feature.accounthub.ui.components.coaMono
import com.zillit.desktop.feature.accounthub.ui.components.coaRailTone

/**
 * "Add chart-of-accounts entries" — the web's `CoaBulkAddPage` and `CoaBulkGrid`.
 *
 * A full-page spreadsheet over the chart. Each row saves itself two seconds
 * after its last edit once it has a code, so there is no Save button: the top
 * bar says where the saves stand, and Done (or the back arrow) sends whatever is
 * still waiting before returning to the chart.
 */
@Composable
internal fun ChartBulkAddPage(state: AccountHubUiState, bulk: BulkAddState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val chart = state.chart.accounts
    val duplicates = remember(bulk.rows, bulk.createdCodes, chart) { bulk.duplicateIds(chart) }
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        BulkTopBar(bulk, onEvent)
        BulkIntro(bulk)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.surface)
                .drawBehind { drawLine(colors.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        ) {
            BulkToolbar(bulk.rows.size, onEvent)
            BulkHeader()
            ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                bulk.rows.forEachIndexed { index, row ->
                    BulkGridRow(
                        row = row,
                        number = index + 1,
                        duplicateMessage = if (row.localId in duplicates) {
                            CoaBulk.duplicateMessage(row, bulk.rows, chart, bulk.createdCodes)
                        } else {
                            null
                        },
                        status = bulk.status[row.localId],
                        error = bulk.errors[row.localId],
                        isLast = index == bulk.rows.lastIndex,
                        focus = bulk.focusRowId == row.localId,
                        onEvent = onEvent,
                    )
                }
                Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    BulkButton(str(S.desktop_add_entry), dashed = true) { onEvent(AccountHubEvent.AddBulkRows(1)) }
                }
            }
        }
    }
}

/** Back · "ACCOUNTING · CHART OF ACCOUNTS / Add chart-of-accounts entries" · save status · Done. */
@Composable
private fun BulkTopBar(bulk: BulkAddState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .drawBehind {
                drawLine(colors.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackButton(enabled = !bulk.finishing) { onEvent(AccountHubEvent.FinishBulkAdd) }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                str(S.desktop_hub_accounting_chart_of_accounts),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.88.sp),
                color = colors.accent,
                maxLines = 1,
            )
            ZillitText("/", style = ZillitTheme.typography.bodySmall, color = colors.borderStrong)
            ZillitText(
                str(S.desktop_hub_add_chart_of_accounts_entries),
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        if (bulk.saveLabel.isNotEmpty()) {
            ZillitText(
                bulk.saveLabel,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    bulk.anySaving -> colors.textMuted
                    bulk.anyError -> colors.danger
                    else -> SAVED_TEAL
                },
                maxLines = 1,
            )
        }
        ZillitButton(
            text = if (bulk.finishing) str(S.drive_uploads_status_posting) else str(S.ah_done),
            onClick = { onEvent(AccountHubEvent.FinishBulkAdd) },
            loading = bulk.finishing,
        )
    }
}

@Composable
private fun BackButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    ZillitTooltip(str(S.desktop_hub_back_to_chart_of_accounts)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clip(shape)
                .background(if (hovered && enabled) colors.surfaceHover else colors.surface)
                .border(1.dp, colors.border, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                CoaIcons.ChevronLeft,
                contentDescription = str(S.back),
                tint = colors.textSecondary,
                size = 13.dp,
            )
        }
    }
}

/** Where the entries go, and the rule every row follows. */
@Composable
private fun BulkIntro(bulk: BulkAddState) {
    val colors = ZillitTheme.colors
    val strong = SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    val text = buildAnnotatedString {
        val parent = bulk.parent
        if (parent != null) {
            append("Adding entries under ")
            withStyle(strong) { append(parent.label("·")) }
            append(" — ")
        } else {
            append("Adding top-level entries — ")
        }
        append("each row saves itself automatically once it has a code, no Save button needed. ")
        withStyle(strong) { append(str(S.desktop_cost_type)) }
        append(
            " sets the accounting class: Asset (Cash, Bank), Liability (Loans), Capital (Equity), " +
                "Income (Revenue, Tax Credits), Expense (Costs).",
        )
    }
    ZillitText(
        text,
        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 12.dp).widthIn(max = 900.dp),
    )
}

@Composable
private fun BulkToolbar(count: Int, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(start = 20.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ZillitText(
            str(S.desktop_entries),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.surfaceSunken)
                .border(1.dp, colors.divider, RoundedCornerShape(50))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            ZillitText(count.toString(), style = coaMono(10.5.sp, FontWeight.Bold), color = colors.textMuted)
        }
        Box(Modifier.weight(1f))
        BulkButton(str(S.desktop_add_entry), dashed = true) { onEvent(AccountHubEvent.AddBulkRows(1)) }
        BulkButton("Add ${CoaBulk.BATCH_ROWS} rows") { onEvent(AccountHubEvent.AddBulkRows(CoaBulk.BATCH_ROWS)) }
    }
}

/** The grid's bordered button; the dashed one is "add" (`.coa-btn.add`). */
@Composable
private fun BulkButton(label: String, dashed: Boolean = false, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val content = when {
        hovered && dashed -> colors.accent
        hovered -> colors.textPrimary
        else -> colors.textSecondary
    }
    val edge = when {
        hovered && dashed -> colors.accent
        hovered -> colors.borderStrong
        else -> colors.border
    }
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .drawBehind {
                val radius = CornerRadius(8.dp.toPx())
                val stroke = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = if (dashed) {
                        PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                    } else {
                        null
                    },
                )
                drawRoundRect(edge, cornerRadius = radius, style = stroke)
            }
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(CoaIcons.Plus, tint = content, size = 11.dp)
        ZillitText(
            label,
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun BulkHeader() {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(colors.surfaceSunken)
            .drawBehind {
                drawLine(colors.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(start = 18.dp, end = 12.dp),
    ) {
        GridCell(RAIL_COLUMN, first = true) { }
        GridCell(TYPE_COLUMN) { HeadText(str(S.desktop_line_type), required = true) }
        GridCell(CODE_COLUMN) { HeadText(str(S.code), required = true) }
        GridCell(COST_COLUMN) { HeadText(str(S.desktop_cost_type), required = true) }
        GridCell(null) { HeadText(str(S.av_display_name)) }
        GridCell(ACTIVE_COLUMN, center = true) { HeadText(str(S.active), padded = false) }
        GridCell(POSTING_COLUMN, center = true) { HeadText(str(S.txt_posting), padded = false) }
        GridCell(REMOVE_COLUMN) { }
    }
}

@Composable
private fun HeadText(text: String, required: Boolean = false, padded: Boolean = true) {
    val colors = ZillitTheme.colors
    Row(Modifier.padding(start = if (padded) 10.dp else 0.dp)) {
        ZillitText(
            text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
            color = colors.textMuted,
            maxLines = 1,
        )
        if (required) {
            ZillitText(
                "*",
                style = ZillitTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                color = colors.danger,
            )
        }
    }
}

/** A grid cell: a fixed width (or the rest of the row), ruled from its left neighbour. */
@Composable
private fun RowScope.GridCell(
    width: Dp?,
    first: Boolean = false,
    center: Boolean = false,
    content: @Composable () -> Unit,
) {
    val divider = ZillitTheme.colors.divider
    val sized = if (width == null) Modifier.weight(1f).widthIn(min = NAME_MIN) else Modifier.width(width)
    Box(
        modifier = sized
            .fillMaxHeight()
            .drawBehind { if (!first) drawLine(divider, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) },
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
    ) { content() }
}

/** One entry (`GridRow`): class rail, number, level, code, class, name, the two flags, remove. */
@Suppress("LongMethod") // A row, read left to right; the order is the reading order.
@Composable
private fun BulkGridRow(
    row: CoaBulkRow,
    number: Int,
    duplicateMessage: String?,
    status: CoaBulkStatus?,
    error: String?,
    isLast: Boolean,
    focus: Boolean,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val tone = coaRailTone(row.costType)
    val codeFocus = remember(row.localId) { FocusRequester() }
    LaunchedEffect(focus) {
        // Tab off the last row's name lands here once the new row exists.
        if (focus) runCatching { codeFocus.requestFocus() }
    }
    fun edit(next: CoaBulkRow) = onEvent(AccountHubEvent.EditBulkRow(next))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .alpha(if (row.isActive) 1f else INACTIVE_ROW_ALPHA)
            .background(colors.surface)
            .drawBehind {
                drawRect(tone.content, size = size.copy(width = 4.dp.toPx()))
                drawLine(colors.divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(start = 18.dp, end = 12.dp),
    ) {
        GridCell(RAIL_COLUMN, first = true, center = true) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(tone.background),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText(number.toString(), style = coaMono(10.sp, FontWeight.Bold), color = tone.content)
            }
        }
        GridCell(TYPE_COLUMN) {
            // Editable for the row's whole life: a saved row re-types with a plain update.
            GridSelect(row.lineType, CoaLineType.entries, { it.tagLabel }) { edit(row.copy(lineType = it)) }
        }
        GridCell(CODE_COLUMN) {
            GridInput(
                value = row.code,
                onValueChange = { edit(row.copy(code = it.uppercase())) },
                placeholder = "1100",
                mono = true,
                error = duplicateMessage != null || status == CoaBulkStatus.Error,
                // The grid has no room for inline text, so the reason is the tooltip.
                tooltip = duplicateMessage ?: error.takeIf { status == CoaBulkStatus.Error }.orEmpty(),
                focusRequester = codeFocus,
            )
        }
        GridCell(COST_COLUMN) {
            GridSelect(row.costType, CoaCostType.entries, { it.label }) { edit(row.copy(costType = it)) }
        }
        GridCell(null) {
            GridInput(
                value = row.name,
                onValueChange = { edit(row.copy(name = it)) },
                placeholder = str(S.dd_publish_display_name_placeholder),
                // Tab off the last row's name extends the grid, spreadsheet-style;
                // Shift+Tab still walks backwards.
                onTab = if (isLast) ({ onEvent(AccountHubEvent.AddBulkRows(1, focus = true)) }) else null,
            )
        }
        GridCell(ACTIVE_COLUMN, center = true) { GridCheck(row.isActive) { edit(row.copy(isActive = it)) } }
        GridCell(POSTING_COLUMN, center = true) { GridCheck(row.isPosting) { edit(row.copy(isPosting = it)) } }
        GridCell(REMOVE_COLUMN, center = true) {
            GridRemove { onEvent(AccountHubEvent.RemoveBulkRow(row.localId)) }
        }
    }
}

/** A borderless cell input that tints on hover and rings on focus (`.coa-input`). */
@Composable
private fun GridInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    error: Boolean = false,
    tooltip: String = "",
    focusRequester: FocusRequester? = null,
    onTab: (() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    val ring by animateColorAsState(
        when {
            error -> colors.danger
            focused -> colors.accent
            else -> Color.Transparent
        },
        label = "gridInputRing",
    )
    val style = gridInputStyle(mono)
    ZillitTooltip(tooltip) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .height(34.dp)
                .clip(shape)
                .background(
                    when {
                        focused -> colors.surface
                        hovered -> colors.surfaceHover
                        else -> Color.Transparent
                    },
                )
                .border(2.dp, ring, shape)
                .hoverable(interaction)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) ZillitText(placeholder, style = style, color = colors.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = style.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .tabOut(onTab),
            )
        }
    }
}

@Composable
private fun gridInputStyle(mono: Boolean) = if (mono) {
    coaMono(12.5.sp, FontWeight.Medium, 0.2.sp)
} else {
    ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
}

/** Tab (not Shift+Tab) runs [onTab] instead of moving focus on; null leaves Tab alone. */
private fun Modifier.tabOut(onTab: (() -> Unit)?): Modifier = if (onTab == null) {
    this
} else {
    onPreviewKeyEvent { event ->
        val tab = event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isShiftPressed
        if (tab) onTab()
        tab
    }
}

/** A borderless cell select with a chevron (`.coa-select`). */
@Composable
private fun <T> GridSelect(value: T, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(5.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(shape)
                .background(if (hovered || open) colors.surfaceHover else Color.Transparent)
                .border(2.dp, if (open) colors.accent else Color.Transparent, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(start = 10.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                label(value),
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(CoaIcons.ChevronDown, tint = colors.textMuted, size = 11.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(8.dp)),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    modifier = Modifier.background(
                        if (option == value) colors.surfaceSelected else colors.surfaceRaised,
                    ),
                    text = {
                        ZillitText(
                            label(option),
                            style = ZillitTheme.typography.bodyMedium,
                            color = if (option == value) colors.accentText else colors.textPrimary,
                        )
                    },
                )
            }
        }
    }
}

/** The grid's square tick box (`.coa-check`). */
@Composable
private fun GridCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val fill by animateColorAsState(if (checked) colors.accent else colors.surface, label = "gridCheck")
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(fill)
            .border(1.5.dp, if (checked) colors.accent else colors.borderStrong, shape)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) ZillitIcon(CoaIcons.Check, tint = colors.textOnAccent, size = 12.dp)
    }
}

/** Remove entry — muted until hovered, then red (`.coa-icon.danger`). */
@Composable
private fun GridRemove(onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(7.dp)
    ZillitTooltip(str(S.desktop_remove_entry)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(shape)
                .background(if (hovered) colors.danger.copy(alpha = 0.10f) else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                CoaIcons.Trash,
                contentDescription = str(S.desktop_remove_entry),
                tint = if (hovered) colors.danger else colors.textMuted,
                size = 13.dp,
            )
        }
    }
}

private val ROW_HEIGHT = 37.dp
private val RAIL_COLUMN = 28.dp
private val TYPE_COLUMN = 160.dp
private val CODE_COLUMN = 132.dp
private val COST_COLUMN = 160.dp
private val NAME_MIN = 220.dp
private val ACTIVE_COLUMN = 76.dp
private val POSTING_COLUMN = 84.dp
private val REMOVE_COLUMN = 44.dp
private val SAVED_TEAL = Color(0xFF14A394)
private const val INACTIVE_ROW_ALPHA = 0.55f
private const val DISABLED_ALPHA = 0.5f
