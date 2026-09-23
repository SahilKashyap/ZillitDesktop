package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * The Account Hub's page header — the web's `ui/PageHeader`.
 *
 * An accent eyebrow trailed by a hairline rule in the same colour, then the
 * title in the display face and a description held to 60% of the width. The
 * app's shared `ZillitPageHeader` is quieter (grey eyebrow, sans title) and
 * serves every tool; the hub's pages drew with it and read as a different
 * product beside Approvers and Forms Configuration, which already used the
 * display face. This keeps the hub consistent with itself and the web without
 * restyling every other tool.
 *
 * [actions] sit on the title row, top-right, where the desktop's pages have
 * always put their primary button.
 */
@Composable
fun HubPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (eyebrow != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitText(
                    text = eyebrow.uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontSize = EYEBROW_SIZE,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = EYEBROW_TRACKING,
                    ),
                    color = colors.accent,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(colors.accent.copy(alpha = RULE_ALPHA)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(
                    text = title,
                    style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                if (!description.isNullOrBlank()) {
                    ZillitText(
                        text = description,
                        style = ZillitTheme.typography.bodyMedium.copy(fontSize = DESCRIPTION_SIZE),
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(DESCRIPTION_WIDTH),
                    )
                }
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    content = actions,
                )
            }
        }
    }
}

private val EYEBROW_SIZE = 12.sp
private val EYEBROW_TRACKING = 0.2.em
private val DESCRIPTION_SIZE = 14.sp
private const val DESCRIPTION_WIDTH = 0.6f
private const val RULE_ALPHA = 0.2f
