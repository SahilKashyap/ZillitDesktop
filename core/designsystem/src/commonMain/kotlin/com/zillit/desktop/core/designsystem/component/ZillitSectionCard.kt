package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * A titled panel — the unit every Account Hub screen is assembled from.
 *
 * The web draws this inline in a dozen places (`AdminSectionCard` in the card
 * Overview, `Card` in the cash pages, bare divs elsewhere) and they have
 * drifted: different radii, different header weights, different padding. One
 * component so a dashboard reads as one surface rather than six.
 *
 * [content] is a `ColumnScope` so callers can put a list straight in it. Pass
 * `padded = false` when the content is a table or a list of rows that should
 * meet the card's edges.
 */
@Composable
fun ZillitSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    meta: String? = null,
    padded: Boolean = true,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(CARD_HAIRLINE, colors.border, ZillitTheme.shapes.large),
    ) {
        if (title != null) {
            SectionHeader(title = title, icon = icon, meta = meta, action = action)
        }
        Column(
            modifier = if (padded) Modifier.padding(ZillitTheme.spacing.lg) else Modifier,
            content = content,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector?,
    meta: String?,
    action: (@Composable RowScope.() -> Unit)?,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        icon?.let { ZillitIcon(it, tint = colors.accent, size = ZillitDimens.iconSmall) }
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall, maxLines = 1)
        Spacer(Modifier.weight(1f))
        meta?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        action?.invoke(this)
    }
    ZillitDivider()
}

/** A hairline rule, at the theme's own divider weight. */
@Composable
fun ZillitDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(CARD_HAIRLINE)
            .background(ZillitTheme.colors.divider),
    )
}

/**
 * The same hairline, turned through ninety degrees — for separating columns.
 *
 * Its own composable because [ZillitDivider] fills its **width**, and dropping
 * that into a `Row` does not merely look wrong: it claims the row's entire
 * width, starves every weighted sibling to zero and leaves the layout blank.
 * That is a silent, total failure, and it is much easier to reach for the
 * wrong one than to diagnose the result.
 */
@Composable
fun ZillitVerticalDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .fillMaxHeight()
            .width(CARD_HAIRLINE)
            .background(ZillitTheme.colors.divider),
    )
}

/**
 * A caption above a group of controls — `WORKFLOW`, `MY EXPENSES`.
 *
 * Letter-spaced small caps, matching the web's section labels. Its own
 * component because a heading built from a `ZillitText` with four modifiers
 * gets copied with three of them.
 */
@Composable
fun ZillitSectionLabel(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        modifier = modifier,
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

internal val CARD_HAIRLINE: Dp = 1.dp
