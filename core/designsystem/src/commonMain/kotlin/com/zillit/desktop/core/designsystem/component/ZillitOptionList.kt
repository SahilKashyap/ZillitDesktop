package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
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
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/*
 * The one look every select in the app shares — the web's `RichSelect`:
 *
 *  - the closed field ([ZillitSelectTrigger]) goes accent-rimmed, with a soft
 *    glow, while its list is open, and its chevron turns up;
 *  - the list ([ZillitOptionPopup]) floats under the field as a detached
 *    card the field's width: a search line, then rows each led by a tile of
 *    the option's initials, then a footer counting the options and showing
 *    the keys that drive it.
 *
 * [ZillitSelect], [ZillitSearchSelect] and [ZillitMultiSelect] are thin
 * shells over these two, so a change of style lands everywhere at once. A
 * picker whose trigger is not a form field — a grid cell, a pill, a button —
 * keeps its own trigger and opens [ZillitOptionPopup] from it, so its list
 * still looks like every other.
 */

/** A row pinned above the options, always shown — "+ New actor", "Add crew…". */
data class ZillitOptionAction(val label: String, val onClick: () -> Unit)

/**
 * The closed field of a select: [content] laid out in the field's padding,
 * then the clear ✕ when [onClear] is set, then the chevron. Open, the rim
 * turns accent and a soft ring is drawn just outside it — outside, so
 * opening a select never moves a pixel of the layout around it.
 */
@Suppress("LongParameterList")
@Composable
fun ZillitSelectTrigger(
    open: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    minHeight: Dp = TRIGGER_HEIGHT,
    contentPadding: Dp = ZillitTheme.spacing.md,
    onClear: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val shape = ZillitTheme.shapes.medium
    val glow = colors.focusRing.copy(alpha = GLOW_ALPHA)
    val rim = when {
        isError -> colors.danger
        open -> colors.focusRing
        else -> colors.border
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .drawBehind {
                if (open) {
                    val g = GLOW_WIDTH.toPx()
                    drawRoundRect(
                        color = glow,
                        topLeft = Offset(-g, -g),
                        size = Size(size.width + 2 * g, size.height + 2 * g),
                        cornerRadius = CornerRadius(TRIGGER_RADIUS.toPx() + g),
                    )
                }
            }
            .clip(shape)
            .background(if (enabled) colors.surface else colors.surfaceHover)
            .border(HAIRLINE, rim, shape)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        content()
        if (onClear != null && enabled) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.ah_clear_selection),
                onClick = onClear,
                size = CLEAR_BUTTON,
                tint = colors.textMuted,
            )
        }
        ZillitIcon(
            icon = if (open) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
            tint = if (enabled) colors.textMuted else colors.textDisabled,
            size = CHEVRON,
        )
    }
}

