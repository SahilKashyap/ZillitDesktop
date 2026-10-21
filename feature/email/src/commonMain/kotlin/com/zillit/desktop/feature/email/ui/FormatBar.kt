package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmojiPicker
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntries
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuSurface
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.BlockKind
import com.zillit.desktop.feature.email.domain.MarkFamily
import com.zillit.desktop.feature.email.domain.TextMark
import com.zillit.desktop.feature.email.domain.sanitisedHref

/**
 * The composer's formatting controls.
 *
 * The web's eight groups in the web's order: undo and redo, the two pickers,
 * the four switches, the two lists, the two colours, the two indents, then a
 * link and an emoji. A [FlowRow] rather than a fixed two-row layout — the
 * composer is also a pop-out window, so the groups should break wherever the
 * width runs out instead of at a line someone picked once.
 *
 * Every control is non-focusable: each acts on the field's current selection,
 * and a control that steals the caret makes every second action apply to
 * nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun FormatBar(
    state: FormatBarState,
    onToggle: (TextMark) -> Unit,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleList: (BlockKind) -> Unit,
    onIndent: (Int) -> Unit,
    onInsertLink: (String, String) -> Unit,
    onInsertText: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        FormatGroup {
            IconChip(
                icon = ZillitIcons.Undo,
                tooltip = str(S.dd_rt_undo),
                enabled = state.canUndo,
                label = str(S.dd_rt_undo),
                onClick = onUndo,
            )
            IconChip(
                icon = ZillitIcons.Redo,
                tooltip = str(S.dd_rt_redo),
                enabled = state.canRedo,
                label = str(S.dd_rt_redo),
                onClick = onRedo,
            )
        }

        FormatGroup {
            FamilyPicker(current = state.fontFamily, onPick = onPick, onClear = onClear)
            SizePicker(current = state.sizePx, onPick = onPick, onClear = onClear)
        }

        FormatGroup {
            SWITCH_MARKS.forEach { style ->
                GlyphChip(
                    label = style.switchLabel,
                    labelStyle = style.switchLabelStyle,
                    tooltip = style.switchTooltip,
                    isActive = style in state.active,
                    onClick = { onToggle(style) },
                )
            }
        }

        FormatGroup {
            IconChip(
                icon = ZillitIcons.ListOrdered,
                tooltip = str(S.numbered_list),
                isActive = state.lineStyle?.kind == BlockKind.Ordered,
                onClick = { onToggleList(BlockKind.Ordered) },
            )
            IconChip(
                icon = ZillitIcons.ListBullet,
                tooltip = str(S.bullet_list),
                isActive = state.lineStyle?.kind == BlockKind.Bullet,
                onClick = { onToggleList(BlockKind.Bullet) },
            )
        }

        FormatGroup {
            ColorPicker(
                tooltip = str(S.text_color),
                current = state.colorHex,
                clears = MarkFamily.TextColor,
                style = { TextMark.TextColor(it) },
                onPick = onPick,
                onClear = onClear,
                glyph = "A",
            )
            ColorPicker(
                tooltip = str(S.desktop_email_highlight_text),
                current = state.highlightHex,
                clears = MarkFamily.Highlight,
                style = { TextMark.Highlight(it) },
                onPick = onPick,
                onClear = onClear,
                icon = ZillitIcons.Highlighter,
            )
        }

        FormatGroup {
            IconChip(ZillitIcons.IndentLess, str(S.desktop_email_indent_less)) { onIndent(-1) }
            IconChip(ZillitIcons.IndentMore, str(S.desktop_email_indent_more)) { onIndent(1) }
        }

        FormatGroup(divided = false) {
            LinkPicker(onInsert = onInsertLink)
            EmojiChip(onPick = onInsertText)
        }
    }
}

/** One cluster of controls, with the rule the web draws after it. */
@Composable
private fun FormatGroup(divided: Boolean = true, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (divided) {
            Box(
                Modifier
                    .padding(horizontal = ZillitTheme.spacing.xs)
                    .width(HAIRLINE)
                    .height(GROUP_RULE_HEIGHT)
                    .background(ZillitTheme.colors.border),
            )
        }
    }
}

