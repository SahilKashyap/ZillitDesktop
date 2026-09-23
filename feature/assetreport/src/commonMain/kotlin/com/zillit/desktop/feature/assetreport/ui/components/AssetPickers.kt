package com.zillit.desktop.feature.assetreport.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The web's `RichSelect`, as the register's filter bar uses it: a 44dp field
 * that names one choice or counts several, over a panel with its own search,
 * a highlighted row per choice and a count in the footer.
 *
 * The list is lazy and given a *fixed* height from its row count: a lazy list
 * asked for its intrinsic height inside a popup throws.
 */
@Suppress("LongParameterList") // A picker's surface; each parameter is one knob a call site uses.
@Composable
internal fun <T> AssetSelect(
    options: List<T>,
    selectedKeys: List<String>,
    key: (T) -> String,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onClear: (() -> Unit)?,
    placeholder: String,
    modifier: Modifier = Modifier,
    multiple: Boolean = false,
    searchText: (T) -> String = label,
    rowHeight: Dp = ROW_HEIGHT,
    row: (@Composable (T) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val summary = when {
        selectedKeys.isEmpty() -> null
        !multiple || selectedKeys.size == 1 ->
            options.firstOrNull { key(it) == selectedKeys.first() }?.let(label) ?: if (multiple) "1 selected" else null
        else -> str(S.dd_n_selected, selectedKeys.size)
    }
    Box(modifier) {
        Trigger(
            text = summary,
            placeholder = placeholder,
            open = open,
            onClick = { open = !open },
            onClear = onClear?.takeIf { selectedKeys.isNotEmpty() },
        )
        if (open) {
            OptionsPanel(
                options = options,
                key = key,
                label = label,
                searchText = searchText,
                rowHeight = rowHeight,
                multiple = multiple,
                isSelected = { key(it) in selectedKeys },
                selectedCount = selectedKeys.size,
                onPick = { picked ->
                    onPick(picked)
                    // A multi-select stays open, so several can be picked in one visit.
                    if (!multiple) open = false
                },
                onDismiss = { open = false },
                row = row,
            )
        }
    }
}

/** The closed field: the choice or the placeholder, a clear button, the chevron. */
@Composable
private fun Trigger(
    text: String?,
    placeholder: String,
    open: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val turn by animateFloatAsState(if (open) HALF_TURN else 0f, label = "assetSelectChevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FIELD_HEIGHT)
            .clip(FIELD_SHAPE)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = when {
                    open -> colors.accent
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                shape = FIELD_SHAPE,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 15.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ZillitText(
            text = text ?: placeholder,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
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

// The panel: search, keys, list and footer read as one unit.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun <T> OptionsPanel(
    options: List<T>,
    key: (T) -> String,
    label: (T) -> String,
    searchText: (T) -> String,
    rowHeight: Dp,
    multiple: Boolean,
    isSelected: (T) -> Boolean,
    selectedCount: Int,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    row: (@Composable (T) -> Unit)?,
) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val shown = remember(options, query) {
        val needle = query.trim()
        if (needle.isEmpty()) options else options.filter { searchText(it).contains(needle, ignoreCase = true) }
    }
    var cursor by remember { mutableIntStateOf(shown.indexOfFirst(isSelected).coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val gap = with(LocalDensity.current) { PANEL_GAP.roundToPx() }

    LaunchedEffect(cursor, shown.size) { if (cursor in shown.indices) listState.animateScrollToItem(cursor) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Popup(
        popupPositionProvider = remember(gap) { BelowOrAbove(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(PANEL_WIDTH)
                .shadow(PANEL_ELEVATION, PANEL_SHAPE)
                .clip(PANEL_SHAPE)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, PANEL_SHAPE)
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
                },
        ) {
            SearchRow(
                value = query,
                onValueChange = {
                    query = it
                    cursor = 0
                },
                focus = focus,
            )
            if (shown.isEmpty()) {
                ZillitText(
                    text = if (options.isEmpty()) {
                        str(S.desktop_nothing_to_choose_from)
                    } else {
                        str(S.desktop_no_results_for, query.trim())
                    },
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 18.dp),
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
                            divided = index < shown.lastIndex,
                            height = rowHeight,
                            onHover = { cursor = index },
                            onClick = { onPick(item) },
                        ) {
                            if (row != null) {
                                row(item)
                            } else {
                                ZillitText(
                                    text = label(item),
                                    style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            Footer(count = shown.size, selected = if (multiple) selectedCount else 0)
        }
    }
}

@Suppress("LongParameterList") // A row's look is four independent flags.
@Composable
private fun OptionRow(
    selected: Boolean,
    highlighted: Boolean,
    divided: Boolean,
    height: Dp,
    onHover: () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Column(Modifier.fillMaxWidth().height(height)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    when {
                        selected -> colors.accentSoft
                        highlighted -> colors.surfaceSunken
                        else -> Color.Transparent
                    },
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The web's 3px accent bar down a picked row's left edge.
            Box(
                Modifier.width(3.dp).fillMaxHeight().background(if (selected) colors.accent else Color.Transparent),
            )
            Box(Modifier.weight(1f).padding(start = 11.dp, end = 8.dp)) { content() }
            Box(Modifier.padding(end = 14.dp).size(CHECK_DISC)) {
                if (selected) {
                    Box(
                        Modifier.size(CHECK_DISC).clip(CircleShape).background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(icon = ZillitIcons.Check, tint = colors.textOnAccent, size = CHECK_ICON)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(if (divided) colors.divider else Color.Transparent),
        )
    }
}

@Composable
private fun SearchRow(value: String, onValueChange: (String) -> Unit, focus: FocusRequester) {
    val colors = ZillitTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            ZillitIcon(icon = ZillitIcons.Search, tint = colors.textMuted, size = 15.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    ZillitText(
                        text = str(S.search),
                        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = ZillitTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (value.isNotEmpty()) {
                ZillitIcon(
                    icon = ZillitIcons.Close,
                    tint = colors.textMuted,
                    size = 11.dp,
                    modifier = Modifier.clip(CircleShape).clickable { onValueChange("") },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** "12 options · 2 selected", and the keys that drive the list. */
@Composable
private fun Footer(count: Int, selected: Int) {
    val colors = ZillitTheme.colors
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = buildString {
                    append(count)
                    append(if (count == 1) " option" else " options")
                    // A key cannot carry the leading separator: the catalogue trims a key's ends.
                    if (selected > 0) append(" · ").append(str(S.dd_n_selected, selected))
                },
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "↑↓ navigate  ↵ select",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textDisabled,
            )
        }
    }
}

/**
 * Below the field, or above it when the space below is short; slid left rather
 * than cut off at the window's edge — the web's `positionDrop`.
 */
private class BelowOrAbove(private val gap: Int) : PopupPositionProvider {
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

private fun maxRows(rowHeight: Dp): Int = (LIST_MAX / rowHeight).toInt().coerceAtLeast(1)

internal val FIELD_HEIGHT = 44.dp
internal val FIELD_SHAPE = RoundedCornerShape(12.dp)
private val PANEL_SHAPE = RoundedCornerShape(15.dp)
private val PANEL_WIDTH = 300.dp
private val PANEL_GAP = 8.dp
private val PANEL_ELEVATION = 18.dp
private val ROW_HEIGHT = 44.dp
private val LIST_MAX = 300.dp
private val CLEAR_SIZE = 18.dp
private val CLEAR_ICON = 9.dp
private val CHECK_DISC = 22.dp
private val CHECK_ICON = 12.dp
private const val HALF_TURN = 180f