/**
 * The open list of a select, anchored under the composable that holds it
 * (flipped above when the window has no room below) — so call it from inside
 * the same `Box` as the trigger, and only while open.
 *
 * [onPick] runs for a click or ↵ on a row; a single select closes itself
 * there, a multi-select toggles and stays open. [isSelected] marks a row
 * picked: accent wash, accent bar, a tick at the end. [footer] words the
 * count strip from the number of rows the search leaves.
 *
 * Rows: [optionLeading] replaces the initials tile (an avatar, a colour dot);
 * [showInitials] off drops the tile; [renderOption] replaces the whole row's
 * content. [isEnabled] false greys a row out and the keys skip it.
 * [section] names each option's group: a header is drawn wherever it changes
 * from the row above, so pass the options already grouped. [pinnedAction]
 * sits above every row; [header] above the search line; [onCreate] offers
 * `Create "<typed>"` when a search matches nothing. [searchPlaceholder]
 * words the empty search line ("Search…" by default); every word typed must
 * appear in an option's [searchText], in any order. [keepOnSearch] rows — an
 * "All", a "Device timezone" — stay listed whatever is typed.
 *
 * Keys: ↑/↓ move the highlight (the pointer moves it too, as on the web),
 * ↵ picks it — or the create row, when that is all there is — and Esc
 * closes. [searchable] decides whether the search line is drawn; the keys
 * work either way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun <T> ZillitOptionPopup(
    onDismiss: () -> Unit,
    options: List<T>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    label: (T) -> String,
    width: Dp = POPUP_MIN_WIDTH,
    searchable: Boolean = options.size > SEARCH_THRESHOLD,
    footer: @Composable (shown: Int) -> String = { zillitOptionCount(it) },
    searchText: (T) -> String = label,
    subtitle: ((T) -> String?)? = null,
    showInitials: Boolean = true,
    optionLeading: (@Composable (T) -> Unit)? = null,
    renderOption: (@Composable (T, Boolean) -> Unit)? = null,
    isEnabled: (T) -> Boolean = { true },
    section: ((T) -> String?)? = null,
    pinnedAction: ZillitOptionAction? = null,
    header: (@Composable () -> Unit)? = null,
    onCreate: ((String) -> Unit)? = null,
    emptyText: String = str(S.desktop_nothing_to_choose_from),
    searchPlaceholder: String? = null,
    keepOnSearch: (T) -> Boolean = { false },
) {
    val colors = ZillitTheme.colors
    val density = LocalDensity.current
    var query by remember { mutableStateOf("") }
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val filtered = if (words.isEmpty()) {
        options
    } else {
        options.filter { option ->
            keepOnSearch(option) || searchText(option).lowercase().let { text -> words.all { it in text } }
        }
    }.take(MAX_SHOWN)
    val create = onCreate?.takeIf { filtered.none { !keepOnSearch(it) } && query.isNotBlank() }

    // The highlight starts on the picked row, so ↵ on a freshly opened list
    // keeps what was there; otherwise on the first row that can be picked.
    var active by remember(filtered) {
        val start = filtered.indexOfFirst { isSelected(it) && isEnabled(it) }
            .takeIf { it >= 0 } ?: filtered.indexOfFirst(isEnabled)
        mutableIntStateOf(start)
    }
    fun step(by: Int) {
        var next = active + by
        while (next in filtered.indices && !isEnabled(filtered[next])) next += by
        if (next in filtered.indices) active = next
    }
    var fromKeys by remember { mutableStateOf(false) }
    val requesters = remember(filtered.size) { List(filtered.size) { BringIntoViewRequester() } }
    LaunchedEffect(active, fromKeys) {
        if (fromKeys) requesters.getOrNull(active)?.let { runCatching { it.bringIntoView() } }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val gap = with(density) { POPUP_GAP.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { UnderAnchor(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(POPUP_RADIUS)
        Column(
            modifier = Modifier
                .width(width)
                .shadow(POPUP_ELEVATION, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(HAIRLINE, colors.border, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionUp -> {
                            fromKeys = true
                            step(if (event.key == Key.DirectionDown) 1 else -1)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            val row = filtered.getOrNull(active)?.takeIf(isEnabled)
                            when {
                                create != null -> {
                                    create(query.trim())
                                    onDismiss()
                                }
                                row != null -> onPick(row)
                            }
                            true
                        }
                        Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            header?.let {
                Box(Modifier.fillMaxWidth().padding(horizontal = ROW_PADDING_H, vertical = ZillitTheme.spacing.md)) {
                    it()
                }
                ZillitDivider()
            }
            if (searchable) {
                SearchLine(
                    query = query,
                    onQuery = { query = it },
                    focus = focus,
                    placeholder = searchPlaceholder ?: (str(S.search) + "…"),
                )
                ZillitDivider()
            } else {
                // Somewhere for the keys to land when there is no search box.
                Box(Modifier.focusRequester(focus).focusable())
            }
            pinnedAction?.let { action ->
                CreateRow(text = action.label) {
                    onDismiss()
                    action.onClick()
                }
                ZillitDivider()
            }
            when {
                create != null -> CreateRow(text = str(S.desktop_create_quoted, query.trim())) {
                    create(query.trim())
                    onDismiss()
                }
                filtered.isEmpty() -> ZillitText(
                    text = if (options.isEmpty()) emptyText else str(S.drive_no_results_for_format, query.trim()),
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = ROW_PADDING_H, vertical = ZillitTheme.spacing.md),
                )
                else -> ZillitScrollColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX)) {
                    filtered.forEachIndexed { index, option ->
                        val group = section?.invoke(option)
                        if (group != null && (index == 0 || section(filtered[index - 1]) != group)) {
                            SectionHeader(group)
                        }
                        val enabled = isEnabled(option)
                        OptionRow(
                            selected = isSelected(option),
                            highlighted = index == active,
                            enabled = enabled,
                            isLast = index == filtered.lastIndex,
                            label = label(option),
                            subtitle = subtitle?.invoke(option),
                            leading = when {
                                optionLeading != null -> ({ optionLeading(option) })
                                // A null or "" option is the conventional "none" row: it keeps
                                // the tile's space but draws no tile, so it does not read as a pick.
                                showInitials -> ({
                                    ZillitInitialsTile(if (option == null || option == "") "" else label(option))
                                })
                                else -> null
                            },
                            custom = renderOption?.let { render -> { render(option, isSelected(option)) } },
                            onHover = {
                                if (enabled) {
                                    fromKeys = false
                                    active = index
                                }
                            },
                            onClick = { onPick(option) },
                            modifier = Modifier.bringIntoViewRequester(requesters[index]),
                        )
                    }
                }
            }
            OptionFooter(footer(filtered.size))
        }
    }
}

/** A group's name above its rows — small, upper-case, on the footer's tint. */
@Composable
private fun SectionHeader(text: String) {
    val colors = ZillitTheme.colors
    Column {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMuted,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ROW_PADDING_H, vertical = ZillitTheme.spacing.xs),
        )
        ZillitDivider()
    }
}