/**
 * One toolbar button.
 *
 * Non-focusable — see [FormatBar] — and hover-lit rather than outlined, so a
 * row of fourteen controls reads as a toolbar and not as fourteen fields.
 */
@Composable
private fun FormatChip(
    isActive: Boolean,
    onClick: () -> Unit,
    tooltip: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(
                when {
                    isActive -> colors.accentSoft
                    hovered && enabled -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .focusProperties { canFocus = false }
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = CHIP_HEIGHT)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        ZillitTooltip(tooltip) { content() }
    }
}

/** An icon button, with the web's inline word beside it for undo and redo. */
@Composable
private fun IconChip(
    icon: ImageVector,
    tooltip: String,
    isActive: Boolean = false,
    enabled: Boolean = true,
    label: String? = null,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val tint = when {
        !enabled -> colors.textDisabled
        isActive -> colors.accentText
        else -> colors.textSecondary
    }

    FormatChip(isActive = isActive, enabled = enabled, onClick = onClick, tooltip = tooltip) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIcon(icon, contentDescription = tooltip, tint = tint, size = GLYPH_SIZE)
            if (label != null) {
                ZillitText(text = label, style = ZillitTheme.typography.bodySmall, color = tint)
            }
        }
    }
}

/**
 * A button styled as the thing it does — a bold B, an italic I.
 *
 * Faster to read than any icon set would be for this many states, and the same
 * four glyphs the web draws from its icon font.
 */
@Composable
private fun GlyphChip(
    label: String,
    labelStyle: TextStyle,
    tooltip: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    FormatChip(isActive = isActive, onClick = onClick, tooltip = tooltip) {
        Box(Modifier.width(GLYPH_SIZE), contentAlignment = Alignment.Center) {
            ZillitText(
                text = label,
                style = labelStyle,
                color = if (isActive) colors.accentText else colors.textSecondary,
            )
        }
    }
}

/**
 * A bordered field with a chevron — the web's two Selects.
 *
 * Not [com.zillit.desktop.core.designsystem.component.ZillitSelect]: that one
 * takes focus when it opens, and a picker that moves the caret out of the body
 * applies the next size to nothing.
 */
@Composable
private fun SelectChip(
    label: String,
    width: Dp,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .width(width)
            .heightIn(min = CHIP_HEIGHT)
            .clip(ZillitTheme.shapes.small)
            .background(if (hovered || expanded) colors.surfaceHover else colors.surface)
            .border(HAIRLINE, if (expanded) colors.accent else colors.border, ZillitTheme.shapes.small)
            .hoverable(interaction)
            .focusProperties { canFocus = false }
            .clickable(onClick = onToggle)
            .padding(horizontal = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        ZillitIcon(ZillitIcons.ChevronDown, contentDescription = null, tint = colors.textMuted, size = CHEVRON_SIZE)
    }
}

/** Font family, over the web's own list, with Default as the absence of a mark. */
@Composable
private fun FamilyPicker(
    current: String?,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val default = str(S.desktop_email_format_default)

    Box {
        SelectChip(
            label = current ?: default,
            width = FAMILY_WIDTH,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        ZillitActionMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT),
            entries = listOf(
                ZillitMenuEntry.Action(
                    label = default,
                    icon = if (current == null) ZillitIcons.Check else null,
                    tone = ZillitMenuTone.Primary,
                ) { onClear(MarkFamily.FontFamily) },
            ) + FONT_FAMILIES.map { family ->
                ZillitMenuEntry.Action(
                    label = family,
                    icon = if (family == current) ZillitIcons.Check else null,
                    tone = ZillitMenuTone.Primary,
                ) { onPick(TextMark.FontFamily(family)) }
            },
        )
    }
}

/** Font size, from the same whitelist the web composer offers. */
@Composable
private fun SizePicker(
    current: Int?,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SelectChip(
            label = (current ?: DEFAULT_SIZE).toString(),
            width = SIZE_WIDTH,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        ZillitActionMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = MENU_MAX_HEIGHT),
            entries = FONT_SIZES.map { px ->
                ZillitMenuEntry.Action(
                    label = if (px == DEFAULT_SIZE) str(S.desktop_email_font_size_default, px) else "$px",
                    // The chosen size wears the check tile; the rest sit bare.
                    // No mark means the default size, so that row is the
                    // chosen one until a size is picked.
                    icon = if (px == (current ?: DEFAULT_SIZE)) ZillitIcons.Check else null,
                    tone = ZillitMenuTone.Primary,
                ) {
                    // The default size is the absence of a mark, so the
                    // body follows the theme instead of hard-coding it.
                    if (px == DEFAULT_SIZE) onClear(MarkFamily.FontSize) else onPick(TextMark.FontSize(px))
                }
            },
        )
    }
}

