package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.DialCode
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The dial-code selector beside a phone input — the web's searchable,
 * clearable antd `Select` (ZL-20023): a narrow trigger showing `+44`, and a
 * wider list naming every country so the right one can be found. The trigger
 * is the crew cell's own (it sits flush beside a cell field, at that field's
 * height); the list is the app's one ([ZillitOptionPopup]) — the code bold,
 * the country beneath, either searchable.
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
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Trigger(value, placeholder, open, isError, height, onOpen = { open = true }, onClear = { onPick("") })
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = LIST_WIDTH,
                options = codes,
                isSelected = { it.dialCode == value },
                onPick = { code ->
                    open = false
                    onPick(code.dialCode)
                },
                label = { it.dialCode },
                subtitle = { it.name },
                searchText = { it.label },
                searchable = true,
                searchPlaceholder = str(S.desktop_search_country_or_code),
                showInitials = false,
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
                ZillitIcon(
                    ZillitIcons.Close,
                    contentDescription = str(S.desktop_clear_code),
                    tint = colors.textMuted,
                    size = 12.dp,
                )
            }
        } else {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 13.dp)
        }
    }
}

private val LIST_WIDTH = 280.dp