/** The bare search line at the top of the card: a glass, then the input. */
@Composable
private fun SearchLine(query: String, onQuery: (String) -> Unit, focus: FocusRequester, placeholder: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ROW_PADDING_H, vertical = SEARCH_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Search, tint = colors.textMuted, size = SEARCH_ICON)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                ZillitText(
                    text = placeholder,
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

/**
 * One row: the [leading] content (the initials tile, by default), the label
 * and its optional muted [subtitle], a tick when [selected] — or [custom]
 * content in place of all but the tick. [highlighted] is the keyboard/pointer
 * cursor's soft wash; [selected] is the accent wash and bar. A row not
 * [enabled] is greyed and ignores the pointer.
 */
@Suppress("LongParameterList")
@Composable
private fun OptionRow(
    selected: Boolean,
    highlighted: Boolean,
    enabled: Boolean,
    isLast: Boolean,
    label: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    custom: (@Composable () -> Unit)?,
    onHover: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The pointer moves the highlight, as on the web; the keys move it back.
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .background(
                    when {
                        selected -> colors.accentSoft
                        highlighted && enabled -> colors.surfaceHover
                        else -> Color.Transparent
                    },
                )
                .drawBehind { if (selected) drawRect(colors.accent, size = size.copy(width = SELECTED_BAR.toPx())) }
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            if (custom != null) {
                Box(Modifier.weight(1f)) { custom() }
            } else {
                leading?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = label,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (selected) colors.accentText else colors.textPrimary,
                        maxLines = 1,
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = it,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (selected) ZillitIcon(icon = ZillitIcons.Check, tint = colors.accent, size = TICK)
        }
        if (!isLast) ZillitDivider()
    }
}

/**
 * The accent tile leading a row: the label's first two letters or digits,
 * as the web draws them ("Post-Production" → "PO"). A label with none —
 * a dash, an em-space — gets the tile's space left empty, so the labels
 * below and above it stay aligned.
 */
@Composable
fun ZillitInitialsTile(label: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val initials = optionInitials(label)
    val shape = RoundedCornerShape(TILE_RADIUS)
    Box(
        modifier = modifier
            .size(TILE)
            .clip(shape)
            .background(
                if (initials.isEmpty()) {
                    SolidColor(Color.Transparent)
                } else {
                    Brush.linearGradient(listOf(colors.accent, colors.accentHover))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isNotEmpty()) {
            ZillitText(
                text = initials,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = TILE_TEXT),
                color = colors.textOnAccent,
                maxLines = 1,
            )
        }
    }
}

/** The label's first two letters or digits, upper-cased; empty if it has none. */
internal fun optionInitials(label: String): String =
    label.filter { it.isLetterOrDigit() }.take(2).uppercase()