/** Text or highlight colour, over one shared palette. */
@Composable
@Suppress("LongParameterList")
private fun ColorPicker(
    tooltip: String,
    current: String?,
    clears: MarkFamily,
    style: (String) -> TextMark,
    onPick: (TextMark) -> Unit,
    onClear: (MarkFamily) -> Unit,
    glyph: String? = null,
    icon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors
    val tint = if (current != null) colors.accentText else colors.textSecondary

    Box {
        FormatChip(isActive = current != null, onClick = { expanded = true }, tooltip = tooltip) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    ZillitIcon(icon, contentDescription = tooltip, tint = tint, size = GLYPH_SIZE)
                } else {
                    Box(Modifier.width(GLYPH_SIZE), contentAlignment = Alignment.Center) {
                        ZillitText(text = glyph.orEmpty(), style = ZillitTheme.typography.bodyMedium, color = tint)
                    }
                }
                // The bar under the letter is the picked colour itself: a
                // swatch is the only honest label for a colour.
                Box(
                    Modifier
                        .size(SWATCH_BAR_WIDTH, SWATCH_BAR_HEIGHT)
                        .background(current?.let { hexColor(it) } ?: colors.textMuted, RoundedCornerShape(1.dp)),
                )
            }
        }
        ZillitMenuSurface(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ZillitMenuEntries(
                entries = listOf(
                    ZillitMenuEntry.Action(str(S.desktop_email_format_default), ZillitIcons.Minus) { onClear(clears) },
                ),
                onDismiss = { expanded = false },
            )
            PALETTE.chunked(SWATCHES_PER_ROW).forEach { row ->
                SwatchRow(row, current) { hex ->
                    expanded = false
                    onPick(style(hex))
                }
            }
        }
    }
}

@Composable
private fun SwatchRow(row: List<String>, current: String?, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.padding(
            horizontal = ZillitTheme.spacing.md,
            vertical = ZillitTheme.spacing.xxs,
        ),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        row.forEach { hex ->
            Box(
                Modifier
                    .size(SWATCH_SIZE)
                    .clip(RoundedCornerShape(SWATCH_RADIUS))
                    .background(hexColor(hex) ?: colors.textMuted)
                    .border(
                        if (hex == current) 2.dp else 1.dp,
                        if (hex == current) colors.accent else colors.border,
                        RoundedCornerShape(SWATCH_RADIUS),
                    )
                    .clickable { onPick(hex) },
            )
        }
    }
}

/**
 * Insert link: the text to show, and where it goes.
 *
 * Insert stays disabled until the URL is one that can be written into an
 * `href` — the same check the HTML writer applies on the way out, so the
 * button never promises a link the body will not carry.
 */
