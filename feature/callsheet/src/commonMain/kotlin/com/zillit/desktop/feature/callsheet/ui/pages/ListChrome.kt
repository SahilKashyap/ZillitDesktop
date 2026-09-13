package com.zillit.desktop.feature.callsheet.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ModeSegmented
import com.zillit.desktop.feature.callsheet.ui.components.ModeTab
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme
import kotlinx.coroutines.delay

/**
 * The list view's sticky toolbar — `.csc-toolbar`: the tool's name,
 * "Call Sheet Creation" for posting users and "Drafts Call Sheet" for
 * everyone else. The name waits for the rights, so a poster never sees it flip
 * (B-10).
 */
@Composable
internal fun SheetToolbar(state: SheetUiState) {
    val colors = SheetTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(colors.dsBg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(colors.dsPrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ZillitToolIcons.IcContinuity,
                contentDescription = null,
                tint = colors.dsPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (state.viewer.ready) {
            Text(
                toolTitle(state.isPoster),
                style = sheetText(18.sp, FontWeight.Bold, 24.sp).copy(letterSpacing = (-0.18).sp),
                color = colors.dsText,
                maxLines = 1,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.dsBorder))
}

/** The labels API name when the dictionary has one, the web's English otherwise. */
internal fun toolTitle(isPoster: Boolean): String = if (isPoster) {
    Labels.current.exact("call_sheet_label") ?: "Call Sheet Creation"
} else {
    Labels.current.exact("drafts_call_sheet_label") ?: "Drafts Call Sheet"
}

/**
 * The tab row — `.csc-segment-row`: the large segmented tabs once rights have
 * answered, and "＋ Create Template" for posting users.
 */
@Composable
internal fun SheetTabRow(state: SheetUiState, onEvent: (SheetEvent) -> Unit) {
    val tabs = state.tabs
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tabs.isNotEmpty()) {
            ModeSegmented(
                tabs = tabs.map { ModeTab(it, it.label, state.tabBadge(it)) },
                selected = state.activeTab,
                onSelect = { onEvent(ListEvent.OpenTab(it)) },
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.isPoster) CreateTemplateButton { onEvent(DialogEvent.CreateTemplate) }
    }
}

/** `.csc-btn.csc-btn--primary` "＋ Create Template" — hub amber with its warm glow. */
@Composable
private fun CreateTemplateButton(onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val glow = Color(if (hovered) 0x59E8930C else 0x4DE8930C)
    Row(
        modifier = Modifier
            .shadow(if (hovered) 8.dp else 5.dp, RoundedCornerShape(10.dp), ambientColor = glow, spotColor = glow)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) colors.dsPrimaryHover else colors.dsPrimary)
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("＋ Create Template", style = sheetText(13.sp, FontWeight.SemiBold), color = Color.White)
    }
}

/**
 * The web's activity pill: shown only once a request has been out for 250 ms,
 * bottom-right, never in the way.
 */
@Composable
internal fun ActivityPill(active: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            delay(PILL_DELAY_MS)
            visible = true
        } else {
            visible = false
        }
    }
    val colors = SheetTheme.colors
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
        Row(
            modifier = Modifier
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.elevated)
                .border(1.dp, colors.border, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .widthIn(min = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(Modifier.size(14.dp))
            Text("Loading…", style = sheetText(12.sp, FontWeight.Medium), color = colors.textSecondary)
        }
    }
}

/** A 2 px orange ring with a gap, turning. */
@Composable
internal fun Spinner(modifier: Modifier = Modifier, color: Color = Color(0xFFF99300)) {
    val turn = rememberInfiniteTransition()
    val angle by turn.animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier.rotate(angle)) {
        val stroke = 2.dp.toPx()
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private const val PILL_DELAY_MS = 250L
private const val FADE_MS = 150
private const val SPIN_MS = 800
