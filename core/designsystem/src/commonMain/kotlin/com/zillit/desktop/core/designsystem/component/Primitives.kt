package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * Text with the theme's colour and type scale applied by default.
 *
 * Wrapping Material's `Text` means no screen has to remember to pass a colour,
 * which is how half-themed dark modes happen.
 */
@Composable
fun ZillitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style ?: ZillitTheme.typography.bodyMedium,
        color = color ?: ZillitTheme.colors.textPrimary,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun ZillitIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color? = null,
    size: Dp = ZillitDimens.icon,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint ?: ZillitTheme.colors.textSecondary,
    )
}

@Composable
fun ZillitSpinner(
    modifier: Modifier = Modifier,
    size: Dp = ZillitDimens.icon,
    color: Color? = null,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color ?: ZillitTheme.colors.accent,
        strokeWidth = 2.dp,
    )
}

/**
 * Count badge — unread messages on a tab or rail item.
 *
 * Caps at "99+" like the web app's tab strip, so a busy project cannot stretch
 * the chrome.
 */
@Composable
fun ZillitBadge(
    count: Int,
    modifier: Modifier = Modifier,
    background: Color? = null,
    contentColor: Color? = null,
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(background ?: ZillitTheme.colors.danger)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = contentColor ?: Color.White,
            maxLines = 1,
        )
    }
}

/**
 * As [ZillitText], for text that carries its own spans.
 *
 * An overload rather than a `buildAnnotatedString` at every call site: search
 * highlighting is the only current user, and screens should not have to know
 * that styled text takes a different `Text` overload.
 */
@Composable
fun ZillitText(
    text: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style ?: ZillitTheme.typography.bodyMedium,
        color = color ?: ZillitTheme.colors.textPrimary,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Small status pill — "Draft", "Published", "Overdue". */
@Composable
fun ZillitTag(
    label: String,
    modifier: Modifier = Modifier,
    tone: TagTone = TagTone.Neutral,
) {
    val colors = ZillitTheme.colors
    val (background, content) = when (tone) {
        TagTone.Neutral -> colors.surfaceHover to colors.textSecondary
        TagTone.Accent -> colors.accentSoft to colors.accentText
        TagTone.Success -> colors.successSoft to colors.success
        TagTone.Warning -> colors.warningSoft to colors.warning
        TagTone.Danger -> colors.dangerSoft to colors.danger
        TagTone.Info -> colors.infoSoft to colors.info
    }
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.small)
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(label, style = ZillitTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

enum class TagTone { Neutral, Accent, Success, Warning, Danger, Info }

private const val MAX_BADGE_COUNT = 99