/** An accent "+" row: `Create "<typed>"`, or a [ZillitOptionAction] pinned on top. */
@Composable
private fun CreateRow(text: String, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceHover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_PADDING_H, vertical = ROW_PADDING_V),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Box(
            modifier = Modifier.size(TILE).clip(RoundedCornerShape(TILE_RADIUS)).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = ZillitIcons.Add, tint = colors.accentText, size = TICK)
        }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accentText,
            maxLines = 1,
        )
    }
}

/** The tinted strip along the bottom: the count, then the keys. */
@Composable
private fun OptionFooter(text: String) {
    val colors = ZillitTheme.colors
    Column {
        ZillitDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ROW_PADDING_H, vertical = FOOTER_PADDING_V),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = text,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Kbd("↑")
            Kbd("↓")
            HintText(str(S.drive_cd_navigate))
            Kbd("↵")
            HintText(str(S.select))
        }
    }
}

@Composable
private fun Kbd(text: String) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(KBD_RADIUS)
    Box(
        modifier = Modifier
            .size(KBD)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(HAIRLINE, colors.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontSize = KBD_TEXT),
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun HintText(text: String) {
    ZillitText(
        text = text.lowercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/**
 * A select's own width, measured by [Modifier.zillitSelectAnchor], so its
 * list opens exactly as wide — never narrower than [POPUP_MIN_WIDTH].
 */
class ZillitSelectAnchor internal constructor() {
    internal var px by mutableIntStateOf(0)
}

@Composable
fun rememberZillitSelectAnchor(): ZillitSelectAnchor = remember { ZillitSelectAnchor() }

/** Measures the composable a [ZillitOptionPopup] hangs from. */
fun Modifier.zillitSelectAnchor(anchor: ZillitSelectAnchor): Modifier = onSizeChanged { anchor.px = it.width }

/** The list's width: [override] when the caller set one, else the select's own. */
@Composable
fun ZillitSelectAnchor.popupWidth(override: Dp? = null): Dp {
    if (override != null) return override
    val measured = with(LocalDensity.current) { px.toDp() }
    return if (measured > POPUP_MIN_WIDTH) measured else POPUP_MIN_WIDTH
}

/** "N option(s)" — the footer a single select shows. */
@Composable
fun zillitOptionCount(count: Int): String = str(
    if (count == 1) S.desktop_search_select_option_count_one else S.desktop_search_select_option_count_other,
    count,
)

/**
 * Under the anchor, left edges aligned; above it when the window has no
 * room below; pushed back inside the window at either side.
 */
private class UnderAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

internal val TRIGGER_HEIGHT = 36.dp
private val TRIGGER_RADIUS = 8.dp
private val GLOW_WIDTH = 3.dp
private const val GLOW_ALPHA = 0.18f
private val CHEVRON = 16.dp
private val CLEAR_BUTTON = 20.dp
private const val DISABLED_ALPHA = 0.45f
private val HAIRLINE = 1.dp

internal val POPUP_MIN_WIDTH = 220.dp
private val POPUP_RADIUS = 14.dp
private val POPUP_ELEVATION = 12.dp
private val POPUP_GAP = 8.dp
private val LIST_MAX = 300.dp

private val SEARCH_PADDING_V = 12.dp
private val SEARCH_ICON = 16.dp
private val ROW_PADDING_H = 16.dp
private val ROW_PADDING_V = 10.dp
private val ROW_GAP = 12.dp
private val SELECTED_BAR = 3.dp
private val TILE = 30.dp
private val TILE_RADIUS = 8.dp
private val TILE_TEXT = 11.sp
private val TICK = 14.dp

private val FOOTER_PADDING_V = 10.dp
private val KBD = 20.dp
private val KBD_RADIUS = 5.dp
private val KBD_TEXT = 10.sp

/**
 * Rows composed at once. Above every real list (the ~400 timezones, ~240
 * dial codes) so nothing is reachable only by search; a list longer still is
 * searched, not scrolled, and this keeps the popup composing quickly.
 */
private const val MAX_SHOWN = 1000

/** Lists longer than this get the search line by default. */
internal const val SEARCH_THRESHOLD = 6
