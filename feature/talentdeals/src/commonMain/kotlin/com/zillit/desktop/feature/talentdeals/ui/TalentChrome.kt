package com.zillit.desktop.feature.talentdeals.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/** The page column's width — the web's `max-w-4xl`. */
internal val PAGE_MAX_WIDTH = 896.dp

/** The gutter every face keeps from the window's edge. */
internal val PAGE_GUTTER = 24.dp

/**
 * The frosted header every face shares: a back chip, the amber `TALENT DEALS`
 * eyebrow (a link back to the register when [onEyebrow] is set), then the
 * current place after a slash, and the face's actions at the right.
 */
@Composable
internal fun TalentHeader(
    backLabel: String,
    onBack: () -> Unit,
    eyebrow: String,
    modifier: Modifier = Modifier,
    onEyebrow: (() -> Unit)? = null,
    current: String? = null,
    sub: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(BACK_CHIP)
                    .clip(ZillitTheme.shapes.large)
                    .border(1.dp, colors.border, ZillitTheme.shapes.large)
                    .clickable(onClickLabel = backLabel, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                ZillitIcon(icon = ZillitIcons.ChevronLeft, contentDescription = backLabel, size = 16.dp)
            }
            ZillitText(
                text = eyebrow.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = colors.accentText,
                modifier = if (onEyebrow != null) Modifier.clickable(onClick = onEyebrow) else Modifier,
            )
            if (current != null) {
                ZillitText("/", color = colors.textMuted)
                ZillitText(current, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1)
            }
            if (sub != null) {
                ZillitText("· $sub", style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/** A small uppercase label over a value — the read-only page's field. */
@Composable
internal fun LabelledValue(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
        content()
    }
}

/** A plain text value for [LabelledValue]; a blank one reads as a dash. */
@Composable
internal fun ValueText(text: String?, mono: Boolean = false) {
    ZillitText(
        text = text?.takeIf { it.isNotBlank() } ?: "—",
        style = if (mono) ZillitTheme.typography.numeric else ZillitTheme.typography.bodyLarge,
        maxLines = Int.MAX_VALUE,
    )
}

/** A section of the read-only page: an amber uppercase title, an optional italic note, then the body. */
@Composable
internal fun ContractSection(title: String, modifier: Modifier = Modifier, note: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier.padding(top = ZillitTheme.spacing.md, bottom = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = title.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.accentText,
            )
            if (note != null) {
                ZillitText(
                    text = note,
                    style = ZillitTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic),
                    color = colors.textMuted,
                )
            }
        }
        content()
    }
}

/** Centres a page column at [PAGE_MAX_WIDTH] with the gutter either side. */
@Composable
internal fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = PAGE_GUTTER),
        contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth(), content = content)
    }
}

private val BACK_CHIP = 36.dp