@Composable
private fun LinkPicker(onInsert: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    val href = url.withScheme()

    Box {
        IconChip(ZillitIcons.Link, str(S.nda_insert_link), isActive = expanded) { expanded = !expanded }
        ZillitMenuSurface(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier.width(LINK_FORM_WIDTH).padding(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(str(S.nda_insert_link), style = ZillitTheme.typography.titleSmall)
                ZillitTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = str(S.desktop_email_text_to_display),
                )
                ZillitTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = str(S.desktop_email_enter_url),
                )
                ZillitButton(
                    text = str(S.insert),
                    enabled = href != null,
                    onClick = {
                        val target = href ?: return@ZillitButton
                        // An empty label means the URL is its own text, which
                        // is what every mail client does with a bare paste.
                        onInsert(label.ifBlank { target }, target)
                        label = ""
                        url = ""
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A bare host typed into the URL box means https, as it does in every browser. */
private fun String.withScheme(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
    return candidate.sanitisedHref()?.takeIf { it.hasHost() }
}

/** A host with a dot in it — enough to tell `https://notes` from `https://a.com`. */
private fun String.hasHost(): Boolean =
    substringAfter("//", substringAfter(':')).substringBefore('/').contains('.')

@Composable
private fun EmojiChip(onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconChip(ZillitIcons.Smiley, str(S.desktop_email_emoji), isActive = expanded) { expanded = !expanded }
        ZillitMenuSurface(expanded = expanded, onDismissRequest = { expanded = false }) {
            ZillitEmojiPicker(
                onPick = { emoji ->
                    expanded = false
                    onPick(emoji)
                },
                modifier = Modifier.padding(ZillitTheme.spacing.sm),
            )
        }
    }
}

private val TextMark.switchLabel: String
    get() = when (this) {
        TextMark.Italic -> "I"
        TextMark.Underline -> "U"
        TextMark.Strike -> "S"
        else -> "B"
    }

private val TextMark.switchTooltip: String
    @Composable get() = when (this) {
        TextMark.Italic -> str(S.italic)
        TextMark.Underline -> str(S.underline)
        TextMark.Strike -> str(S.strikethrough)
        else -> str(S.bold)
    }

private val TextMark.switchLabelStyle: TextStyle
    @Composable get() = ZillitTheme.typography.bodyMedium.copy(
        fontWeight = if (this == TextMark.Bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (this == TextMark.Italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = when (this) {
            TextMark.Underline -> TextDecoration.Underline
            TextMark.Strike -> TextDecoration.LineThrough
            else -> null
        },
    )

/** The web composer's size list: every whole point from 10 to 36. */
internal val FONT_SIZES = (MIN_SIZE..MAX_SIZE).toList()
internal const val DEFAULT_SIZE = 16

/** The web composer's family list, in its order. */
internal val FONT_FAMILIES = listOf(
    "Arial", "Arial Black", "Arial Narrow", "Arial Rounded MT Bold", "Avant Garde",
    "Baskerville", "Big Caslon", "Bodoni MT", "Book Antiqua", "Bookman",
    "Bradley Hand", "Brush Script MT", "Calibri", "Calisto MT", "Cambria",
    "Candara", "Century", "Century Gothic", "Century Schoolbook", "Chalkboard",
    "Chalkduster", "Comic Sans MS", "Consolas", "Copperplate", "Courier",
    "Courier New", "Cursive", "Didot", "Fantasy", "Franklin Gothic Medium",
    "Futura", "Garamond", "Geneva", "Georgia", "Gill Sans",
    "Goudy Old Style", "Helvetica", "Helvetica Neue", "Hoefler Text", "Impact",
    "Lucida Bright", "Lucida Console", "Lucida Grande", "Lucida Sans Unicode", "Monaco",
    "Monospace", "Optima", "Palatino", "Palatino Linotype", "Perpetua",
    "Perpetua Titling MT", "Rockwell", "Rockwell Extra Bold", "Sans Serif", "Segoe UI",
    "Serif", "Tahoma", "Times", "Times New Roman", "Trebuchet MS",
    "Tw Cen MT", "Verdana",
)

/** A compact cut of the web's default swatch grid — the colours mail actually uses. */
internal val PALETTE = listOf(
    "#000000", "#444444", "#888888", "#e60000", "#ff9900",
    "#ffff00", "#008a00", "#0066cc", "#9933ff", "#ffffff",
)

private val SCHEME = Regex("""^[a-z][a-z0-9+.\-]*:""", RegexOption.IGNORE_CASE)

private const val MIN_SIZE = 10
private const val MAX_SIZE = 36
private const val SWATCHES_PER_ROW = 5
private val SWATCH_SIZE = 20.dp
private val SWATCH_RADIUS = 4.dp
private val SWATCH_BAR_WIDTH = 14.dp
private val SWATCH_BAR_HEIGHT = 3.dp
private val GLYPH_SIZE = 16.dp
private val CHEVRON_SIZE = 14.dp
private val CHIP_HEIGHT = 26.dp
private val GROUP_RULE_HEIGHT = 20.dp
private val HAIRLINE = 1.dp
private val FAMILY_WIDTH = 128.dp
private val SIZE_WIDTH = 64.dp
private val MENU_MAX_HEIGHT = 260.dp
private val LINK_FORM_WIDTH = 220.dp
