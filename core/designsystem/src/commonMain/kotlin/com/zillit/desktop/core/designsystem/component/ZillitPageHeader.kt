package com.zillit.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * The heading block at the top of a tool page.
 *
 * [eyebrow] is the small caps line above the title — "Finance", "Petty Cash".
 * It exists because these tools nest three levels deep and the title alone
 * ("Active Floats") does not say which module you are in when the window is
 * torn off onto a second monitor.
 */
@Composable
fun ZillitPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            eyebrow?.let { ZillitSectionLabel(it) }
            ZillitText(text = title, style = ZillitTheme.typography.titleLarge, maxLines = 2)
            description?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
        actions?.let {
            Spacer(Modifier)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                content = it,
            )
        }
    }
}
