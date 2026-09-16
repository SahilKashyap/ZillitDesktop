@file:Suppress("MagicNumber") // Layout constants for one tool's chrome, all named below.

package com.zillit.desktop.feature.formsignature.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The tool's header bar — the web's white strip with a back caret and the
 * screen's title, and room on the right for the screen's own control (the
 * discussion room's "Select User").
 */
@Composable
internal fun ToolTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (onBack != null) {
                ZillitIconButton(icon = ZillitIcons.ChevronLeft, contentDescription = "Back", onClick = onBack)
            }
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke(this)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

/**
 * The web's antd `Segmented` with a badge on a tab — the design system's
 * segmented control has no count slot, and the Received / Fully Signed
 * tabs wear one.
 */
@Composable
internal fun BadgedSegmented(
    options: List<ZillitTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val active = option.id == activeId
            Row(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.surface else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onSelect(option.id) }
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitText(
                    text = option.label,
                    style = ZillitTheme.typography.label,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                if (option.count > 0) ZillitBadge(count = option.count)
            }
        }
    }
}

/** The web's antd `Alert type="info" showIcon` — a tinted band with an icon. */
@Composable
internal fun InfoBand(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.infoSoft)
            .border(1.dp, colors.info.copy(alpha = 0.35f), ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitIcon(ZillitIcons.Info, tint = colors.info, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A person as the web's `UserChip` shows them: their picture (initials without one) and name. */
@Composable
internal fun PersonChip(
    name: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    userId: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = name.ifBlank { "?" }, userId = userId, size = 28.dp)
        Column {
            ZillitText(text = name.ifBlank { "—" }, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            if (!subtitle.isNullOrBlank()) {
                ZillitText(
                    text = subtitle,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The web's file chip under a picked document: an extension badge and the name. */
@Composable
internal fun FileChip(name: String, extension: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (extension.equals("PDF", true)) Color(0xFFFFE4E4) else Color(0xFFE3ECFF)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = extension.take(4).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (extension.equals("PDF", true)) Color(0xFFD64545) else Color(0xFF2B6BD8),
            )
        }
        Column(Modifier.weight(1f)) {
            ZillitText(text = name, style = ZillitTheme.typography.bodyMedium, maxLines = 1)
            ZillitText(
                text = extension.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
        }
    }
}

/** The web's `dateTimeFormat`: `Oct 17, 2023 at 11:21 AM`. */
internal fun formDateTime(millis: Long?): String {
    if (millis == null || millis <= 0) return "—"
    val local = runCatching {
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
    }.getOrNull() ?: return "—"
    val month = local.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val hour12 = when (local.hour % 12) { 0 -> 12; else -> local.hour % 12 }
    val minute = local.minute.toString().padStart(2, '0')
    val meridiem = if (local.hour < 12) "AM" else "PM"
    return "$month ${local.day.toString().padStart(2, '0')}, ${local.year} at " +
        "${hour12.toString().padStart(2, '0')}:$minute $meridiem"
}

/** `04 Aug, 2026` for the signature cards' "Created" line. */
internal fun formDate(millis: Long?): String = EpochDate.date(millis).ifBlank { "—" }
