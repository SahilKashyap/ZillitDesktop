package com.zillit.desktop.feature.recce.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A clock field — typed `HH:mm`, or picked from a dropdown of five-minute
 * slots, the web's `TimePicker minuteStep={5}`. Typing still works: a
 * schedule with twelve stops is typed faster than it is clicked.
 */
@Composable
internal fun RecceTimeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = "HH:mm",
            errorText = errorText,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitIconButton(
                    icon = ZillitIcons.Clock,
                    contentDescription = str(S.desktop_choose_a_time),
                    onClick = { if (enabled) open = true },
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TimeSlots(selected = value.trim()) { picked ->
                onValueChange(picked)
                open = false
            }
        }
    }
}

/** The 288 five-minute slots. A FIXED height: a lazy list in a menu measures against infinity otherwise. */
@Composable
private fun TimeSlots(selected: String, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    val slots = remember { (0 until MINUTES_PER_DAY step SLOT_MINUTES).map { m -> "%02d:%02d".format(m / 60, m % 60) } }
    val state = rememberLazyListState()
    LaunchedEffect(selected) {
        val index = slots.indexOf(selected).takeIf { it >= 0 } ?: slots.indexOf(DEFAULT_SLOT)
        state.scrollToItem((index - VISIBLE_ABOVE).coerceAtLeast(0))
    }
    LazyColumn(state = state, modifier = Modifier.width(SLOT_WIDTH).height(SLOT_LIST_HEIGHT)) {
        items(slots.size) { index ->
            val slot = slots[index]
            val active = slot == selected
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            active -> colors.accentSoft
                            hovered -> colors.surfaceHover
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .clickable { onPick(slot) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                ZillitText(
                    text = slot,
                    style = ZillitTheme.typography.bodyMedium.copy(
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (active) colors.accentText else colors.textPrimary,
                )
            }
        }
    }
}

/** A label above a control the text field cannot wrap — a select, the location block. */
@Composable
internal fun Labelled(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    errorText: String? = null,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (required) ZillitText(text = "*", style = ZillitTheme.typography.label, color = colors.danger)
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label,
                color = if (errorText != null) colors.danger else colors.textSecondary,
            )
            hint?.let {
                ZillitText(
                    text = it,
                    style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Normal),
                    color = colors.textMuted,
                )
            }
        }
        content()
        errorText?.let {
            ZillitText(text = it, style = ZillitTheme.typography.labelSmall, color = colors.danger)
        }
    }
}

/** One option of [RecceSegmented]. */
internal data class SegmentOption(val id: String, val label: String, val count: Int)

/**
 * The list's All / Published / Drafts switch — the web's brand-pill
 * `Segmented`: a sunken track, the active tab a solid amber pill with its
 * count in a translucent chip, the idle tabs muted with their counts faint.
 */
@Composable
internal fun RecceSegmented(
    options: List<SegmentOption>,
    activeId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option.id == activeId
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val background by animateColorAsState(
                if (active) RecceColors.Brand else Color.Transparent,
                label = "segmentBackground",
            )
            val text by animateColorAsState(
                when {
                    active -> Color.White
                    hovered -> RecceColors.Brand
                    else -> colors.textSecondary
                },
                label = "segmentText",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(background)
                    .hoverable(interaction)
                    .clickable { onSelect(option.id) }
                    .height(SEGMENT_HEIGHT)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(
                    text = option.label,
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = text,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Color(0x38FFFFFF) else colors.surface)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    ZillitText(
                        text = option.count.toString(),
                        style = ZillitTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = if (active) Color.White else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** A dashed-outline full-width "add another" row — the web's `az-add-row`. */
@Composable
internal fun DashedAddRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint by animateColorAsState(if (hovered) RecceColors.Brand else colors.textSecondary, label = "addRowTint")
    val fill by animateColorAsState(if (hovered) colors.accentSoft else Color.Transparent, label = "addRowFill")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .dashedBorder(if (hovered) RecceColors.Brand else colors.borderStrong, RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        ZillitIcon(icon = ZillitIcons.Add, tint = tint, size = 16.dp)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
        )
    }
}

private const val MINUTES_PER_DAY = 24 * 60
private const val SLOT_MINUTES = 5
private const val DEFAULT_SLOT = "09:00"
private const val VISIBLE_ABOVE = 2
private val SLOT_WIDTH = 120.dp
private val SLOT_LIST_HEIGHT = 240.dp
private val SEGMENT_HEIGHT = 40.dp
