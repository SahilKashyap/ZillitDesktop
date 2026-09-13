package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/**
 * The deal's setup picker above the memo card (a deal from the Create menu):
 * which setup it starts from, and Reset to put that setup back over it.
 */
@Composable
internal fun SetupPickerBanner(state: DealMemoUiState, builder: BuilderState, onEvent: (DealMemoEvent) -> Unit) {
    val p = bp
    val group = builder.mode.dealGroup ?: return
    val rows = state.templates.rows
    val setups = when {
        rows != null -> rows.filter { it.nonUnion == (group == SetupGroup.NonUnion) }
        state.templates.failed -> emptyList()
        else -> null
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(p.pickerBg)
            .border(1.dp, p.pickerBorder, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = "This deal starts from a ${group.label} setup",
                style = DmType.sans(15.5.sp, FontWeight.Bold),
                color = p.pickerTitle,
            )
            ZillitText(
                text = "Switching reloads rates, allowances and conditions — later edits apply to this deal only.",
                style = DmType.sans(14.sp).copy(lineHeight = 21.sp),
                color = p.pickerBody,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        RichSelect(
            options = setups.orEmpty().map { PickOption(key = it.id, label = it.name, search = it.name) },
            selectedKey = builder.pickedTemplateId,
            onPick = { onEvent(BuilderEvent.PickSetup(it)) },
            placeholder = when {
                setups == null -> "Loading setups…"
                setups.isEmpty() -> "No ${group.label} setups yet"
                else -> "Select a setup…"
            },
            enabled = setups != null,
            dropdownWidth = 300.dp,
            height = 42.dp,
            modifier = Modifier.width(240.dp),
        )
        ResetButton(enabled = builder.pickedTemplateId != null) { onEvent(BuilderEvent.ResetToSetup) }
    }
}

@Composable
private fun ResetButton(enabled: Boolean, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .height(42.dp)
            .clip(shape)
            .background(if (hovered && enabled) p.chipHover else p.card)
            .border(1.dp, p.hairline, shape)
            .hoverable(source)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = "Reset to setup",
            style = DmType.sans(13.5.sp, FontWeight.Bold),
            color = p.title,
            maxLines = 1,
        )
    }
}

private const val DISABLED_ALPHA = 0.5f
