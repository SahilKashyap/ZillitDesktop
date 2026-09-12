package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The report filter bar's pickers — the web's `RichSelect` as its filter bars
 * use it.
 *
 * Compact on purpose: one 32dp line whatever is picked. The hub's form pickers
 * draw each choice as a chip and grow a row per wrap, which is right in a form
 * and wrong in a bar of ten filters, where one busy picker would push the
 * whole report down. So a multi-select says its one choice, or "3 selected",
 * as the web's does.
 *
 * The list is lazy — a production's vendors run to hundreds, each drawn as a
 * two-line card — and is given a *fixed* height from its row count, because a
 * lazy list asked for its intrinsic height inside a popup throws.
 */

/** A filter's label above its control — the web's 10.5px uppercase eyebrow. */
@Composable
fun FilterCell(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = LABEL_SIZE,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = LABEL_TRACKING,
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) { content() }
    }
}

/**
 * One choice, or none — "All vendors" is the empty state, and the ✕ returns to it.
 *
 * [searchText] is what the search matches, which can say more than the label:
 * the web finds a vendor by its contact and address as well as its name.
 * [row] draws a richer option than the label; its height is [rowHeight].
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob the call sites use.
@Composable
fun <T> FilterSelect(
    value: T?,
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    clearable: Boolean = true,
    searchable: Boolean = true,
    searchText: (T) -> String = label,
    popupWidth: Dp = POPUP_WIDTH,
    rowHeight: Dp = ROW_HEIGHT,
    row: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterTrigger(
            text = value?.let(label),
            placeholder = placeholder,
            open = open,
            onClick = { open = !open },
            onClear = if (clearable && value != null) ({ onSelect(null) }) else null,
        )
        if (open) {
            OptionsPopup(
                options = options,
                key = key,
                label = label,
                searchText = searchText,
                searchable = searchable,
                width = popupWidth,
                rowHeight = rowHeight,
                isSelected = { value != null && key(it) == key(value) },
                onPick = { picked ->
                    onSelect(picked)
                    open = false
                },
                onDismiss = { open = false },
                row = row,
                footer = null,
            )
        }
    }
}

/**
 * Several choices, held as keys — empty means all.
 *
 * Keys rather than items so a choice that has left the list — a tag removed
 * from the production's settings — still counts, still shows, and can still
 * be cleared, instead of silently narrowing the report from nowhere.
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob the call sites use.
@Composable
fun <T> FilterMultiSelect(
    selected: List<String>,
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select…",
    searchable: Boolean = true,
    popupWidth: Dp = POPUP_WIDTH,
) {
    var open by remember { mutableStateOf(false) }
    val summary = when (selected.size) {
        0 -> null
        1 -> options.firstOrNull { key(it) == selected.first() }?.let(label) ?: selected.first()
        else -> "${selected.size} selected"
    }
    Box(modifier) {
        FilterTrigger(
            text = summary,
            placeholder = placeholder,
            open = open,
            onClick = { open = !open },
            onClear = if (selected.isNotEmpty()) ({ onChange(emptyList()) }) else null,
        )
        if (open) {
            OptionsPopup(
                options = options,
                key = key,
                label = label,
                searchText = label,
                searchable = searchable,
                width = popupWidth,
                rowHeight = ROW_HEIGHT,
                isSelected = { key(it) in selected },
                // The list stays open, so several can be picked in one visit.
                onPick = { picked ->
                    val id = key(picked)
                    onChange(if (id in selected) selected - id else selected + id)
                },
                onDismiss = { open = false },
                row = null,
                checkboxes = true,
                footer = {
                    FooterBar(
                        text = if (selected.isEmpty()) "${options.size} options" else "${selected.size} selected",
                        action = if (selected.isEmpty()) null else "Clear" to { onChange(emptyList()) },
                    )
                },
            )
        }
    }
}

/** The one-line field every picker shows while closed. */
@Composable
private fun FilterTrigger(
    text: String?,
    placeholder: String,
    open: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val turn by animateFloatAsState(if (open) HALF_TURN else 0f, label = "filterChevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ZillitDimens.controlHeight)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = when {
                    open -> colors.focusRing
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                shape = ZillitTheme.shapes.medium,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = text ?: placeholder,
            style = ZillitTheme.typography.bodySmall.copy(
                fontWeight = if (text != null) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (text != null) colors.textPrimary else colors.textMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Always composed, faded when empty: a control that appears only once
        // something is picked would move the chevron under the pointer.
        Box(
            modifier = Modifier
                .size(CLEAR_SIZE)
                .clip(CircleShape)
                .background(if (onClear != null) colors.surfaceSunken else Color.Transparent)
                .then(if (onClear != null) Modifier.clickable(onClick = onClear) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (onClear != null) ZillitIcon(icon = ZillitIcons.Close, tint = colors.textMuted, size = CLEAR_ICON)
        }
        ZillitIcon(
            icon = ZillitIcons.ChevronDown,
            tint = colors.textMuted,
            size = ZillitDimens.iconSmall,
            modifier = Modifier.rotate(turn),
        )
    }
}

// The shared popup behind both pickers: search, keys, list and footer read as one unit.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun <T> OptionsPopup(
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    searchText: (T) -> String,
    searchable: Boolean,
    width: Dp,
    rowHeight: Dp,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    row: (@Composable (T) -> Unit)?,
    footer: (@Composable () -> Unit)?,
    checkboxes: Boolean = false,
) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    var cursor by remember { mutableIntStateOf(-1) }
    val shown = remember(options, query) {
        val needle = query.trim()
        if (needle.isEmpty()) options else options.filter { searchText(it).contains(needle, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val gap = with(LocalDensity.current) { POPUP_GAP.roundToPx() }
    val showSearch = searchable && options.size > SEARCH_THRESHOLD

    LaunchedEffect(cursor) { if (cursor >= 0) listState.animateScrollToItem(cursor) }
    LaunchedEffect(showSearch) { if (showSearch) runCatching { focus.requestFocus() } }

    Popup(
        popupPositionProvider = remember(gap) { DropdownPosition(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .shadow(POPUP_ELEVATION, ZillitTheme.shapes.large)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, ZillitTheme.shapes.large)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> onDismiss()
                        Key.DirectionDown -> cursor = (cursor + 1).coerceAtMost(shown.lastIndex)
                        Key.DirectionUp -> cursor = (cursor - 1).coerceAtLeast(0)
                        Key.Enter, Key.NumPadEnter -> shown.getOrNull(cursor)?.let(onPick)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .padding(ZillitTheme.spacing.xs),
        ) {
            if (showSearch) {
                SearchInput(
                    value = query,
                    onValueChange = { query = it; cursor = if (it.isBlank()) -1 else 0 },
                    focus = focus,
                )
            }
            if (shown.isEmpty()) {
                FieldHint(
                    text = if (options.isEmpty()) "Nothing to choose from" else "No results for “${query.trim()}”",
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.md),
                )
            } else {
                LazyColumn(
                    state = listState,
                    // Fixed, never `heightIn`: see the file's note.
                    modifier = Modifier.fillMaxWidth().height(rowHeight * shown.size.coerceAtMost(maxRows(rowHeight))),
                ) {
                    itemsIndexed(shown, key = { index, item -> "${key(item)}#$index" }) { index, item ->
                        OptionRow(
                            selected = isSelected(item),
                            highlighted = index == cursor,
                            height = rowHeight,
                            checkbox = checkboxes,
                            onClick = { onPick(item) },
                        ) {
                            if (row != null) row(item) else ZillitText(
                                text = label(item),
                                style = ZillitTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    highlighted: Boolean,
    height: Dp,
    checkbox: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    selected && !checkbox -> colors.accentSoft
                    hovered || highlighted -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        if (checkbox) CheckMark(selected)
        Box(Modifier.weight(1f)) { content() }
        if (selected && !checkbox) {
            ZillitIcon(icon = ZillitIcons.Check, tint = colors.accentText, size = ZillitDimens.iconSmall)
        }
    }
}

/** A 16dp square that fills with the accent when picked. */
@Composable
private fun CheckMark(checked: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(CHECK_BOX)
            .clip(ZillitTheme.shapes.small)
            .background(if (checked) colors.accent else colors.surface)
            .border(1.dp, if (checked) colors.accent else colors.borderStrong, ZillitTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = CHECK_ICON)
    }
}

@Composable
private fun SearchInput(value: String, onValueChange: (String) -> Unit, focus: FocusRequester) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = ZillitTheme.spacing.xs)
            .height(SEARCH_HEIGHT)
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.Search, tint = colors.textMuted, size = ZillitDimens.iconSmall)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                ZillitText(
                    text = "Search…",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = ZillitTheme.typography.bodySmall.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/** "4 selected · Clear", under a multi-select's list. */
@Composable
private fun FooterBar(text: String, action: Pair<String, () -> Unit>?) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZillitTheme.spacing.xs)
            .background(colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldHint(text, Modifier.weight(1f))
        if (action != null) {
            ZillitText(
                text = action.first,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentText,
                modifier = Modifier
                    .clip(ZillitTheme.shapes.small)
                    .clickable(onClick = action.second)
                    .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
            )
        }
    }
}

/**
 * Below the field, or above it when there is no room below; slid left rather
 * than cut off at the window's edge. The last filters in the bar sit at the
 * right-hand edge, and a 400dp vendor list anchored at their left would run
 * off the window.
 */
private class DropdownPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left
            .coerceAtMost(windowSize.width - popupContentSize.width)
            .coerceAtLeast(0)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = if (below + popupContentSize.height > windowSize.height && above >= 0) above else below
        return IntOffset(x, y)
    }
}

/** How many rows the list shows before it scrolls — about 280dp of them. */
private fun maxRows(rowHeight: Dp): Int = (LIST_MAX / rowHeight).toInt().coerceAtLeast(1)

private val POPUP_WIDTH = 260.dp
private val POPUP_GAP = 4.dp
private val POPUP_ELEVATION = 12.dp
private val ROW_HEIGHT = 32.dp
private val LIST_MAX = 288.dp
private val SEARCH_HEIGHT = 32.dp
private val CLEAR_SIZE = 18.dp
private val CLEAR_ICON = 10.dp
private val CHECK_BOX = 16.dp
private val CHECK_ICON = 11.dp
private val LABEL_SIZE = 10.5.sp
private val LABEL_TRACKING = 0.6.sp
private const val HALF_TURN = 180f

/** Short lists are scanned, not searched; the web's pickers show the field past a handful. */
private const val SEARCH_THRESHOLD = 6
