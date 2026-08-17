package com.zillit.desktop.feature.settings.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.avatarHue
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * A run of settings destinations under one heading.
 *
 * One card per group rather than a card per row: twenty separately outlined
 * rows is a ladder, and the border is doing nothing except repeating itself.
 * Inside the card the rows are separated by a hairline that starts where the
 * text does, so the icons form a column the eye can run down.
 */
@Composable
fun SettingsEntryGroup(
    group: SettingsGroup,
    onOpen: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        // The same heading the preference sections wear, so a page carrying
        // both does not look like two pages stitched together.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitIcon(
                icon = group.icon,
                contentDescription = null,
                tint = ZillitTheme.colors.textMuted,
                size = HEADING_GLYPH,
            )
            ZillitText(
                text = group.title,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surface)
                .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.medium),
        ) {
            group.entries.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                EntryRow(entry = entry, onOpen = { onOpen(entry.destination) })
            }
        }
    }
}

/**
 * One destination: what it is, what it does, and whether it goes anywhere yet.
 *
 * The whole row is the target, not the title — a click that lands one line
 * below the words and does nothing is the most common way a list like this
 * feels broken.
 */
@Composable
private fun EntryRow(entry: SettingsEntry, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Only rows that go somewhere light up. A hover highlight on a row that
    // cannot be opened is a promise the click will not keep.
    val liftable = entry.isOpenable
    val background by animateFloatAsState(
        targetValue = if (hovered && liftable) 1f else 0f,
        label = "entryHover",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (entry.tone == EntryTone.Danger) {
                    colors.dangerSoft.copy(alpha = background * DANGER_HOVER_TINT)
                } else {
                    colors.surfaceHover.copy(alpha = background)
                },
            )
            .hoverable(interaction, enabled = liftable)
            .then(
                if (liftable) {
                    Modifier.clickable(onClickLabel = entry.title, onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        EntryIcon(entry)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            ZillitText(
                text = entry.title,
                style = ZillitTheme.typography.titleSmall,
                color = when {
                    entry.tone == EntryTone.Danger -> colors.danger
                    entry.isOpenable -> colors.textPrimary
                    else -> colors.textSecondary
                },
                maxLines = 1,
            )
            ZillitText(
                text = entry.detail,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }

        ZillitBadge(count = entry.badge)
        EntryAffordance(entry = entry, hovered = hovered)
    }
}

/** The right-hand end of a row: where it goes, or why it does not go yet. */
@Composable
private fun EntryAffordance(entry: SettingsEntry, hovered: Boolean) {
    if (!entry.isOpenable) {
        // Says why the row does not respond, on the row, rather than leaving
        // the reader to click it twice and wonder.
        ZillitTag("Soon", tone = TagTone.Neutral)
        return
    }

    // Slides a hair to the right under the cursor — the cheapest possible
    // "this opens something", and it costs no layout.
    ZillitIcon(
        icon = ZillitIcons.ChevronRight,
        contentDescription = null,
        tint = if (hovered) ZillitTheme.colors.textSecondary else ZillitTheme.colors.textMuted,
        size = CHEVRON,
        modifier = Modifier.offset(x = if (hovered) CHEVRON_NUDGE else 0.dp),
    )
}

/**
 * The row's glyph on a tinted disc.
 *
 * The hue comes from the title by the same rule avatars and tool tiles use, so
 * a row keeps its colour everywhere it appears and becomes findable by shape
 * rather than by reading. Destructive rows override it — a delete that took a
 * cheerful teal from the hash would be the one row on the page that has to read
 * as dangerous at a glance.
 */
@Composable
private fun EntryIcon(entry: SettingsEntry) {
    val colors = ZillitTheme.colors
    val danger = entry.tone == EntryTone.Danger
    val hue = if (danger) colors.danger else avatarHue(entry.title)

    Box(
        modifier = Modifier
            .size(DISC)
            .clip(ZillitTheme.shapes.medium)
            .background(hue.copy(alpha = if (entry.isOpenable) DISC_TINT else PLANNED_DISC_TINT)),
        contentAlignment = Alignment.Center,
    ) {
        // The glyph keeps its full hue even on a row that is not built yet.
        // Dimming it too made these unreadable against the dark theme's
        // surface — the muted title and the "Soon" tag already carry that
        // meaning, and a glyph nobody can make out carries none.
        ZillitIcon(
            icon = entry.icon,
            contentDescription = null,
            tint = hue,
            size = GLYPH,
        )
    }
}

/**
 * Starts under the text, not under the icon.
 *
 * A rule that runs the full width cuts the icon column in half at every row;
 * indenting it lets the discs read as one strip down the card.
 */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DIVIDER_INSET)
            .height(HAIRLINE)
            .background(ZillitTheme.colors.divider),
    )
}

/**
 * What the page says when a search matched nothing.
 *
 * Echoes the query, because the alternative — "No results" — leaves the reader
 * unsure whether the page is empty or the filter is.
 */
@Composable
fun NoEntriesMatched(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Search,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = EMPTY_GLYPH,
        )
        ZillitText(
            text = "Nothing here matches “$query”.",
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/** Explains the "Soon" tags once, at the top, instead of on every row. */
@Composable
fun PlannedNotice(modifier: Modifier = Modifier) {
    val colors: Color = ZillitTheme.colors.info
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(ZillitTheme.colors.infoSoft)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Info,
            contentDescription = null,
            tint = colors,
            size = NOTICE_GLYPH,
        )
        ZillitText(
            text = "Rows marked Soon are on their way to the desktop app. " +
                "They work on the phone and web apps today.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

private val HAIRLINE = 1.dp
private val DISC = 34.dp
private val GLYPH = 17.dp
private val HEADING_GLYPH = 14.dp
private val CHEVRON = 16.dp
private val CHEVRON_NUDGE = 2.dp
private val NOTICE_GLYPH = 16.dp
private val EMPTY_GLYPH = 22.dp
private val DIVIDER_INSET = 58.dp
private const val DISC_TINT = 0.14f
private const val PLANNED_DISC_TINT = 0.10f
private const val DANGER_HOVER_TINT = 0.7f
