package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.t

/*
 * Small pieces shared by the continuity / reports / budget / vendors / notifications /
 * setup screens. Prefixed `kit` so they never clash with a neighbouring screen's helper.
 */

/**
 * A sub-tab strip inside a screen (the web's `SubTabs`): 14px labels, 8x16 padding, a 1px rule under the
 * row; the open tab is bold ink with a 2px ink underline, not the accent.
 */
@Composable
internal fun KitTabs(tabs: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    val colors = ZillitTheme.colors
    val ink = colors.textPrimary
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(colors.border))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { (id, label) ->
                val on = id == value
                Box(
                    Modifier.clickable { onChange(id) }
                        .drawBehind { if (on) drawRect(ink, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx())) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ZillitText(
                        label,
                        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (on) colors.textPrimary else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "1 scene" / "3 scenes": [key] is the plural key, `<key>_one` the singular; both carry `{n}`. */
internal fun kitCount(key: String, n: Int): String = t(if (n == 1) "${key}_one" else key, "n" to n)

/** A confirm dialog (the web's `Modal.confirm`). */
@Composable
internal fun KitConfirm(
    open: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    FormDialog(open = open, title = title, onDismiss = onDismiss, confirmLabel = confirmLabel, onConfirm = onConfirm, danger = danger, width = CONFIRM_WIDTH) {
        ZillitText(body)
    }
}

/** A grid header row: [headers] over columns of [weights]. */
@Composable
internal fun KitHeader(headers: List<String>, weights: List<Float>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        headers.forEachIndexed { i, h ->
            ZillitText(h.uppercase(), Modifier.weight(weights[i]), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1)
        }
    }
    ZillitDivider()
}

/** One grid row of [cells] laid out by [weights]. */
@Composable
internal fun KitGridRow(weights: List<Float>, cells: List<@Composable () -> Unit>, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            androidx.compose.foundation.layout.Box(Modifier.weight(weights[i])) { cell() }
        }
    }
    if (bold) ZillitDivider()
}

/** A plain text grid cell. */
@Composable
internal fun KitText(text: String, mono: Boolean = false, strong: Boolean = false, maxLines: Int = 2) {
    var style = ZillitTheme.typography.bodyMedium
    if (mono) style = style.copy(fontFamily = FontFamily.Monospace)
    if (strong) style = style.copy(fontWeight = FontWeight.SemiBold)
    ZillitText(text.ifBlank { "" }, style = style, maxLines = maxLines)
}

@Composable
internal fun KitLabelledNote(label: String, value: String) {
    if (value.isBlank()) return
    MutedText("$label: $value", maxLines = 3)
}

internal val KIT_FIELD_WIDTH = 190.dp
internal val KIT_DATE_WIDTH = 200.dp

private val CONFIRM_WIDTH = 460.dp

/** A fixed-width cell modifier for form rows. */
internal fun kitWidth(width: androidx.compose.ui.unit.Dp): Modifier = Modifier.width(width)

/** "5 minutes ago" / "in 2 hours" — the web's `relativeTime`, on the `csync_*_ago` / `csync_in_*` strings. */
internal fun kitRelativeTime(ms: Long, now: Long): String {
    if (ms == 0L) return ""
    val mins = ((now - ms) / MS_PER_MINUTE.toDouble()).let { kotlin.math.round(it).toLong() }
    fun say(past: String, future: String, n: Long) = t(if (n > 0) past else future, "n" to kotlin.math.abs(n))
    if (kotlin.math.abs(mins) < 1) return t("csync_just_now")
    if (kotlin.math.abs(mins) < MINUTES_PER_HOUR) return say("csync_minutes_ago", "csync_in_minutes", mins)
    val hours = kotlin.math.round(mins / MINUTES_PER_HOUR.toDouble()).toLong()
    if (kotlin.math.abs(hours) < HOURS_PER_DAY) return say("csync_hours_ago", "csync_in_hours", hours)
    return say("csync_days_ago", "csync_in_days", kotlin.math.round(hours / HOURS_PER_DAY.toDouble()).toLong())
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
