package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A column's width from its translated title — the web's `headerWidthFor`:
 * about 7.2 px a character plus padding, never narrower than the checkboxes
 * under it (120) and never so wide one translation eats the window (170) — a
 * longer title wraps to a second line instead.
 */
internal fun headerWidth(label: String): Dp =
    (label.length * CHAR_WIDTH + HEADER_PADDING).toInt().coerceIn(HEADER_MIN, HEADER_MAX).dp

private const val CHAR_WIDTH = 7.2f
private const val HEADER_PADDING = 24
private const val HEADER_MIN = 120
private const val HEADER_MAX = 170

/** The amber bar along the top of both grids — brand, in both themes. */
@Composable
internal fun GridToolbar(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.accent)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** "Loading…" in the middle of the page, where the table will be. */
@Composable
internal fun GridLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitSpinner()
            ZillitText(
                text = "${str(S.loading_)}…",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

@Composable
internal fun GridCentred(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * antd's pager, bottom-left as the web places it: `1-20 of 437`, the page
 * numbers with their ellipses, a size changer, and a "Go to" box with a Go
 * button beside it — the web added the button because Enter alone gave no
 * sign it was the way to commit (Enter still works).
 */
@Suppress("LongParameterList")
@Composable
internal fun GridPager(
    page: Int,
    pageSize: Int,
    total: Int,
    onPage: (Int) -> Unit,
    onPageSize: (Int) -> Unit,
    enabled: Boolean = true,
) {
    val lastPage = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
    val first = if (total == 0) 0 else (page - 1) * pageSize + 1
    val last = (page * pageSize).coerceAtMost(total)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = str(S.desktop_pg_range, first, last, total),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = str(S.docusign_tour_prev),
            onClick = { onPage(page - 1) },
            enabled = enabled && page > 1,
        )
        pageItems(page, lastPage).forEach { item ->
            if (item == null) {
                ZillitText(
                    text = "•••",
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                PageNumber(item, active = item == page, enabled = enabled, onClick = { onPage(item) })
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = str(S.next),
            onClick = { onPage(page + 1) },
            enabled = enabled && page < lastPage,
        )
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        ZillitSelect(
            value = pageSize,
            options = PAGE_SIZES,
            onSelect = onPageSize,
            label = { str(S.desktop_n_per_page, it) },
            enabled = enabled,
            showInitials = false,
            modifier = Modifier.width(SIZE_SELECT_WIDTH),
        )
        Spacer(Modifier.width(ZillitTheme.spacing.sm))
        QuickJumper(lastPage = lastPage, enabled = enabled, onPage = onPage)
    }
}

@Composable
private fun PageNumber(number: Int, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .widthIn(min = PAGE_ITEM)
            .height(PAGE_ITEM)
            .clip(ZillitTheme.shapes.small)
            .then(
                if (active) {
                    Modifier.background(colors.accentSoft).border(1.dp, colors.accent, ZillitTheme.shapes.small)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled && !active, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = number.toString(),
            style = ZillitTheme.typography.bodySmall,
            color = if (active) colors.accent else colors.textPrimary,
        )
    }
}

@Composable
private fun QuickJumper(lastPage: Int, enabled: Boolean, onPage: (Int) -> Unit) {
    var typed by remember { mutableStateOf("") }
    fun go() {
        val target = typed.trim().toIntOrNull() ?: return
        onPage(target.coerceIn(1, lastPage))
        typed = ""
    }
    ZillitText(
        text = str(S.desktop_pg_go_to),
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    ZillitTextField(
        value = typed,
        onValueChange = { text -> typed = text.filter(Char::isDigit).take(JUMPER_DIGITS) },
        enabled = enabled,
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Go,
        onImeAction = ::go,
        modifier = Modifier.width(JUMPER_WIDTH),
    )
    ZillitButton(
        text = str(S.desktop_pg_go),
        onClick = ::go,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        enabled = enabled && typed.isNotBlank(),
    )
}

/**
 * antd's page list: always the first and last, the current page with two
 * either side, and an ellipsis (null) where pages are skipped.
 */
internal fun pageItems(page: Int, lastPage: Int): List<Int?> {
    if (lastPage <= SHOW_ALL_UP_TO) return (1..lastPage).toList()
    val from = (page - NEIGHBOURS).coerceAtLeast(2).coerceAtMost(lastPage - WINDOW + 1)
    val to = (page + NEIGHBOURS).coerceAtMost(lastPage - 1).coerceAtLeast(WINDOW)
    return buildList {
        add(1)
        if (from > 2) add(null)
        addAll(from..to)
        if (to < lastPage - 1) add(null)
        add(lastPage)
    }
}

/** The ⓘ glyph beside a select — the web's `InfoCircleOutlined`. */
@Composable
internal fun InfoGlyph(tint: androidx.compose.ui.graphics.Color = ZillitTheme.colors.textMuted) {
    ZillitIcon(icon = ZillitIcons.Info, tint = tint, size = INFO_SIZE)
}

private const val SHOW_ALL_UP_TO = 7
/** Pages shown either side of the current one, and the run they make with it. */
private const val NEIGHBOURS = 2
private const val WINDOW = 5
private const val JUMPER_DIGITS = 5
private val PAGE_ITEM = 32.dp
private val SIZE_SELECT_WIDTH = 130.dp
private val JUMPER_WIDTH = 64.dp
private val INFO_SIZE = 14.dp

