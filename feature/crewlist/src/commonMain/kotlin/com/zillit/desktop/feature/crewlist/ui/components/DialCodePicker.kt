package com.zillit.desktop.feature.crewlist.ui.components

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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.DialCode

/**
 * The dial-code selector beside a phone input — the web's searchable,
 * clearable antd `Select` (ZL-20023): a narrow trigger showing `+44`, and a
 * wider list naming every country so the right one can be found.
 */
@Composable
internal fun DialCodePicker(
    value: String,
    codes: List<DialCode>,
    placeholder: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    height: androidx.compose.ui.unit.Dp = CELL_HEIGHT,
) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Trigger(value, placeholder, open, isError, height, onOpen = { open = true }, onClear = { onPick("") })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(colors.surfaceRaised, RoundedCornerShape(8.dp)),
        ) {
            DialCodeList(
                codes = codes,
                selected = value,
                onPick = { code ->
                    open = false
                    onPick(code)
                },
            )
        }
    }
}

/** The closed picker: the code or its placeholder, a chevron — a clear button on hover once one is set. */
@Composable
private fun Trigger(
    value: String,
    placeholder: String,
    open: Boolean,
    isError: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        isError -> crewPalette().error
        open || hovered -> colors.accent
        else -> colors.borderStrong
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CELL_SHAPE)
            .background(colors.surface)
            .border(if (open) 1.5.dp else 1.dp, border, CELL_SHAPE)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitText(
            text = value.ifEmpty { placeholder },
            style = ZillitTheme.typography.bodyMedium,
            color = if (value.isEmpty()) colors.textDisabled else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty() && hovered) {
            Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClear).padding(2.dp)) {
                ZillitIcon(ZillitIcons.Close, contentDescription = "Clear code", tint = colors.textMuted, size = 12.dp)
            }
        } else {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 13.dp)
        }
    }
}

@Composable
private fun DialCodeList(codes: List<DialCode>, selected: String, onPick: (String) -> Unit) {
    val colors = ZillitTheme.colors
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shown = remember(codes, query) {
        val needle = query.trim()
        if (needle.isEmpty()) codes else codes.filter { it.label.contains(needle, ignoreCase = true) }
    }
    Column(Modifier.width(LIST_WIDTH).padding(horizontal = 8.dp, vertical = 4.dp)) {
        ZillitSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search country or code",
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // A FIXED height: a lazy list inside a menu measures its intrinsics on
        // open and crashes under a mere `heightIn`.
        LazyColumn(Modifier.fillMaxWidth().height(LIST_HEIGHT).padding(top = 6.dp)) {
            if (shown.isEmpty()) {
                item {
                    ZillitText(
                        text = "No matching country",
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            items(shown, key = { "${it.isoCode}-${it.dialCode}-${it.name}" }) { code ->
                val picked = code.dialCode == selected
                val rowInteraction = remember { MutableInteractionSource() }
                val rowHovered by rowInteraction.collectIsHoveredAsState()
                ZillitText(
                    text = code.label,
                    style = ZillitTheme.typography.bodyMedium,
                    color = if (picked) colors.accentText else colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                picked -> colors.surfaceSelected
                                rowHovered -> colors.surfaceHover
                                else -> colors.surfaceRaised
                            },
                        )
                        .hoverable(rowInteraction)
                        .clickable(interactionSource = rowInteraction, indication = null) { onPick(code.dialCode) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private val LIST_WIDTH = 280.dp
private val LIST_HEIGHT = 280.dp
