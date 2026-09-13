package com.zillit.desktop.feature.assetreport.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.assetreport.domain.AssetExportFormat

/**
 * The Export button and its menu — the web's shared `ExportMenu` under the
 * register's own trigger: "Download as", then a card per format with its badge,
 * what the file is for and, on hover, its extension.
 */
@Composable
internal fun AssetExportMenu(
    exporting: AssetExportFormat?,
    enabled: Boolean,
    onExport: (AssetExportFormat) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // The panel carries room for its shadow inside the popup; the offset takes it back out.
    val density = LocalDensity.current
    val drop = with(density) { (EXPORT_BUTTON_HEIGHT + MENU_GAP - MENU_SHADOW_ROOM).roundToPx() }
    val inset = with(density) { MENU_SHADOW_ROOM.roundToPx() }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = open && enabled && exporting == null
    Box {
        ExportTrigger(
            busy = exporting != null,
            enabled = enabled && exporting == null,
            onClick = { open = !open },
        )
        if (visibility.currentState || visibility.targetState) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(inset, drop),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                // Grows from the button's corner and shrinks back into it, as the web's menu does.
                val corner = TransformOrigin(1f, 0f)
                AnimatedVisibility(
                    visibleState = visibility,
                    enter = fadeIn(tween(MENU_MILLIS)) + scaleIn(tween(MENU_MILLIS), MENU_SCALE, corner),
                    exit = fadeOut(tween(MENU_MILLIS)) + scaleOut(tween(MENU_MILLIS), MENU_SCALE, corner),
                ) {
                    MenuPanel(
                        onExport = { format ->
                            open = false
                            onExport(format)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportTrigger(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .height(EXPORT_BUTTON_HEIGHT)
            .alpha(if (enabled || busy) 1f else DISABLED_ALPHA)
            .clip(TRIGGER_SHAPE)
            .background(if (hovered && enabled) colors.surfaceHover else colors.surface)
            .border(1.dp, colors.border, TRIGGER_SHAPE)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            ZillitSpinner(size = 12.dp)
        } else {
            ZillitIcon(icon = ZillitIcons.Download, tint = colors.textSecondary, size = 13.dp)
        }
        ZillitText(
            text = if (busy) "Exporting…" else "Export",
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MenuPanel(onExport: (AssetExportFormat) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .padding(MENU_SHADOW_ROOM)
            .width(320.dp)
            .shadow(24.dp, MENU_SHAPE)
            .clip(MENU_SHAPE)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, MENU_SHAPE)
            .padding(7.dp),
    ) {
        ZillitText(
            text = "DOWNLOAD AS",
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.14.em,
            ),
            color = colors.textMuted,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        )
        AssetExportFormat.entries.forEachIndexed { index, format ->
            if (index > 0) {
                Box(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().height(1.dp)
                        .background(colors.divider),
                )
            }
            FormatCard(format) { onExport(format) }
        }
    }
}

@Composable
private fun FormatCard(format: AssetExportFormat, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) colors.surfaceHover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FormatBadge(format)
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = format.label,
                style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
            )
            ZillitText(
                text = format.purpose,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 2,
            )
        }
        ExtensionChip(format, visible = hovered)
    }
}

/** The format's gradient tile — red for a PDF, green for a spreadsheet, as the web tints them. */
@Composable
private fun FormatBadge(format: AssetExportFormat) {
    val palette = assetPalette()
    val (from, to) = when (format) {
        AssetExportFormat.Pdf -> palette.pdfFrom to palette.pdfTo
        AssetExportFormat.Excel -> palette.sheetFrom to palette.sheetTo
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .shadow(3.dp, BADGE_SHAPE)
            .clip(BADGE_SHAPE)
            .background(Brush.linearGradient(listOf(from, to))),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = format.badge,
            style = ZillitTheme.typography.numeric.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** `.pdf` on hover — shown by alpha, never composed on hover, so the card keeps its size under the pointer. */
@Composable
private fun ExtensionChip(format: AssetExportFormat, visible: Boolean) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (visible) 1f else 0f)
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.divider, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        ZillitText(
            text = ".${format.wire}",
            style = ZillitTheme.typography.numeric.copy(fontSize = 10.sp),
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

internal val EXPORT_BUTTON_HEIGHT = 36.dp
private val MENU_GAP = 8.dp
private val MENU_SHADOW_ROOM = 12.dp
private val TRIGGER_SHAPE = RoundedCornerShape(10.dp)
private val MENU_SHAPE = RoundedCornerShape(16.dp)
private val BADGE_SHAPE = RoundedCornerShape(10.dp)
private const val MENU_MILLIS = 150
private const val MENU_SCALE = 0.95f
private const val DISABLED_ALPHA = 0.5f
