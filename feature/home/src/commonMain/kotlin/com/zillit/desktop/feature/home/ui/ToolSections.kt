package com.zillit.desktop.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.home.domain.ToolPresentation

/**
 * The grid, as the phones now draw it (`ToolsGroupedAdapter`): each of the
 * production's sections is a card of rows — the tool's glyph, its name, its
 * unread count, an ⓘ that says what the tool is for, and a chevron.
 *
 * Rows rather than the old square tiles because a name reads in a line, a
 * count has somewhere to sit that is not over the artwork, and the ⓘ needs a
 * place of its own. A desktop window is wide where a phone is tall, so the
 * cards stand in as many columns as fit, each card going to the shortest
 * column — the sections are uneven, and a plain grid would leave holes.
 */
@Composable
internal fun ToolSections(
    sections: List<ToolSection>,
    badges: Map<String, Int>,
    query: String,
    onOpen: (ToolPresentation) -> Unit,
    onInfo: (ToolPresentation) -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SECTIONS_PADDING),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = columnCount(maxWidth)
            val laid = remember(sections, columns) { sections.intoColumns(columns) }
            Row(horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                laid.forEach { column ->
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                    ) {
                        column.forEach { section ->
                            SectionCard(section, badges, query, onOpen, onInfo)
                        }
                    }
                }
            }
        }
    }
}

/** As many columns as leave each card at least [CARD_MIN] wide; never fewer than one. */
internal fun columnCount(width: Dp): Int =
    ((width + COLUMN_GAP) / (CARD_MIN + COLUMN_GAP)).toInt().coerceIn(1, MAX_COLUMNS)

/**
 * Deals sections into [columns], each to the column that is shortest so
 * far, measured in rows (a header weighs about one) — reading order is kept
 * within a column, and the page ends roughly level.
 */
internal fun List<ToolSection>.intoColumns(columns: Int): List<List<ToolSection>> {
    val laid = List(columns) { mutableListOf<ToolSection>() }
    val heights = IntArray(columns)
    forEach { section ->
        val shortest = heights.indices.minBy { heights[it] }
        laid[shortest] += section
        heights[shortest] += section.tools.size + HEADER_WEIGHT
    }
    return laid.filter { it.isNotEmpty() }
}

/** One section: its heading, then its tools in a card. */
@Composable
private fun SectionCard(
    section: ToolSection,
    badges: Map<String, Int>,
    query: String,
    onOpen: (ToolPresentation) -> Unit,
    onInfo: (ToolPresentation) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        SectionHeading(section.title, section.tools.size)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.large),
        ) {
            section.tools.forEachIndexed { index, tool ->
                if (index > 0) {
                    // Inset to the name, as a list divider is: the glyphs
                    // stand in a column of their own.
                    Box(
                        Modifier
                            .padding(start = DIVIDER_INSET)
                            .fillMaxWidth()
                            .height(HAIRLINE)
                            .background(colors.border),
                    )
                }
                ToolRow(
                    tool = tool,
                    badge = badges[tool.identifier] ?: 0,
                    query = query,
                    onOpen = { onOpen(tool) },
                    onInfo = { onInfo(tool) },
                )
            }
        }
    }
}

/** The section's name in its own colour, and how many tools it holds. */
@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(Modifier.size(SECTION_DOT).clip(CircleShape).background(avatarHue(title)))
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall, color = ZillitTheme.colors.textPrimary)
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = COUNT_PAD),
        )
    }
}

@Composable
private fun ToolRow(
    tool: ToolPresentation,
    badge: Int,
    query: String,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The same one-name, one-colour rule the avatars keep, so a tool is
    // findable by its colour after the first visit.
    val hue = avatarHue(tool.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            .clickable(onClickLabel = tool.label, onClick = onOpen)
            .padding(start = ZillitTheme.spacing.md, end = ZillitTheme.spacing.sm)
            .height(ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            Modifier
                .size(GLYPH_TILE)
                .clip(ZillitTheme.shapes.medium)
                .background(hue.copy(alpha = GLYPH_TINT)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = tool.icon, contentDescription = null, tint = hue, size = GLYPH)
        }
        ZillitText(
            text = highlightedLabel(tool.label, query),
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Uncapped: a row has room for the real number, as both phones print it.
        if (badge > 0) ZillitBadge(count = badge, cap = null)
        // On every tool: each one has something to say about itself.
        ZillitIconButton(
            icon = ZillitIcons.Info,
            contentDescription = str(S.desktop_sa_about_code, tool.label),
            onClick = onInfo,
            tint = if (hovered) colors.textSecondary else colors.textMuted,
            size = INFO_BUTTON,
        )
        ZillitIcon(
            icon = ZillitIcons.ChevronRight,
            contentDescription = null,
            tint = if (hovered) colors.textSecondary else colors.textMuted,
            size = CHEVRON,
        )
    }
}

/**
 * The ⓘ's answer: the tool's name, what it is for, and the way in — the
 * phones' info dialog (OK only there; here Open as well, since the question
 * "what is this?" is usually followed by "then let me see it").
 */
@Composable
internal fun ToolInfoDialog(
    tool: ToolPresentation?,
    describe: (ToolPresentation) -> String,
    onOpen: (ToolPresentation) -> Unit,
    onDismiss: () -> Unit,
) {
    ZillitDialogShell(
        title = tool?.label.orEmpty(),
        icon = tool?.icon,
        visible = tool != null,
        onDismiss = onDismiss,
        width = INFO_WIDTH,
        actions = {
            ZillitButton(text = str(S.ok), onClick = onDismiss, variant = ButtonVariant.Secondary)
            tool?.let { shown ->
                ZillitButton(text = str(S.drive_btn_open), onClick = { onDismiss(); onOpen(shown) })
            }
        },
    ) {
        ZillitText(
            text = tool?.let(describe).orEmpty(),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private val SECTIONS_PADDING = 24.dp
private val CARD_MIN = 320.dp
private const val MAX_COLUMNS = 4
private val COLUMN_GAP = 20.dp
private val CARD_GAP = 24.dp
private const val HEADER_WEIGHT = 2
private val ROW_HEIGHT = 52.dp
private val GLYPH_TILE = 34.dp
private val GLYPH = 20.dp
private const val GLYPH_TINT = 0.14f
private val INFO_BUTTON = 28.dp
private val CHEVRON = 16.dp
private val SECTION_DOT = 8.dp
private val COUNT_PAD = 1.dp
private val HAIRLINE = 1.dp
private val DIVIDER_INSET = 62.dp
private val INFO_WIDTH = 440.dp
