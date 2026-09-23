package com.zillit.desktop.feature.invoices.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The full-page form's top bar — the web's sticky one on Credit Notes and
 * Sales Invoices: a back chip, `INVOICES / AP / section / title`, then the
 * form's own buttons on the right.
 */
@Composable
internal fun FormTopBar(
    section: String,
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(BACK_CHIP)
                .clip(ZillitTheme.shapes.medium)
                .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = str(S.back), onClick = onBack)
        }
        ZillitText(
            text = str(S.desktop_inv_eyebrow_invoices_ap).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = colors.accentText,
            maxLines = 1,
        )
        Slash()
        ZillitText(text = section, style = ZillitTheme.typography.bodySmall, maxLines = 1)
        Slash()
        ZillitText(
            text = title,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Slash() {
    ZillitText(text = "/", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
}

private val BACK_CHIP = 32.dp
