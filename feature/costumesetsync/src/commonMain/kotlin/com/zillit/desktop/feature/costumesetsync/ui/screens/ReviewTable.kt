package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText

/**
 * The web's bordered, self-scrolling review tables (`.csync-scripttable`, `.csync-charconfirm__table`): a
 * header that stays put over a body that scrolls on its own, columns that share the dialog's width (a fixed
 * [Dp] each, `null` = the one that takes what is left) instead of scrolling sideways.
 */
internal class ReviewStyle(
    val radius: Dp,
    val padX: Dp,
    val padY: Dp,
    val headSize: Float,
    val headTrack: Float,
    val maxHeight: Dp,
    val lightBorder: Boolean,
    val headSunken: Boolean,
)

/** `.csync-scripttable`: 14px rows, 10/12 padding, white 12px headers. */
internal val ScriptTableStyle = ReviewStyle(8.dp, 12.dp, 10.dp, 12f, 0.6f, 420.dp, lightBorder = false, headSunken = false)

/** `.csync-table` inside `.csync-charconfirm__table`: 13px rows, 8/10 padding, tinted 11px headers. */
internal val PlainTableStyle = ReviewStyle(10.dp, 10.dp, 8.dp, 11f, 0.44f, 440.dp, lightBorder = true, headSunken = true)

/** The text of a review table's body: 14px in the script/schedule reviews. */
internal val ReviewText: TextStyle @Composable get() = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp)

internal class ReviewRowScope(private val row: RowScope, private val cols: List<Dp?>, private val style: ReviewStyle) {
    /** The [i]th cell of this row. */
    @Composable
    fun cell(i: Int, content: @Composable ColumnScope.() -> Unit) {
        val w = cols[i]
        row.run {
            Column(
                (if (w == null) Modifier.weight(1f) else Modifier.width(w)).padding(horizontal = style.padX, vertical = style.padY),
                content = content,
            )
        }
    }
}

@Composable
internal fun ReviewTable(
    cols: List<Dp?>,
    heads: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    style: ReviewStyle = ScriptTableStyle,
    body: @Composable ReviewBodyScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(style.radius)
    Column(modifier.fillMaxWidth().clip(shape).border(1.dp, if (style.lightBorder) colors.divider else colors.border, shape)) {
        Row(Modifier.fillMaxWidth().background(if (style.headSunken) colors.surfaceSunken else colors.surface), verticalAlignment = Alignment.CenterVertically) {
            val scope = ReviewRowScope(this, cols, style)
            heads.forEachIndexed { i, head -> scope.cell(i) { head() } }
        }
        ZillitDivider()
        Column(Modifier.heightIn(max = style.maxHeight).verticalScroll(rememberScrollState())) {
            ReviewBodyScope(this, cols, style).body()
        }
    }
}

internal class ReviewBodyScope(private val column: ColumnScope, private val cols: List<Dp?>, private val style: ReviewStyle) {
    /** One row; a rule under it unless it is [last]. */
    @Composable
    fun row(last: Boolean, modifier: Modifier = Modifier, content: @Composable ReviewRowScope.() -> Unit) {
        column.run {
            Row(Modifier.fillMaxWidth().then(modifier), verticalAlignment = Alignment.CenterVertically) {
                ReviewRowScope(this, cols, style).content()
            }
            if (!last) ZillitDivider()
        }
    }

    /** A row spanning every column (the hand-correction fields). */
    @Composable
    fun wide(content: @Composable ColumnScope.() -> Unit) {
        column.run {
            Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surfaceSunken).padding(horizontal = style.padX, vertical = style.padY), content = content)
            ZillitDivider()
        }
    }
}

/** A header label: 11–12px uppercase, tracked, muted. */
@Composable
internal fun ReviewHead(text: String, style: ReviewStyle = ScriptTableStyle) {
    ZillitText(
        text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontSize = style.headSize.sp, fontWeight = FontWeight.SemiBold, letterSpacing = style.headTrack.sp),
        color = ZillitTheme.colors.textMuted,
        maxLines = 1,
    )
}

/** The web's `.csync-chip`: a 11px pill on the sunken tint with a hairline. */
@Composable
internal fun ChipPill(text: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(999.dp)
    ZillitText(
        text,
        modifier.clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape).padding(horizontal = 7.dp, vertical = 1.dp),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
        maxLines = 1,
    )
}

/** antd's `<Button size="small" danger>`: white, red hairline, red text. */
@Composable
internal fun OutlineDangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val tint = if (enabled) colors.danger else colors.textDisabled
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (enabled) colors.danger else colors.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ZillitText(text, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = tint, maxLines = 1)
    }
}

/** The `.csync-sched`/`.csync-docview`/`.csync-picklist` checkbox: 18px, 4px radius, ink when checked (not the brand orange). */
@Composable
internal fun InkCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, label: String? = null) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(shape)
                .background(if (checked) colors.textPrimary else colors.surface)
                .border(1.dp, if (checked) colors.textPrimary else colors.borderStrong, shape)
                .alpha(if (enabled) 1f else 0.5f),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) ZillitIcon(ZillitIcons.Check, tint = colors.surface, size = 12.dp)
        }
        label?.let { ZillitText(it, style = ZillitTheme.typography.bodyMedium.copy(fontSize = 14.sp), maxLines = 2) }
    }
}
