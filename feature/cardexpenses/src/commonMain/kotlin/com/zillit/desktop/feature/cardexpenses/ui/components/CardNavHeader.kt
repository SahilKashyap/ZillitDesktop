package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The card sidebar's title card: a bordered back chip beside the tool's name.
 *
 * The web's `Sidebar.jsx` title wrapper — its own rounded card with a hairline
 * border and a soft shadow, a 30px chip and the title in extra-bold. It is the
 * only way back once the Account Hub renders this tool full-bleed, without its
 * own sidebar, the way the web's hub does for Card Expenses
 * (`SELF_NAV_MODULE_PREFIXES`): standing alone the chip closes the tool, and
 * inside the hub the hub's navigator turns the same close into "back to the
 * hub".
 *
 * Copied from the Account Hub's `RailHeaderCard` rather than shared with it:
 * this module does not depend on the hub, and the two draw the same card.
 */
@Composable
fun CardNavHeader(title: String, backLabel: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(HEADER_ELEVATION, ZillitTheme.shapes.large, clip = false)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP),
    ) {
        Box(
            modifier = Modifier
                .size(BACK_CHIP)
                .clip(ZillitTheme.shapes.medium)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.medium)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIconButton(icon = ZillitIcons.ArrowLeft, contentDescription = backLabel, onClick = onBack)
        }
        ZillitText(
            text = title,
            style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            maxLines = 1,
        )
    }
}

private val HAIRLINE = 1.dp
private val BACK_CHIP = 30.dp
private val CHIP_GAP = 10.dp
private val HEADER_ELEVATION = 3.dp
