package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.rememberWheelScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import kotlinx.datetime.LocalTime

/**
 * A time field with a list of times to pick from.
 *
 * Editable for the same reason [DatePickerField] is: someone entering a 05:45
 * crew call types it faster than they scroll to it, and a picker that takes
 * the keyboard away is the one people complain about. The list runs in
 * quarter-hour steps — fine enough for call times, coarse enough to scan —
 * and opens at the time already in the field, or at a working-day morning
 * when the field is blank.
 */
@Composable
internal fun TimePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "09:00",
    errorText: String? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            errorText = errorText,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                ZillitIconButton(
                    icon = ZillitIcons.Clock,
                    contentDescription = "Choose a time",
                    onClick = { open = true },
                )
            },
        )

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TimeList(
                selected = value.trim().toLocalTimeOrNull(),
                onPick = { time ->
                    onValueChange(time.hhmmText())
                    open = false
                },
            )
        }
    }
}

@Composable
private fun TimeList(selected: LocalTime?, onPick: (LocalTime) -> Unit) {
    val anchor = selected?.let { it.hour * STEPS_PER_HOUR + it.minute / STEP_MINUTES }
        ?: DEFAULT_SLOT
    // One row of context above the anchor, so the chosen time reads as "in
    // the list" rather than pinned to the popup's edge.
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (anchor - 1).coerceAtLeast(0),
    )

    // Fixed, not heightIn: the menu asks its content for intrinsic
    // measurements, which a lazy list cannot answer — and ninety-six rows
    // overflow any cap regardless, so nothing is lost by pinning it.
    LazyColumn(
        state = state,
        modifier = Modifier
            .width(TIME_MENU_WIDTH)
            .height(TIME_LIST_HEIGHT)
            .padding(horizontal = ZillitTheme.spacing.xs)
            .then(rememberWheelScroll(state)),
    ) {
        items(SLOT_COUNT) { slot ->
            val time = LocalTime(slot / STEPS_PER_HOUR, (slot % STEPS_PER_HOUR) * STEP_MINUTES)
            TimeChoice(
                time = time,
                selected = time == selected,
                onClick = { onPick(time) },
            )
        }
    }
}

@Composable
private fun TimeChoice(time: LocalTime, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    ZillitText(
        text = time.hhmmText(),
        style = ZillitTheme.typography.bodySmall.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (selected) colors.accentText else colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.small)
            .background(
                when {
                    selected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    )
}

private const val STEP_MINUTES = 15
private const val STEPS_PER_HOUR = 60 / STEP_MINUTES
private const val SLOT_COUNT = 24 * STEPS_PER_HOUR

/** 09:00 — where a blank field's list opens. */
private const val DEFAULT_SLOT = 9 * STEPS_PER_HOUR

private val TIME_MENU_WIDTH = 132.dp
private val TIME_LIST_HEIGHT = 236.dp
