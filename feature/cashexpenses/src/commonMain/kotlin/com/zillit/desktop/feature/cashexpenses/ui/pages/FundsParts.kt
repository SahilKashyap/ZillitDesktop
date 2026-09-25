package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText

// Small pieces the Top-Ups, Cash Extension, Fund Requests and Cash Recon pages
// share — the web draws each of them inline in every one of those files.

/** The web's `Notice`: an icon, a bold title and the sentence after it, on an amber wash. */
@Composable
internal fun TitledNotice(title: String, body: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .border(1.dp, colors.warning.copy(alpha = NOTICE_BORDER), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon, tint = colors.warning)
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                append("  ")
                append(body)
            },
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A white card with the hairline border — the web's `bg-white border rounded-[14px]`. */
@Composable
internal fun FundsCard(
    modifier: Modifier = Modifier,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(background ?: ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
        content = content,
    )
}

/** A small uppercase caption above a figure. */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = color ?: ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

/** A caption over a value — the details modals' cells. */
@Composable
internal fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Caption(label)
        ZillitText(
            text = value,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color ?: ZillitTheme.colors.textPrimary,
        )
    }
}

/** One label / figure line of a summary. */
@Composable
internal fun SummaryLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    valueColor: Color? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = if (strong) {
                ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            } else {
                ZillitTheme.typography.bodySmall
            },
            color = if (strong) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(
                fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            ),
            color = valueColor ?: ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

private const val NOTICE_BORDER = 0.2f
